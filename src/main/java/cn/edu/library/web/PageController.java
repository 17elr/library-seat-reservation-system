package cn.edu.library.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping({
        "/",
        "/login",
        "/book",
        "/reservations",
        "/admin",
        "/profile",
        "/violations",
        "/notices",
        "/rooms",
        "/seats",
        "/users",
        "/rules",
        "/audits",
    })
    public String app() {
        return "forward:/index.html";
    }
}
