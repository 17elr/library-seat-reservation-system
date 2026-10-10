package cn.edu.library.web;

import cn.edu.library.service.LibraryService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;

@Controller
public class PageController {
    private final LibraryService service;

    public PageController(LibraryService service) {
        this.service = service;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String app(Principal principal, Model model) {
        model.addAttribute("user", service.user(principal.getName()));
        return "app";
    }
}
