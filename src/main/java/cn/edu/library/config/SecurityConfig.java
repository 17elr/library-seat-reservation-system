package cn.edu.library.config;

import cn.edu.library.mapper.LibraryMapper;
import cn.edu.library.model.Models.User;

import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
public class SecurityConfig {
    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    ServletListenerRegistrationBean<HttpSessionEventPublisher> sessionEvents() {
        return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(LibraryMapper db) {
        return account -> {
            User u = db.userByAccount(account);
            if (u == null) throw new UsernameNotFoundException("账号或密码错误");
            return org.springframework.security.core.userdetails.User.withUsername(u.studentNo)
                    .password(u.passwordHash)
                    .roles(u.role)
                    .disabled(!u.enabled)
                    .build();
        };
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, SessionRegistry sessions) throws Exception {
        http.authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/login",
                                                "/css/**",
                                                "/js/**",
                                                "/images/**",
                                                "/error")
                                        .permitAll()
                                        .requestMatchers("/api/admin/**")
                                        .hasRole("ADMIN")
                                        .anyRequest()
                                        .authenticated())
                .formLogin(
                        login ->
                                login.loginPage("/login")
                                        .defaultSuccessUrl("/", true)
                                        .failureUrl("/login?error")
                                        .permitAll())
                .logout(
                        logout ->
                                logout.logoutSuccessUrl("/login?logout")
                                        .invalidateHttpSession(true)
                                        .deleteCookies("JSESSIONID"))
                .sessionManagement(
                        session ->
                                session.maximumSessions(-1)
                                        .sessionRegistry(sessions)
                                        .expiredSessionStrategy(
                                                event -> {
                                                    var response = event.getResponse();
                                                    if (event.getRequest()
                                                            .getRequestURI()
                                                            .startsWith("/api/")) {
                                                        response.setStatus(401);
                                                        response.setContentType(
                                                                "application/json;charset=UTF-8");
                                                        response.getWriter()
                                                                .write(
                                                                        "{\"message\":\"登录凭据已更新，请重新登录\"}");
                                                    } else response.sendRedirect("/login?expired");
                                                }))
                .exceptionHandling(
                        errors ->
                                errors.defaultAuthenticationEntryPointFor(
                                                (req, res, e) -> {
                                                    res.setStatus(401);
                                                    res.setContentType(
                                                            "application/json;charset=UTF-8");
                                                    res.getWriter()
                                                            .write("{\"message\":\"登录已过期，请重新登录\"}");
                                                },
                                                req -> req.getRequestURI().startsWith("/api/"))
                                        .accessDeniedHandler(
                                                (req, res, e) -> {
                                                    res.setStatus(403);
                                                    res.setContentType(
                                                            "application/json;charset=UTF-8");
                                                    res.getWriter()
                                                            .write(
                                                                    "{\"message\":\"无操作权限，或页面凭据已失效，请刷新后重试\"}");
                                                }));
        return http.build();
    }
}
