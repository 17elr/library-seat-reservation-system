package cn.edu.library.web;

import cn.edu.library.mapper.LibraryMapper;
import cn.edu.library.model.Models.User;
import cn.edu.library.service.BusinessException;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final LibraryMapper db;
    private final PasswordEncoder passwords;

    public AuthController(LibraryMapper db, PasswordEncoder passwords) {
        this.db = db;
        this.passwords = passwords;
    }

    public record Login(
        @NotBlank @Size(max = 32) String account,
        @NotBlank @Size(max = 64) String password
    ) {}

    @GetMapping("/csrf")
    public Object csrf(HttpServletRequest req) {
        return Map.of("token", SessionAuthFilter.token(req.getSession(true)));
    }

    @PostMapping("/login")
    public Object login(
        @Valid @RequestBody Login input,
        HttpServletRequest req
    ) {
        User user = db.userByAccount(input.account());
        if (
            user == null ||
            !user.enabled ||
            !passwords.matches(input.password(), user.passwordHash)
        ) throw new BusinessException(
            HttpStatus.UNAUTHORIZED,
            "账号或密码错误，或账号已停用"
        );
        HttpSession session = req.getSession();
        req.changeSessionId();
        session.setAttribute("userId", user.id);
        session.setAttribute("authVersion", user.authVersion);
        session.removeAttribute("csrf");
        return Map.of("user", user, "token", SessionAuthFilter.token(session));
    }

    @GetMapping("/me")
    public Object me(HttpServletRequest req) {
        return req.getAttribute(SessionAuthFilter.USER);
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest req) {
        req.getSession().invalidate();
    }
}
