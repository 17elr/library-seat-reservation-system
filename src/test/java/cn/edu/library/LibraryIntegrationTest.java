package cn.edu.library;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.edu.library.mapper.LibraryMapper;
import cn.edu.library.model.Models.*;
import cn.edu.library.service.*;
import cn.edu.library.web.Requests.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LibraryIntegrationTest {
    @Autowired LibraryService service;
    @Autowired LibraryMapper db;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    User student, student2, admin;
    long seatId, seat2Id;
    LocalDateTime start = LocalDateTime.of(2026, 10, 9, 10, 30), end = start.plusHours(2);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        volatile Instant instant = Instant.parse("2026-10-09T02:00:00Z");

        void at(String local) {
            instant = LocalDateTime.parse(local).atZone(getZone()).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Shanghai");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @BeforeEach
    void reset() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("library_seat_test");
        for (String table :
                List.of(
                        "reservation_event",
                        "usage_session",
                        "violation_record",
                        "reservation",
                        "audit_log",
                        "room_opening")) jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE sys_user SET enabled=1,blocked_until=NULL");
        jdbc.update("UPDATE seat SET enabled=1");
        jdbc.update("UPDATE reading_room SET enabled=1,open_time='08:00:00',close_time='22:00:00'");
        jdbc.update("DELETE FROM system_rule");
        Rule q = new Rule();
        q.effectiveAt = LocalDateTime.of(2020, 1, 1, 0, 0);
        q.advanceDays = 2;
        q.maxDurationMinutes = 240;
        q.checkinEarlyMinutes = 10;
        q.checkinGraceMinutes = 15;
        q.leaveMinutes = 30;
        q.leaveLimit = 2;
        q.violationWindowDays = 7;
        q.violationLimit = 3;
        q.blockDays = 3;
        db.insertRule(q);
        clock.at("2026-10-09T10:00:00");
        student = db.userByAccount("20260001");
        student2 = db.userByAccount("20260002");
        admin = db.userByAccount("admin");
        var seats = db.seats(db.rooms().getFirst().id);
        seatId = seats.get(0).id;
        seat2Id = seats.get(1).id;
    }

    Booking booking(long seat, LocalDateTime from, LocalDateTime to) {
        return new Booking(seat, from, to, UUID.randomUUID().toString());
    }

    Reservation book(User u, long seat) {
        return service.book(u, booking(seat, start, end));
    }

    @Test
    void fullLifecycleClosesUsageAndKeepsHistory() {
        Reservation r = book(student, seatId);
        clock.at("2026-10-09T10:20:00");
        service.action(student, r.id, "check-in");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT start_time FROM usage_session WHERE reservation_id=?",
                                LocalDateTime.class,
                                r.id))
                .isEqualTo(start);
        assertThatThrownBy(() -> service.action(student, r.id, "leave"))
                .isInstanceOf(BusinessException.class);
        clock.at("2026-10-09T10:40:00");
        assertThat(service.action(student, r.id, "leave").status).isEqualTo("TEMP_LEFT");
        clock.at("2026-10-09T10:50:00");
        assertThat(service.action(student, r.id, "return").status).isEqualTo("CHECKED_IN");
        clock.at("2026-10-09T11:00:00");
        assertThat(service.action(student, r.id, "check-out").status).isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM usage_session WHERE end_time IS NULL",
                                Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_session", Integer.class))
                .isEqualTo(2);
        assertThat(db.events(r.id)).hasSize(5);
        assertThat(db.countViolations(student.id)).isZero();
    }

    @Test
    void simultaneousStudentsGetOnlyOneSeat() throws Exception {
        var users = db.users("202600");
        var gate = new CountDownLatch(1);
        var jobs = new ArrayList<Future<Boolean>>();
        try (var pool = Executors.newFixedThreadPool(20)) {
            for (User u : users)
                jobs.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        book(u, seatId);
                                        return true;
                                    } catch (BusinessException e) {
                                        return false;
                                    }
                                }));
            gate.countDown();
            int successes = 0;
            for (var job : jobs) if (job.get(20, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(db.active()).hasSize(1);
        }
    }

    @Test
    void oneStudentCannotBookDifferentRoomsConcurrently() throws Exception {
        long other = db.seats(db.rooms().get(1).id).getFirst().id;
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> a =
                    () -> {
                        gate.await();
                        try {
                            book(student, seatId);
                            return true;
                        } catch (BusinessException e) {
                            return false;
                        }
                    };
            Callable<Boolean> b =
                    () -> {
                        gate.await();
                        try {
                            book(student, other);
                            return true;
                        } catch (BusinessException e) {
                            return false;
                        }
                    };
            var f1 = pool.submit(a);
            var f2 = pool.submit(b);
            gate.countDown();
            assertThat((f1.get() ? 1 : 0) + (f2.get() ? 1 : 0)).isEqualTo(1);
        }
    }

    @Test
    void requestKeyReplaysAndRejectsDifferentPayload() {
        Booking b = booking(seatId, start, end);
        Reservation r = service.book(student, b);
        assertThat(service.book(student, b).id).isEqualTo(r.id);
        assertThatThrownBy(
                        () ->
                                service.book(
                                        student, new Booking(seat2Id, start, end, b.requestKey())))
                .isInstanceOf(BusinessException.class);
        assertThat(db.active()).hasSize(1);
    }

    @Test
    void adjacentIntervalsAllowedButPartialOverlapRejected() {
        book(student, seatId);
        assertThat(service.book(student2, booking(seatId, end, end.plusHours(1))).status)
                .isEqualTo("RESERVED");
        User third = db.userByAccount("20260003");
        assertThatThrownBy(
                        () ->
                                service.book(
                                        third,
                                        booking(
                                                seatId,
                                                start.plusMinutes(30),
                                                end.plusMinutes(30))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void inputDatesAndGridBoundariesAreEnforced() {
        for (Booking b :
                List.of(
                        booking(seatId, start.plusMinutes(1), end),
                        booking(seatId, start, start.plusHours(5)),
                        booking(seatId, start.minusDays(1), end.minusDays(1)),
                        booking(seatId, start.plusDays(3), end.plusDays(3)),
                        booking(seatId, start, start.plusDays(1)))) {
            assertThatThrownBy(() -> service.book(student, b))
                    .isInstanceOf(BusinessException.class);
        }
        assertThat(db.active()).isEmpty();
    }

    @Test
    void signupDeadlineIsInclusiveAndLateSweepIsIdempotent() {
        Reservation first = book(student, seatId);
        Reservation second = book(student2, seat2Id);
        clock.at("2026-10-09T10:45:00");
        assertThat(service.action(student, first.id, "check-in").status).isEqualTo("CHECKED_IN");
        service.sweep();
        assertThat(db.reservation(second.id).status).isEqualTo("RESERVED");
        clock.at("2026-10-09T11:01:00");
        service.sweep();
        service.sweep();
        assertThat(db.reservation(second.id).status).isEqualTo("NO_SHOW");
        assertThat(db.reservation(second.id).finishedAt).isEqualTo(start.plusMinutes(15));
        assertThat(db.countViolations(student2.id)).isEqualTo(1);
        assertThat(
                        service.book(
                                        student2,
                                        booking(
                                                seat2Id,
                                                LocalDateTime.of(2026, 10, 9, 11, 30),
                                                end.plusHours(1)))
                                .status)
                .isEqualTo("RESERVED");
    }

    @Test
    void leaveDeadlineInclusiveAndReservationEndHasPriorityWhenEqual() {
        Reservation r = book(student, seatId);
        clock.at("2026-10-09T10:30:00");
        service.action(student, r.id, "check-in");
        service.action(student, r.id, "leave");
        clock.at("2026-10-09T11:00:00");
        assertThat(service.action(student, r.id, "return").status).isEqualTo("CHECKED_IN");
        clock.at("2026-10-09T12:00:00");
        service.action(student, r.id, "leave");
        clock.at("2026-10-09T12:31:00");
        service.sweep();
        assertThat(db.reservation(r.id).status).isEqualTo("COMPLETED");
        assertThat(db.countViolations(student.id)).isZero();
    }

    @Test
    void leaveTimeoutReleasesAtEffectiveDeadline() {
        Reservation r = book(student, seatId);
        clock.at("2026-10-09T10:30:00");
        service.action(student, r.id, "check-in");
        service.action(student, r.id, "leave");
        clock.at("2026-10-09T11:10:00");
        service.sweep();
        assertThat(db.reservation(r.id).status).isEqualTo("LEAVE_TIMEOUT");
        assertThat(db.reservation(r.id).finishedAt).isEqualTo(LocalDateTime.of(2026, 10, 9, 11, 0));
        assertThat(db.countViolations(student.id)).isEqualTo(1);
    }

    @Test
    void oldReservationKeepsOriginalRule() {
        Reservation r = book(student, seatId);
        clock.at("2026-10-09T10:01:00");
        RuleInput input = new RuleInput(service.now(), 2, 240, 10, 5, 30, 2, 7, 3, 3);
        service.saveRule(admin, input);
        clock.at("2026-10-09T10:40:00");
        service.sweep();
        assertThat(db.reservation(r.id).status).isEqualTo("RESERVED");
        assertThat(service.currentRule().checkinGraceMinutes).isEqualTo(5);
    }

    @Test
    void maintenanceClosesBookingsWithoutStudentViolation() {
        Reservation r = book(student, seatId);
        Seat s = db.seat(seatId);
        service.saveSeat(
                admin,
                s.id,
                new SeatInput(
                        s.roomId,
                        s.seatNo,
                        s.rowNo,
                        s.colNo,
                        s.hasSocket,
                        s.nearWindow,
                        false,
                        "维修"));
        assertThat(db.reservation(r.id).status).isEqualTo("ADMIN_CLOSED");
        assertThat(db.countViolations(student.id)).isZero();
        assertThatThrownBy(() -> book(student2, seatId)).isInstanceOf(BusinessException.class);
        service.saveOpening(
                admin,
                new OpeningInput(
                        s.roomId,
                        start.toLocalDate(),
                        true,
                        LocalTime.of(8, 0),
                        LocalTime.of(22, 0),
                        "闭馆维护"));
        assertThatThrownBy(() -> book(student2, seat2Id)).isInstanceOf(BusinessException.class);
    }

    @Test
    void revokingViolationRecalculatesBlock() {
        for (int day = 0; day < 3; day++) {
            String date = start.plusDays(day).toLocalDate().toString();
            clock.at(date + "T10:00:00");
            LocalDateTime from = start.plusDays(day);
            service.book(student, booking(seatId, from, end.plusDays(day)));
            clock.at(date + "T11:00:00");
            service.sweep();
        }
        assertThat(db.user(student.id).blockedUntil).isAfter(service.now());
        assertThatThrownBy(
                        () ->
                                service.book(
                                        student,
                                        booking(seatId, start.plusDays(3), end.plusDays(3))))
                .isInstanceOf(BusinessException.class);
        long violation = db.violations(student.id, 0, 12).getFirst().id;
        service.revoke(admin, violation, "核验后撤销");
        assertThat(db.user(student.id).blockedUntil).isNull();
        assertThat(db.violation(violation).isRevoked).isTrue();
    }

    @Test
    void failedWriteRollsBackReservationAndEvents() {
        assertThatThrownBy(
                        () ->
                                service.write(
                                        () -> {
                                            book(student, seatId);
                                            throw new IllegalStateException("simulate failure");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.active()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_event", Integer.class))
                .isZero();
    }

    @Test
    void detailOwnershipEnforced() {
        Reservation r = book(student, seatId);
        assertThatThrownBy(() -> service.detail(student2, r.id))
                .isInstanceOf(BusinessException.class);
        assertThat(service.detail(admin, r.id)).containsKey("events");
    }

    @Test
    @WithMockUser(username = "20260001", roles = "STUDENT")
    void securityCsrfAndSafeJson() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isForbidden());
        Booking b = booking(seatId, start, end);
        mvc.perform(
                        post("/api/reservations")
                                .contentType("application/json")
                                .content(json.writeValueAsString(b)))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/reservations")
                                .with(csrf())
                                .contentType("application/json")
                                .content(json.writeValueAsString(b)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
        mvc.perform(get("/api/me/violations")).andExpect(status().isOk());
    }

    @Test
    void unauthenticatedApiAndRealLoginWork() throws Exception {
        mvc.perform(get("/api/bootstrap")).andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/login")
                                .with(csrf())
                                .param("username", "20260001")
                                .param("password", "Student123!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void specialOpeningCanBeClosedAndReopenedWithNewTimes() {
        Room room = db.rooms().getFirst();
        LocalDate date = start.toLocalDate();
        service.saveOpening(
                admin,
                new OpeningInput(
                        room.id, date, true, LocalTime.of(8, 0), LocalTime.of(22, 0), "临时闭馆"));
        var rooms = (List<Map<String, Object>>) service.schedules(date).get("rooms");
        assertThat(rooms.getFirst()).containsEntry("closed", true).containsEntry("bookable", false);
        service.saveOpening(
                admin,
                new OpeningInput(
                        room.id, date, false, LocalTime.of(12, 0), LocalTime.of(20, 0), "恢复开放"));
        rooms = (List<Map<String, Object>>) service.schedules(date).get("rooms");
        assertThat(rooms.getFirst())
                .containsEntry("closed", false)
                .containsEntry("earliestStart", "12:00");
        assertThat(db.openings()).hasSize(1);
        clock.at("2026-10-09T19:30:00");
        rooms = (List<Map<String, Object>>) service.schedules(date).get("rooms");
        assertThat(rooms.getFirst())
                .containsEntry("bookable", true)
                .containsEntry("earliestStart", "19:30");
        clock.at("2026-10-09T19:30:01");
        rooms = (List<Map<String, Object>>) service.schedules(date).get("rooms");
        assertThat(rooms.getFirst()).containsEntry("bookable", false);
    }

    @Test
    void currentBookingAndDateFiltersStayWithinStudentScope() {
        Reservation own = book(student, seatId);
        book(student2, seat2Id);
        var ownBoot = service.bootstrap(student);
        assertThat(((Reservation) ownBoot.get("currentReservation")).id).isEqualTo(own.id);
        var current = service.reservations(student.id, "ACTIVE", "", start.toLocalDate(), 1);
        assertThat((List<?>) current.get("items")).hasSize(1);
        assertThat(
                        service.reservations(
                                        student.id,
                                        "ACTIVE",
                                        "",
                                        start.plusDays(1).toLocalDate(),
                                        1)
                                .get("total"))
                .isEqualTo(0);
        assertThatThrownBy(() -> service.schedules(start.toLocalDate().plusDays(3)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void adminPasswordResetInvalidatesOldLoginWithoutCancellingReservation() throws Exception {
        Reservation booking = book(student, seatId);
        String original = db.user(student.id).passwordHash;
        try {
            MockHttpSession oldSession =
                    (MockHttpSession)
                            mvc.perform(
                                            post("/login")
                                                    .with(csrf())
                                                    .param("username", student.studentNo)
                                                    .param("password", "Student123!"))
                                    .andExpect(status().is3xxRedirection())
                                    .andReturn()
                                    .getRequest()
                                    .getSession(false);
            service.resetPassword(
                    admin, student.id, new ResetPasswordInput("Updated123!", "学生申请重置"));
            mvc.perform(get("/api/bootstrap").session(oldSession))
                    .andExpect(status().isUnauthorized());
            assertThat(passwords.matches("Updated123!", db.user(student.id).passwordHash)).isTrue();
            assertThat(db.reservation(booking.id).status).isEqualTo("RESERVED");
            assertThat(db.audits(0, 12).getFirst().operation).isEqualTo("重置学生密码");
            mvc.perform(
                            post("/login")
                                    .with(csrf())
                                    .param("username", student.studentNo)
                                    .param("password", "Student123!"))
                    .andExpect(redirectedUrl("/login?error"));
            mvc.perform(
                            post("/login")
                                    .with(csrf())
                                    .param("username", student.studentNo)
                                    .param("password", "Updated123!"))
                    .andExpect(redirectedUrl("/"));
            assertThatThrownBy(
                            () ->
                                    service.resetPassword(
                                            student,
                                            student2.id,
                                            new ResetPasswordInput("Other123!", "无权限")))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(
                            () ->
                                    service.resetPassword(
                                            admin,
                                            admin.id,
                                            new ResetPasswordInput("Other123!", "不允许")))
                    .isInstanceOf(BusinessException.class);
        } finally {
            db.password(student.id, original);
        }
    }
}
