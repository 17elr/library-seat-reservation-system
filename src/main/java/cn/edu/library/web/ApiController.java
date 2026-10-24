package cn.edu.library.web;

import cn.edu.library.mapper.LibraryMapper;
import cn.edu.library.model.Models.*;
import cn.edu.library.service.*;
import cn.edu.library.web.Requests.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

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

    private User actor(HttpServletRequest p) {
        return (User) p.getAttribute(SessionAuthFilter.USER);
    }

    @GetMapping("/bootstrap")
    public Object bootstrap(HttpServletRequest p) {
        return service.bootstrap(actor(p));
    }

    @GetMapping("/rooms/schedules")
    public Object schedules(
        HttpServletRequest p,
        @RequestParam LocalDate date
    ) {
        actor(p);
        return service.schedules(date);
    }

    @GetMapping("/rooms/{id}/seats")
    public Object seats(
        HttpServletRequest p,
        @PathVariable long id,
        @RequestParam LocalDateTime start,
        @RequestParam LocalDateTime end
    ) {
        actor(p);
        return service.seats(id, start, end);
    }

    @PostMapping("/reservations")
    public Object book(
        HttpServletRequest p,
        @Valid @RequestBody Booking input
    ) {
        return service.book(actor(p), input);
    }

    @GetMapping("/me/reservations")
    public Object mine(
        HttpServletRequest p,
        @RequestParam(defaultValue = "") String status,
        @RequestParam(required = false) LocalDate date,
        @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page,
        @RequestParam(defaultValue = "12") @Min(1) @Max(100) int size
    ) {
        return service.reservations(actor(p).id, status, "", date, page, size);
    }

    @GetMapping("/reservations/{id}")
    public Object detail(HttpServletRequest p, @PathVariable long id) {
        return service.detail(actor(p), id);
    }

    @PostMapping("/reservations/{id}/{action}")
    public Object action(
        HttpServletRequest p,
        @PathVariable long id,
        @PathVariable String action
    ) {
        return service.action(actor(p), id, action);
    }

    @GetMapping("/me/violations")
    public Object violations(
        HttpServletRequest p,
        @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page
    ) {
        return service.violations(actor(p).id, page);
    }

    @PostMapping("/me/password")
    public void password(
        HttpServletRequest p,
        @Valid @RequestBody PasswordInput input,
        HttpServletResponse response
    ) {
        service.changePassword(actor(p), input);
        p.getSession().invalidate();
    }

    @GetMapping("/admin/reservations")
    public Object reservations(
        HttpServletRequest p,
        @RequestParam(defaultValue = "") @Size(max = 100) String search,
        @RequestParam(defaultValue = "") String status,
        @RequestParam(required = false) LocalDate date,
        @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page,
        @RequestParam(defaultValue = "12") @Min(1) @Max(100) int size
    ) {
        actor(p);
        return service.reservations(null, status, search, date, page, size);
    }

    @PostMapping("/admin/reservations/{id}/close")
    public void close(
        HttpServletRequest p,
        @PathVariable long id,
        @Valid @RequestBody Reason input
    ) {
        service.close(actor(p), id, input.reason());
    }

    @GetMapping("/admin/violations")
    public Object violationsAll(
        HttpServletRequest p,
        @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page
    ) {
        actor(p);
        return service.violations(null, page);
    }

    @PostMapping("/admin/violations/{id}/revoke")
    public void revoke(
        HttpServletRequest p,
        @PathVariable long id,
        @Valid @RequestBody Reason input
    ) {
        service.revoke(actor(p), id, input.reason());
    }

    @GetMapping("/admin/users")
    public Object users(
        HttpServletRequest p,
        @RequestParam(defaultValue = "") @Size(max = 100) String search
    ) {
        actor(p);
        return db.users(search);
    }

    @PostMapping("/admin/users")
    public void user(
        HttpServletRequest p,
        @Valid @RequestBody UserInput input
    ) {
        service.addUser(actor(p), input);
    }

    @PostMapping("/admin/users/{id}/enabled")
    public void enabled(
        HttpServletRequest p,
        @PathVariable long id,
        @Valid @RequestBody EnabledInput input
    ) {
        service.enableUser(actor(p), id, input);
    }

    @PostMapping("/admin/users/{id}/password")
    public void resetPassword(
        HttpServletRequest p,
        @PathVariable long id,
        @Valid @RequestBody ResetPasswordInput input
    ) {
        service.resetPassword(actor(p), id, input);
    }

    @GetMapping("/admin/rooms")
    public Object rooms(HttpServletRequest p) {
        actor(p);
        return db.rooms();
    }

    @PostMapping("/admin/rooms")
    public void addRoom(
        HttpServletRequest p,
        @Valid @RequestBody RoomInput input
    ) {
        service.saveRoom(actor(p), null, input);
    }

    @PutMapping("/admin/rooms/{id}")
    public void room(
        HttpServletRequest p,
        @PathVariable long id,
        @Valid @RequestBody RoomInput input
    ) {
        service.saveRoom(actor(p), id, input);
    }

    @GetMapping("/admin/rooms/{id}/seats")
    public Object seatsAll(HttpServletRequest p, @PathVariable long id) {
        actor(p);
        return db.seats(id);
    }

    @PostMapping("/admin/seats")
    public void addSeat(
        HttpServletRequest p,
        @Valid @RequestBody SeatInput input
    ) {
        service.saveSeat(actor(p), null, input);
    }

    @PutMapping("/admin/seats/{id}")
    public void seat(
        HttpServletRequest p,
        @PathVariable long id,
        @Valid @RequestBody SeatInput input
    ) {
        service.saveSeat(actor(p), id, input);
    }

    @GetMapping("/admin/openings")
    public Object openings(HttpServletRequest p) {
        actor(p);
        return db.openings();
    }

    @PostMapping("/admin/openings")
    public void opening(
        HttpServletRequest p,
        @Valid @RequestBody OpeningInput input
    ) {
        service.saveOpening(actor(p), input);
    }

    @GetMapping("/admin/notices")
    public Object notices(HttpServletRequest p) {
        actor(p);
        return db.notices(true);
    }

    @PostMapping("/admin/notices")
    public void addNotice(
        HttpServletRequest p,
        @Valid @RequestBody NoticeInput input
    ) {
        service.saveNotice(actor(p), null, input);
    }

    @PutMapping("/admin/notices/{id}")
    public void notice(
        HttpServletRequest p,
        @PathVariable long id,
        @Valid @RequestBody NoticeInput input
    ) {
        service.saveNotice(actor(p), id, input);
    }

    @GetMapping("/admin/rules")
    public Object rules(HttpServletRequest p) {
        actor(p);
        return db.rules();
    }

    @PostMapping("/admin/rules")
    public void rule(
        HttpServletRequest p,
        @Valid @RequestBody RuleInput input
    ) {
        service.saveRule(actor(p), input);
    }

    @GetMapping("/admin/statistics")
    public Object stats(
        HttpServletRequest p,
        @RequestParam LocalDate start,
        @RequestParam LocalDate end
    ) {
        actor(p);
        return service.statistics(start, end);
    }

    @GetMapping("/admin/audits")
    public Object audits(
        HttpServletRequest p,
        @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page
    ) {
        actor(p);
        return Map.of(
            "items",
            db.audits((page - 1) * 12, 12),
            "total",
            db.countAudits(),
            "page",
            page,
            "size",
            12
        );
    }
}
