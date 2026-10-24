package cn.edu.library;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.edu.library.mapper.LibraryMapper;
import cn.edu.library.model.Models.*;
import cn.edu.library.service.*;
import cn.edu.library.web.Requests.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LibraryIntegrationTest {

    @org.springframework.test.context.DynamicPropertySource
    static void rejectNonTestDatabaseBeforeContextStarts(
        org.springframework.test.context.DynamicPropertyRegistry ignored
    ) {
        String url = System.getenv("TEST_DB_URL");
        if (
            url != null &&
            !url.matches("jdbc:mysql://[^/]+/library_seat_test(?:\\?.*)?")
        ) throw new IllegalStateException(
            "TEST_DB_URL must target library_seat_test; context startup refused before migration"
        );
    }

    @Autowired
    LibraryService service;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    LibraryMapper db;

    @Autowired
    MetadataCache metadata;

    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    PasswordEncoder passwords;

    User student, student2, admin;
    long seatId, seat2Id;
    LocalDateTime start = LocalDateTime.of(2026, 10, 9, 10, 30),
        end = start.plusHours(2);

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
        assertThat(
            jdbc.queryForObject("SELECT DATABASE()", String.class)
        ).isEqualTo("library_seat_test");
        for (String table : List.of(
            "reservation_event",
            "usage_session",
            "violation_record",
            "reservation",
            "audit_log",
            "room_opening"
        ))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE sys_user SET enabled=1,blocked_until=NULL");
        jdbc.update("UPDATE seat SET enabled=1");
        jdbc.update(
            "UPDATE reading_room SET enabled=1,open_time='08:00:00',close_time='22:00:00'"
        );
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
                r.id
            )
        ).isEqualTo(start);
        assertThatThrownBy(() ->
            service.action(student, r.id, "leave")
        ).isInstanceOf(BusinessException.class);
        clock.at("2026-10-09T10:40:00");
        assertThat(service.action(student, r.id, "leave").status).isEqualTo(
            "TEMP_LEFT"
        );
        clock.at("2026-10-09T10:50:00");
        assertThat(service.action(student, r.id, "return").status).isEqualTo(
            "CHECKED_IN"
        );
        clock.at("2026-10-09T11:00:00");
        assertThat(service.action(student, r.id, "check-out").status).isEqualTo(
            "COMPLETED"
        );
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_session WHERE end_time IS NULL",
                Integer.class
            )
        ).isZero();
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_session",
                Integer.class
            )
        ).isEqualTo(2);
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
                    pool.submit(() -> {
                        gate.await();
                        try {
                            book(u, seatId);
                            return true;
                        } catch (BusinessException e) {
                            return false;
                        }
                    })
                );
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
            Callable<Boolean> a = () -> {
                gate.await();
                try {
                    book(student, seatId);
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            };
            Callable<Boolean> b = () -> {
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
        assertThatThrownBy(() ->
            service.book(
                student,
                new Booking(seat2Id, start, end, b.requestKey())
            )
        ).isInstanceOf(BusinessException.class);
        assertThat(db.active()).hasSize(1);
    }

    @Test
    void adjacentIntervalsAllowedButPartialOverlapRejected() {
        book(student, seatId);
        assertThat(
            service
                .book(student2, booking(seatId, end, end.plusHours(1)))
                .status
        ).isEqualTo("RESERVED");
        User third = db.userByAccount("20260003");
        assertThatThrownBy(() ->
            service.book(
                third,
                booking(seatId, start.plusMinutes(30), end.plusMinutes(30))
            )
        ).isInstanceOf(BusinessException.class);
    }

    @Test
    void inputDatesAndGridBoundariesAreEnforced() {
        for (Booking b : List.of(
            booking(seatId, start.plusMinutes(1), end),
            booking(seatId, start, start.plusHours(5)),
            booking(seatId, start.minusDays(1), end.minusDays(1)),
            booking(seatId, start.plusDays(3), end.plusDays(3)),
            booking(seatId, start, start.plusDays(1))
        )) {
            assertThatThrownBy(() -> service.book(student, b)).isInstanceOf(
                BusinessException.class
            );
        }
        assertThat(db.active()).isEmpty();
    }

    @Test
    void signupDeadlineIsInclusiveAndLateSweepIsIdempotent() {
        Reservation first = book(student, seatId);
        Reservation second = book(student2, seat2Id);
        clock.at("2026-10-09T10:45:00");
        assertThat(
            service.action(student, first.id, "check-in").status
        ).isEqualTo("CHECKED_IN");
        service.sweep();
        assertThat(db.reservation(second.id).status).isEqualTo("RESERVED");
        clock.at("2026-10-09T11:01:00");
        service.sweep();
        service.sweep();
        assertThat(db.reservation(second.id).status).isEqualTo("NO_SHOW");
        assertThat(db.reservation(second.id).finishedAt).isEqualTo(
            start.plusMinutes(15)
        );
        assertThat(db.countViolations(student2.id)).isEqualTo(1);
        assertThat(
            service
                .book(
                    student2,
                    booking(
                        seat2Id,
                        LocalDateTime.of(2026, 10, 9, 11, 30),
                        end.plusHours(1)
                    )
                )
                .status
        ).isEqualTo("RESERVED");
    }

    @Test
    void leaveDeadlineInclusiveAndReservationEndHasPriorityWhenEqual() {
        Reservation r = book(student, seatId);
        clock.at("2026-10-09T10:30:00");
        service.action(student, r.id, "check-in");
        service.action(student, r.id, "leave");
        clock.at("2026-10-09T11:00:00");
        assertThat(service.action(student, r.id, "return").status).isEqualTo(
            "CHECKED_IN"
        );
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
        assertThat(db.reservation(r.id).finishedAt).isEqualTo(
            LocalDateTime.of(2026, 10, 9, 11, 0)
        );
        assertThat(db.countViolations(student.id)).isEqualTo(1);
    }

    @Test
    void oldReservationKeepsOriginalRule() {
        Reservation r = book(student, seatId);
        clock.at("2026-10-09T10:01:00");
        RuleInput input = new RuleInput(
            service.now(),
            2,
            240,
            10,
            5,
            30,
            2,
            7,
            3,
            3
        );
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
                "维修"
            )
        );
        assertThat(db.reservation(r.id).status).isEqualTo("ADMIN_CLOSED");
        assertThat(db.countViolations(student.id)).isZero();
        assertThatThrownBy(() -> book(student2, seatId)).isInstanceOf(
            BusinessException.class
        );
        service.saveOpening(
            admin,
            new OpeningInput(
                s.roomId,
                start.toLocalDate(),
                true,
                LocalTime.of(8, 0),
                LocalTime.of(22, 0),
                "闭馆维护"
            )
        );
        assertThatThrownBy(() -> book(student2, seat2Id)).isInstanceOf(
            BusinessException.class
        );
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
        assertThatThrownBy(() ->
            service.book(
                student,
                booking(seatId, start.plusDays(3), end.plusDays(3))
            )
        ).isInstanceOf(BusinessException.class);
        long violation = db.violations(student.id, 0, 12).getFirst().id;
        service.revoke(admin, violation, "核验后撤销");
        assertThat(db.user(student.id).blockedUntil).isNull();
        assertThat(db.violation(violation).isRevoked).isTrue();
    }

    @Test
    void failedWriteRollsBackReservationAndEvents() {
        assertThatThrownBy(() ->
            service.write(() -> {
                book(student, seatId);
                throw new IllegalStateException("simulate failure");
            })
        ).isInstanceOf(IllegalStateException.class);
        assertThat(db.active()).isEmpty();
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_event",
                Integer.class
            )
        ).isZero();
    }

    @Test
    void detailOwnershipEnforced() {
        Reservation r = book(student, seatId);
        assertThatThrownBy(() -> service.detail(student2, r.id)).isInstanceOf(
            BusinessException.class
        );
        assertThat(service.detail(admin, r.id)).containsKey("events");
    }

    @Test
    void specialOpeningCanBeClosedAndReopenedWithNewTimes() {
        Room room = db.rooms().getFirst();
        LocalDate date = start.toLocalDate();
        service.saveOpening(
            admin,
            new OpeningInput(
                room.id,
                date,
                true,
                LocalTime.of(8, 0),
                LocalTime.of(22, 0),
                "临时闭馆"
            )
        );
        var rooms = (List<Map<String, Object>>) service
            .schedules(date)
            .get("rooms");
        assertThat(rooms.getFirst())
            .containsEntry("closed", true)
            .containsEntry("bookable", false);
        service.saveOpening(
            admin,
            new OpeningInput(
                room.id,
                date,
                false,
                LocalTime.of(12, 0),
                LocalTime.of(20, 0),
                "恢复开放"
            )
        );
        rooms = (List<Map<String, Object>>) service
            .schedules(date)
            .get("rooms");
        assertThat(rooms.getFirst())
            .containsEntry("closed", false)
            .containsEntry("earliestStart", "12:00");
        assertThat(db.openings()).hasSize(1);
        clock.at("2026-10-09T19:30:00");
        rooms = (List<Map<String, Object>>) service
            .schedules(date)
            .get("rooms");
        assertThat(rooms.getFirst())
            .containsEntry("bookable", true)
            .containsEntry("earliestStart", "19:30");
        clock.at("2026-10-09T19:30:01");
        rooms = (List<Map<String, Object>>) service
            .schedules(date)
            .get("rooms");
        assertThat(rooms.getFirst()).containsEntry("bookable", false);
    }

    @Test
    void currentBookingAndDateFiltersStayWithinStudentScope() {
        Reservation own = book(student, seatId);
        book(student2, seat2Id);
        var ownBoot = service.bootstrap(student);
        assertThat(
            ((Reservation) ownBoot.get("currentReservation")).id
        ).isEqualTo(own.id);
        var current = service.reservations(
            student.id,
            "ACTIVE",
            "",
            start.toLocalDate(),
            1
        );
        assertThat((List<?>) current.get("items")).hasSize(1);
        assertThat(
            service
                .reservations(
                    student.id,
                    "ACTIVE",
                    "",
                    start.plusDays(1).toLocalDate(),
                    1
                )
                .get("total")
        ).isEqualTo(0);
        assertThatThrownBy(() ->
            service.schedules(start.toLocalDate().plusDays(3))
        ).isInstanceOf(BusinessException.class);
    }

    MockHttpSession anonymous() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("csrf", "test-token");
        return s;
    }

    MockHttpSession logged(User u) {
        MockHttpSession s = anonymous();
        s.setAttribute("userId", u.id);
        s.setAttribute("authVersion", db.user(u.id).authVersion);
        return s;
    }

    MockHttpSession login(String account, String password) throws Exception {
        return (MockHttpSession) mvc
            .perform(
                post("/api/auth/login")
                    .session(anonymous())
                    .header("X-CSRF-TOKEN", "test-token")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of("account", account, "password", password)
                        )
                    )
            )
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession();
    }

    @Test
    void securityCsrfAndSafeJson() throws Exception {
        var session = logged(student);
        mvc.perform(get("/api/admin/users").session(session)).andExpect(
            status().isForbidden()
        );
        Booking b = booking(seatId, start, end);
        mvc.perform(
            post("/api/reservations")
                .session(session)
                .contentType("application/json")
                .content(json.writeValueAsString(b))
        ).andExpect(status().isForbidden());
        mvc.perform(
            post("/api/reservations")
                .session(session)
                .header("X-CSRF-TOKEN", "test-token")
                .contentType("application/json")
                .content(json.writeValueAsString(b))
        ).andExpect(status().isOk());
        mvc.perform(get("/api/bootstrap").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
        mvc.perform(get("/api/me/violations").session(session)).andExpect(
            status().isOk()
        );
    }

    @Test
    void unauthenticatedApiAndRealLoginWork() throws Exception {
        mvc.perform(get("/api/bootstrap")).andExpect(status().isUnauthorized());
        mvc.perform(
            get("/api/auth/me").header("userId", student.id.toString())
        ).andExpect(status().isUnauthorized());
        var s = login(student.studentNo, "Student123!");
        mvc.perform(get("/api/auth/me").session(s)).andExpect(
            jsonPath("$.studentNo").value(student.studentNo)
        );
    }

    @Test
    void adminPasswordResetInvalidatesOldLoginWithoutCancellingReservation()
        throws Exception {
        Reservation b = book(student, seatId);
        String original = db.user(student.id).passwordHash;
        try {
            var s = login(student.studentNo, "Student123!");
            service.resetPassword(
                admin,
                student.id,
                new ResetPasswordInput("Updated123!", "学生申请重置")
            );
            mvc.perform(get("/api/bootstrap").session(s)).andExpect(
                status().isUnauthorized()
            );
            assertThat(
                passwords.matches(
                    "Updated123!",
                    db.user(student.id).passwordHash
                )
            ).isTrue();
            assertThat(db.reservation(b.id).status).isEqualTo("RESERVED");
            assertThat(db.audits(0, 12).getFirst().operation).isEqualTo(
                "重置学生密码"
            );
            mvc.perform(
                post("/api/auth/login")
                    .session(anonymous())
                    .header("X-CSRF-TOKEN", "test-token")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "account",
                                student.studentNo,
                                "password",
                                "Student123!"
                            )
                        )
                    )
            ).andExpect(status().isUnauthorized());
            login(student.studentNo, "Updated123!");
            assertThatThrownBy(() ->
                service.resetPassword(
                    student,
                    student2.id,
                    new ResetPasswordInput("Other123!", "无权限")
                )
            ).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() ->
                service.resetPassword(
                    admin,
                    admin.id,
                    new ResetPasswordInput("Other123!", "不允许")
                )
            ).isInstanceOf(BusinessException.class);
        } finally {
            db.password(student.id, original);
        }
    }

    @Test
    void delayedGetComputesExpiryWithoutAnyMapperWrites() throws Exception {
        Reservation r = book(student, seatId);
        assertThat(
            service.detail(student, r.id).get("serverTime")
        ).isInstanceOf(java.time.OffsetDateTime.class);
        clock.at("2026-10-09T10:45:01");
        org.mockito.Mockito.clearInvocations(db);
        var detail = (Reservation) service
            .detail(student, r.id)
            .get("reservation");
        assertThat(detail.status).isEqualTo("NO_SHOW");
        assertThat(detail.persistedStatus).isEqualTo("RESERVED");
        assertThat(detail.stateComputed).isTrue();
        service.bootstrap(student);
        service.seats(db.seat(seatId).roomId, start, end);
        assertThat(
            service
                .reservations(student.id, "NO_SHOW", "", null, 1)
                .get("total")
        ).isEqualTo(1);
        assertThat(
            service.reservations(student.id, "ACTIVE", "", null, 1).get("total")
        ).isEqualTo(0);
        service.violations(student.id, 1);
        service.statistics(start.toLocalDate(), start.toLocalDate());
        var calls = org.mockito.Mockito.mockingDetails(db).getInvocations();
        assertThat(calls).noneMatch(
            i ->
                i.getMethod().getName().equals("lockWrites") ||
                i
                    .getMethod()
                    .getName()
                    .matches("insert.*|update.*|endUsage|startUsage|blockUser")
        );
        assertThat(db.reservation(r.id).status).isEqualTo("RESERVED");
        assertThat(db.events(r.id)).hasSize(1);
    }

    @Test
    void bookRepairsRelatedExpiredRecordBeforeCheckingActiveReservation() {
        var old = book(student, seatId);
        clock.at("2026-10-09T10:46:00");
        var next = service.book(
            student,
            booking(seat2Id, start.plusHours(1), end)
        );
        assertThat(next.status).isEqualTo("RESERVED");
        assertThat(db.reservation(old.id).status).isEqualTo("NO_SHOW");
        assertThat(db.effectiveViolations(student.id)).hasSize(1);
    }

    @Test
    void relatedExpiryCannotBypassThirdViolationBlock() {
        for (int i = 0; i < 2; i++) {
            var r = book(student, seatId);
            clock.at("2026-10-09T10:46:00");
            service.sweep();
            clock.at("2026-10-09T10:00:00");
        }
        book(student, seatId);
        clock.at("2026-10-09T10:46:00");
        assertThatThrownBy(() ->
            service.book(student, booking(seat2Id, start.plusHours(1), end))
        )
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("限制预约");
        service.sweep();
        assertThat(db.effectiveViolations(student.id)).hasSize(3);
        assertThat(db.user(student.id).blockedUntil).isAfter(service.now());
    }

    @Test
    void sweepProcessesMoreThanOneBatchWithOutOfOrderDeadlines() {
        long rule = service.currentRule().id;
        for (int i = 0; i < 405; i++) {
            LocalDateTime begin = start.minusMinutes(i % 2 == 0 ? 120 : 60);
            jdbc.update(
                "INSERT INTO reservation(reservation_no,user_id,seat_id,start_time,end_time,status,rule_id,request_key,created_at,checkin_deadline) VALUES(?,?,?,?,?,'RESERVED',?,?,?,?)",
                "BATCH" + i,
                student.id,
                seatId,
                begin,
                begin.plusMinutes(30),
                rule,
                "batch" + i,
                begin.minusMinutes(30),
                begin.plusMinutes(15)
            );
        }
        try {
            java.nio.file.Files.createDirectories(
                java.nio.file.Path.of(".local")
            );
            java.nio.file.Files.writeString(
                java.nio.file.Path.of(".local/expiry-explain.json"),
                jdbc.queryForObject(
                    "EXPLAIN FORMAT=JSON SELECT id FROM reservation FORCE INDEX(idx_expiry_checkin) WHERE status='RESERVED' AND checkin_deadline < '2026-10-09 10:00:00' AND id>0 ORDER BY id LIMIT 200",
                    String.class
                )
            );
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        org.mockito.Mockito.clearInvocations(db);
        service.sweep();
        assertThat(db.active()).isEmpty();
        var batches = org.mockito.Mockito.mockingDetails(db)
            .getInvocations()
            .stream()
            .filter(i -> i.getMethod().getName().equals("dueCandidates"))
            .toList();
        assertThat(batches).hasSize(4);
        assertThat(batches).allMatch(i -> (Integer) i.getArgument(2) == 200);
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM violation_record",
                Integer.class
            )
        ).isEqualTo(405);
        service.sweep();
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM violation_record",
                Integer.class
            )
        ).isEqualTo(405);
    }

    @Test
    void simultaneousExpiryAndCheckinAreIdempotent() throws Exception {
        var r = book(student, seatId);
        clock.at("2026-10-09T10:45:01");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> service.sweep());
            var b = pool.submit(() ->
                service.action(student, r.id, "check-in")
            );
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(db.reservation(r.id).status).isEqualTo("NO_SHOW");
        assertThat(db.effectiveViolations(student.id)).hasSize(1);
        assertThat(db.events(r.id)).hasSize(2);
    }

    @Test
    void csrfLoginRotatesSessionAndRejectsSpoofedIdentityAndOversizedPages()
        throws Exception {
        var anon = anonymous();
        String id = anon.getId();
        var result = mvc
            .perform(
                post("/api/auth/login")
                    .session(anon)
                    .header("X-CSRF-TOKEN", "test-token")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "account",
                                student.studentNo,
                                "password",
                                "Student123!"
                            )
                        )
                    )
            )
            .andExpect(status().isOk())
            .andReturn();
        var session = (MockHttpSession) result.getRequest().getSession();
        assertThat(session.getId()).isNotEqualTo(id);
        assertThat(session.getAttribute("csrf")).isNotEqualTo("test-token");
        mvc.perform(
            get("/api/auth/me").header("X-User-Id", admin.id)
        ).andExpect(status().isUnauthorized());
        mvc.perform(
            get("/api/auth/me")
                .session(session)
                .param("userId", admin.id.toString())
        ).andExpect(jsonPath("$.id").value(student.id));
        mvc.perform(
            get("/api/me/reservations").session(session).param("size", "101")
        ).andExpect(status().isBadRequest());
        mvc.perform(get("/api/rooms/1/seats").session(session)).andExpect(
            status().isBadRequest()
        );
        mvc.perform(get("/api/does-not-exist").session(session)).andExpect(
            status().isNotFound()
        );
        mvc.perform(get("/images/does-not-exist.png")).andExpect(
            status().isNotFound()
        );
    }

    @Test
    void passwordAndDisableInvalidateExistingSessions() throws Exception {
        var old = logged(student);
        String hash = student.passwordHash;
        try {
            service.changePassword(
                student,
                new PasswordInput("Student123!", "Changed123!")
            );
            mvc.perform(get("/api/bootstrap").session(old)).andExpect(
                status().isUnauthorized()
            );
            assertThat(
                passwords.matches(
                    "Changed123!",
                    db.user(student.id).passwordHash
                )
            ).isTrue();
            var next = logged(db.user(student.id));
            service.enableUser(
                admin,
                student.id,
                new EnabledInput(false, "验证停用失效")
            );
            mvc.perform(get("/api/bootstrap").session(next)).andExpect(
                status().isUnauthorized()
            );
        } finally {
            jdbc.update(
                "UPDATE sys_user SET password_hash=?,enabled=1 WHERE id=?",
                hash,
                student.id
            );
        }
    }

    @Test
    void realRedisCachesMetadataAndInvalidatesOnlyAfterCommit() {
        org.springframework.test.util.ReflectionTestUtils.setField(
            metadata,
            "enabled",
            true
        );
        String key = MetadataCache.PREFIX + "rooms";
        try {
            redis.delete(key);
            org.mockito.Mockito.clearInvocations(db);
            metadata.rooms();
            metadata.rooms();
            org.mockito.Mockito.verify(
                db,
                org.mockito.Mockito.times(1)
            ).rooms();
            assertThat(redis.getExpire(key)).isBetween(119L, 150L);
            assertThatThrownBy(() ->
                service.write(() -> {
                    metadata.invalidateAfterCommit("rooms");
                    throw new IllegalStateException("rollback");
                })
            ).isInstanceOf(IllegalStateException.class);
            assertThat(redis.hasKey(key)).isTrue();
            service.write(() -> {
                metadata.invalidateAfterCommit("rooms");
                return null;
            });
            assertThat(redis.hasKey(key)).isFalse();
        } finally {
            redis.delete(key);
            org.springframework.test.util.ReflectionTestUtils.setField(
                metadata,
                "enabled",
                false
            );
        }
    }

    @Test
    void staleEnabledSeatCacheCannotAuthorizeBook() {
        org.springframework.test.util.ReflectionTestUtils.setField(
            metadata,
            "enabled",
            true
        );
        String key = MetadataCache.PREFIX + "seats:" + db.seat(seatId).roomId;
        try {
            metadata.seats(db.seat(seatId).roomId);
            jdbc.update("UPDATE seat SET enabled=0 WHERE id=?", seatId);
            assertThat(
                metadata
                    .seats(db.seat(seatId).roomId)
                    .stream()
                    .filter(s -> s.id == seatId)
                    .findFirst()
                    .orElseThrow()
                    .enabled
            ).isTrue();
            assertThatThrownBy(() -> book(student, seatId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("停用");
        } finally {
            redis.delete(key);
            org.springframework.test.util.ReflectionTestUtils.setField(
                metadata,
                "enabled",
                false
            );
        }
    }

    @Test
    void realDisconnectedRedisFallsBackAndBookingStillWorks() {
        var config =
            new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                "127.0.0.1",
                1
            );
        var client =
            org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(150))
                .build();
        var factory =
            new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                config,
                client
            );
        factory.afterPropertiesSet();
        factory.start();
        var broken =
            new org.springframework.data.redis.core.StringRedisTemplate(
                factory
            );
        var fallback = new MetadataCache(broken, json, db);
        org.springframework.test.util.ReflectionTestUtils.setField(
            fallback,
            "enabled",
            true
        );
        org.springframework.test.util.ReflectionTestUtils.setField(
            service,
            "metadata",
            fallback
        );
        try {
            assertThat(service.bootstrap(student).get("rooms")).isNotNull();
            assertThat(
                service.seats(db.seat(seatId).roomId, start, end).get("seats")
            ).isNotNull();
            assertThat(book(student, seatId).id).isNotNull();
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(
                service,
                "metadata",
                metadata
            );
            factory.destroy();
        }
    }

    @Test
    void cacheSelectsFutureRuleOnlyWhenItBecomesEffective() {
        org.springframework.test.util.ReflectionTestUtils.setField(
            metadata,
            "enabled",
            true
        );
        String key = MetadataCache.PREFIX + "rules";
        try {
            redis.delete(key);
            Rule before = metadata.ruleDisplay(service.now());
            var input = new RuleInput(
                service.now().plusMinutes(1),
                2,
                180,
                10,
                15,
                30,
                2,
                7,
                3,
                3
            );
            service.saveRule(admin, input);
            assertThat(metadata.ruleDisplay(service.now()).id).isEqualTo(
                before.id
            );
            clock.at("2026-10-09T10:01:00");
            assertThat(
                metadata.ruleDisplay(service.now()).maxDurationMinutes
            ).isEqualTo(180);
            assertThat(
                service.book(student, booking(seatId, start, end)).ruleId
            ).isNotEqualTo(before.id);
        } finally {
            redis.delete(key);
            org.springframework.test.util.ReflectionTestUtils.setField(
                metadata,
                "enabled",
                false
            );
        }
    }

    @Test
    void eventInsertFailureRollsBackRealBooking() {
        org.mockito.Mockito.doThrow(
            new IllegalStateException("event insert fault")
        )
            .when(db)
            .insertEvent(org.mockito.ArgumentMatchers.any());
        try {
            assertThatThrownBy(() -> book(student, seatId)).isInstanceOf(
                IllegalStateException.class
            );
            assertThat(db.active()).isEmpty();
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM reservation_event",
                    Integer.class
                )
            ).isZero();
        } finally {
            org.mockito.Mockito.reset(db);
        }
    }

    @Test
    void encodedOrMatrixAdminPathsCannotBypassAuthorization() throws Exception {
        for (String path : List.of(
            "/api/%61dmin/users",
            "/api/admin;x=1/users"
        ))
            mvc.perform(
                get(java.net.URI.create(path)).session(logged(student))
            ).andExpect(status().isForbidden());
    }

    @Test
    void maintenanceDoesNotReclassifyAlreadyExpiredReservations() {
        Room room = db.rooms().getFirst();
        var seats = db.seats(room.id);
        List<Reservation> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) records.add(
            book(
                db.userByAccount(String.format("202600%02d", i + 1)),
                seats.get(i).id
            )
        );
        clock.at("2026-10-09T10:46:00");
        Seat seat = seats.getFirst();
        service.saveSeat(
            admin,
            seat.id,
            new SeatInput(
                room.id,
                seat.seatNo,
                seat.rowNo,
                seat.colNo,
                seat.hasSocket,
                seat.nearWindow,
                false,
                "维护停用"
            )
        );
        service.enableUser(
            admin,
            records.get(1).userId,
            new EnabledInput(false, "维护停用")
        );
        assertThatThrownBy(() ->
            service.close(admin, records.get(2).id, "手工关闭")
        )
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已结束");
        service.saveOpening(
            admin,
            new OpeningInput(
                room.id,
                start.toLocalDate(),
                true,
                LocalTime.of(8, 0),
                LocalTime.of(22, 0),
                "闭馆维护"
            )
        );
        service.saveRoom(
            admin,
            room.id,
            new RoomInput(
                room.code,
                room.name,
                room.floor,
                room.description,
                room.openTime,
                room.closeTime,
                false,
                "场地维护"
            )
        );
        assertThat(records).allMatch(r ->
            db.reservation(r.id).status.equals("NO_SHOW")
        );
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM violation_record",
                Integer.class
            )
        ).isEqualTo(5);
    }
}
