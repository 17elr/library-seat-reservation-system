package cn.edu.library.web;

import cn.edu.library.mapper.LibraryMapper;
import cn.edu.library.model.Models.*;
import cn.edu.library.service.*;
import cn.edu.library.web.Requests.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api")
@Validated
public class ApiController {
    private final LibraryService service;
    private final LibraryMapper db;

    public ApiController(LibraryService service, LibraryMapper db) {
        this.service = service;
        this.db = db;
    }

    private User actor(Principal p) {
        return service.user(p.getName());
    }

    @GetMapping("/bootstrap")
    public Object bootstrap(Principal p) {
        return service.bootstrap(actor(p));
    }

    @GetMapping("/rooms/schedules")
    public Object schedules(Principal p, @RequestParam LocalDate date) {
        actor(p);
        return service.schedules(date);
    }

    @GetMapping("/rooms/{id}/seats")
    public Object seats(
            Principal p,
            @PathVariable long id,
            @RequestParam LocalDateTime start,
            @RequestParam LocalDateTime end) {
        actor(p);
        return service.seats(id, start, end);
    }

    @PostMapping("/reservations")
    public Object book(Principal p, @Valid @RequestBody Booking input) {
        return service.book(actor(p), input);
    }

    @GetMapping("/me/reservations")
    public Object mine(
            Principal p,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page) {
        return service.reservations(actor(p).id, status, "", date, page);
    }

    @GetMapping("/reservations/{id}")
    public Object detail(Principal p, @PathVariable long id) {
        return service.detail(actor(p), id);
    }

    @PostMapping("/reservations/{id}/{action}")
    public Object action(Principal p, @PathVariable long id, @PathVariable String action) {
        return service.action(actor(p), id, action);
    }

    @GetMapping("/me/violations")
    public Object violations(
            Principal p, @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page) {
        return service.violations(actor(p).id, page);
    }

    @PostMapping("/me/password")
    public void password(
            Principal p,
            @Valid @RequestBody PasswordInput input,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        service.changePassword(actor(p), input);
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }

    @GetMapping("/admin/reservations")
    public Object reservations(
            Principal p,
            @RequestParam(defaultValue = "") @Size(max = 100) String search,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page) {
        actor(p);
        return service.reservations(null, status, search, date, page);
    }

    @PostMapping("/admin/reservations/{id}/close")
    public void close(Principal p, @PathVariable long id, @Valid @RequestBody Reason input) {
        service.close(actor(p), id, input.reason());
    }

    @GetMapping("/admin/violations")
    public Object violationsAll(
            Principal p, @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page) {
        actor(p);
        return service.violations(null, page);
    }

    @PostMapping("/admin/violations/{id}/revoke")
    public void revoke(Principal p, @PathVariable long id, @Valid @RequestBody Reason input) {
        service.revoke(actor(p), id, input.reason());
    }

    @GetMapping("/admin/users")
    public Object users(
            Principal p, @RequestParam(defaultValue = "") @Size(max = 100) String search) {
        actor(p);
        return db.users(search);
    }

    @PostMapping("/admin/users")
    public void user(Principal p, @Valid @RequestBody UserInput input) {
        service.addUser(actor(p), input);
    }

    @PostMapping("/admin/users/{id}/enabled")
    public void enabled(
            Principal p, @PathVariable long id, @Valid @RequestBody EnabledInput input) {
        service.enableUser(actor(p), id, input);
    }

    @PostMapping("/admin/users/{id}/password")
    public void resetPassword(
            Principal p, @PathVariable long id, @Valid @RequestBody ResetPasswordInput input) {
        service.resetPassword(actor(p), id, input);
    }

    @GetMapping("/admin/rooms")
    public Object rooms(Principal p) {
        actor(p);
        return db.rooms();
    }

    @PostMapping("/admin/rooms")
    public void addRoom(Principal p, @Valid @RequestBody RoomInput input) {
        service.saveRoom(actor(p), null, input);
    }

    @PutMapping("/admin/rooms/{id}")
    public void room(Principal p, @PathVariable long id, @Valid @RequestBody RoomInput input) {
        service.saveRoom(actor(p), id, input);
    }

    @GetMapping("/admin/rooms/{id}/seats")
    public Object seatsAll(Principal p, @PathVariable long id) {
        actor(p);
        return db.seats(id);
    }

    @PostMapping("/admin/seats")
    public void addSeat(Principal p, @Valid @RequestBody SeatInput input) {
        service.saveSeat(actor(p), null, input);
    }

    @PutMapping("/admin/seats/{id}")
    public void seat(Principal p, @PathVariable long id, @Valid @RequestBody SeatInput input) {
        service.saveSeat(actor(p), id, input);
    }

    @GetMapping("/admin/openings")
    public Object openings(Principal p) {
        actor(p);
        return db.openings();
    }

    @PostMapping("/admin/openings")
    public void opening(Principal p, @Valid @RequestBody OpeningInput input) {
        service.saveOpening(actor(p), input);
    }

    @GetMapping("/admin/notices")
    public Object notices(Principal p) {
        actor(p);
        return db.notices(true);
    }

    @PostMapping("/admin/notices")
    public void addNotice(Principal p, @Valid @RequestBody NoticeInput input) {
        service.saveNotice(actor(p), null, input);
    }

    @PutMapping("/admin/notices/{id}")
    public void notice(Principal p, @PathVariable long id, @Valid @RequestBody NoticeInput input) {
        service.saveNotice(actor(p), id, input);
    }

    @GetMapping("/admin/rules")
    public Object rules(Principal p) {
        actor(p);
        return db.rules();
    }

    @PostMapping("/admin/rules")
    public void rule(Principal p, @Valid @RequestBody RuleInput input) {
        service.saveRule(actor(p), input);
    }

    @GetMapping("/admin/statistics")
    public Object stats(Principal p, @RequestParam LocalDate start, @RequestParam LocalDate end) {
        actor(p);
        return service.statistics(start, end);
    }

    @GetMapping("/admin/audits")
    public Object audits(
            Principal p, @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page) {
        actor(p);
        return Map.of(
                "items",
                db.audits((page - 1) * 12, 12),
                "total",
                db.countAudits(),
                "page",
                page,
                "size",
                12);
    }
}
