package cn.edu.library.web;

import cn.edu.library.mapper.LibraryMapper;
import cn.edu.library.model.Models.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SessionAuthFilter extends OncePerRequestFilter {

    public static final String USER = "library.user";
    private final LibraryMapper db;
    private final ObjectMapper json;

    public SessionAuthFilter(LibraryMapper db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public static String token(HttpSession session) {
        String token = (String) session.getAttribute("csrf");
        if (token == null) {
            token = UUID.randomUUID().toString();
            session.setAttribute("csrf", token);
        }
        return token;
    }

    private void fail(HttpServletResponse response, int status, String message)
        throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(), Map.of("message", message));
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest req,
        HttpServletResponse res,
        FilterChain chain
    ) throws ServletException, IOException {
        String path =
            org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(
                req
            );
        if (!path.startsWith("/api/")) {
            chain.doFilter(req, res);
            return;
        }
        res.setHeader("Cache-Control", "no-store");
        boolean anonymous =
            path.equals("/api/auth/csrf") || path.equals("/api/auth/login");
        HttpSession session = req.getSession(false);
        if (!anonymous) {
            if (
                session == null ||
                !(session.getAttribute("userId") instanceof Long id)
            ) {
                fail(res, 401, "请先登录");
                return;
            }
            User user;
            try {
                user = db.user(id);
            } catch (
                org.springframework.dao.DataAccessResourceFailureException e
            ) {
                fail(res, 503, "服务暂不可用，请稍后重试");
                return;
            }
            if (
                user == null ||
                !user.enabled ||
                !Objects.equals(
                    session.getAttribute("authVersion"),
                    user.authVersion
                )
            ) {
                session.invalidate();
                fail(res, 401, "登录凭据已更新或账号停用，请重新登录");
                return;
            }
            req.setAttribute(USER, user);
            if (path.startsWith("/api/admin/") && !"ADMIN".equals(user.role)) {
                fail(res, 403, "只有管理员可以访问此功能");
                return;
            }
        }
        if (!Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod())) {
            String expected =
                    session == null
                        ? null
                        : (String) session.getAttribute("csrf"),
                provided = req.getHeader("X-CSRF-TOKEN");
            if (
                expected == null ||
                provided == null ||
                !MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    provided.getBytes(StandardCharsets.UTF_8)
                )
            ) {
                fail(res, 403, "页面凭据已失效，请刷新后重试");
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
