package cn.edu.library.service;

import cn.edu.library.mapper.LibraryMapper;
import cn.edu.library.model.Models.*;
import cn.edu.library.web.Requests.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LibraryService {

    private final LibraryMapper db;
    private final Clock clock;
    private final PasswordEncoder passwords;
    private final TransactionTemplate tx;
    private final MetadataCache metadata;
    private static final Set<String> ACTIVE = Set.of(
        "RESERVED",
        "CHECKED_IN",
        "TEMP_LEFT"
    );

    public LibraryService(
        LibraryMapper db,
        Clock clock,
        PasswordEncoder passwords,
        PlatformTransactionManager manager,
        MetadataCache metadata
    ) {
        this.db = db;
        this.clock = clock;
        this.passwords = passwords;
        this.tx = new TransactionTemplate(manager);
        this.metadata = metadata;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    public <T> T write(Supplier<T> operation) {
        // shortcut: one DB guard serializes campus writes; split locks only after measured
        // contention.
        return tx.execute(status -> {
            db.lockWrites();
            return operation.get();
        });
    }

    public User user(String account) {
        User u = db.userByAccount(account);
        if (u == null || !u.enabled) throw new BusinessException(
            HttpStatus.UNAUTHORIZED,
            "账号已停用，请重新登录或联系管理员"
        );
        return u;
    }

    public Rule currentRule() {
        Rule rule = db.currentRule(now());
        if (rule == null) throw new BusinessException("系统预约规则尚未初始化");
        return rule;
    }

    private <T> T required(T value) {
        if (value == null) throw new BusinessException(
            HttpStatus.NOT_FOUND,
            "记录不存在"
        );
        return value;
    }

    private void check(boolean valid, String message) {
        if (!valid) throw new BusinessException(message);
    }

    private void owner(Reservation r, User actor) {
        if (!r.userId.equals(actor.id)) throw new BusinessException(
            HttpStatus.FORBIDDEN,
            "只能操作本人的预约"
        );
    }

    private boolean overlaps(
        LocalDateTime a,
        LocalDateTime b,
        LocalDateTime c,
        LocalDateTime d
    ) {
        return a.isBefore(d) && c.isBefore(b);
    }

    public void sweep() {
        long after = 0;
        while (true) {
            var ids = db.dueCandidates(now(), after, 200);
            if (ids.isEmpty()) return;
            write(() -> {
                for (long id : ids) {
                    Reservation r = db.reservation(id);
                    if (r != null) expire(r, now());
                }
                return null;
            });
            after = ids.getLast();
        }
    }

    private void expireRelated(Long userId, Long seatId, LocalDateTime time) {
        for (Reservation r : db.relatedActive(userId, seatId)) expire(r, time);
    }

    private Reservation display(Reservation r) {
        r.persistedStatus = r.status;
        r.status = EffectiveState.evaluateEffectiveState(
            r,
            required(db.rule(r.ruleId)),
            now()
        );
        r.stateComputed = !r.status.equals(r.persistedStatus);
        return r;
    }

    public <T> T writeAs(User actor, Supplier<T> operation) {
        return write(() -> {
            User fresh = required(db.user(actor.id));
            if (
                !fresh.enabled || fresh.authVersion != actor.authVersion
            ) throw new BusinessException(
                HttpStatus.UNAUTHORIZED,
                "登录凭据已更新，请重新登录"
            );
            if (!fresh.role.equals(actor.role)) throw new BusinessException(
                HttpStatus.FORBIDDEN,
                "权限已变化"
            );
            return operation.get();
        });
    }

    private void expire(Reservation r, LocalDateTime time) {
        Rule q = required(db.rule(r.ruleId));
        String effective = EffectiveState.evaluateEffectiveState(r, q, time);
        if (effective.equals(r.status)) return;
        LocalDateTime end = switch (effective) {
            case "NO_SHOW" -> r.startTime.plusMinutes(q.checkinGraceMinutes);
            case "LEAVE_TIMEOUT" -> r.leaveDeadline;
            default -> r.endTime;
        };
        finish(
            r,
            effective,
            end,
            null,
            switch (effective) {
                case "NO_SHOW" -> "未在签到截止时间前签到";
                case "LEAVE_TIMEOUT" -> "暂离超过允许时长";
                default -> "预约到期，自动结束";
            }
        );
    }

    private void event(
        Reservation r,
        String from,
        String type,
        LocalDateTime time,
        Long actor,
        String reason
    ) {
        Event e = new Event();
        e.reservationId = r.id;
        e.fromStatus = from;
        e.toStatus = r.status;
        e.eventType = type;
        e.occurredAt = time;
        e.actorId = actor;
        e.reason = reason;
        db.insertEvent(e);
    }

    private void change(
        Reservation r,
        String next,
        String type,
        LocalDateTime time,
        Long actor,
        String reason
    ) {
        String from = r.status;
        r.status = next;
        db.updateReservation(r);
        event(r, from, type, time, actor, reason);
    }

    private void finish(
        Reservation r,
        String status,
        LocalDateTime time,
        Long actor,
        String reason
    ) {
        if (!ACTIVE.contains(r.status)) return;
        r.finishedAt = time;
        r.finishReason = reason;
        db.endUsage(r.id, time, reason);
        change(r, status, status, time, actor, reason);
        if (Set.of("NO_SHOW", "LEAVE_TIMEOUT").contains(status)) {
            Violation v = new Violation();
            v.reservationId = r.id;
            v.userId = r.userId;
            v.type = status;
            v.occurredAt = time;
            v.reason = reason;
            db.insertViolation(v);
            recalculateBlock(r.userId);
        }
    }

    private void recalculateBlock(long userId) {
        List<Violation> records = db.effectiveViolations(userId);
        LocalDateTime until = null;
        for (Violation v : records) {
            Rule q = required(
                db.rule(required(db.reservation(v.reservationId)).ruleId)
            );
            long count = records
                .stream()
                .filter(
                    x ->
                        !x.occurredAt.isAfter(v.occurredAt) &&
                        !x.occurredAt.isBefore(
                            v.occurredAt.minusDays(q.violationWindowDays)
                        )
                )
                .count();
            if (count >= q.violationLimit) {
                LocalDateTime candidate = v.occurredAt.plusDays(q.blockDays);
                if (until == null || candidate.isAfter(until)) until =
                    candidate;
            }
        }
        db.blockUser(userId, until);
    }

    private void validateTime(
        LocalDateTime start,
        LocalDateTime end,
        Rule q,
        boolean future
    ) {
        LocalDateTime time = now();
        check(start.toLocalDate().equals(end.toLocalDate()), "预约不能跨天");
        check(start.isBefore(end), "结束时间必须晚于开始时间");
        check(
            start.getMinute() % 30 == 0 &&
                end.getMinute() % 30 == 0 &&
                start.getSecond() == 0 &&
                end.getSecond() == 0 &&
                start.getNano() == 0 &&
                end.getNano() == 0,
            "起止时间须对齐整点或半点"
        );
        long minutes = Duration.between(start, end).toMinutes();
        check(
            minutes >= 30 && minutes <= q.maxDurationMinutes,
            "预约时长须为 30 分钟至 " + q.maxDurationMinutes + " 分钟"
        );
        check(
            !start.toLocalDate().isBefore(time.toLocalDate()) &&
                !start
                    .toLocalDate()
                    .isAfter(time.toLocalDate().plusDays(q.advanceDays)),
            "日期超出可预约范围"
        );
        if (future) check(!start.isBefore(time), "不能预约已经开始的时间段");
    }

    private boolean open(Room room, LocalDateTime start, LocalDateTime end) {
        if (!room.enabled) return false;
        Opening o = db.opening(room.id, start.toLocalDate());
        if (o != null && o.isClosed) return false;
        LocalTime begin = o == null ? room.openTime : o.openTime,
            finish = o == null ? room.closeTime : o.closeTime;
        return (
            !start.toLocalTime().isBefore(begin) &&
            !end.toLocalTime().isAfter(finish)
        );
    }

    public Map<String, Object> schedules(LocalDate date) {
        LocalDateTime time = now();
        Rule q = currentRule();
        check(
            !date.isBefore(time.toLocalDate()) &&
                !date.isAfter(time.toLocalDate().plusDays(q.advanceDays)),
            "日期超出可预约范围"
        );
        List<Map<String, Object>> rooms = new ArrayList<>();
        for (Room room : metadata.rooms()) {
            if (!room.enabled) continue;
            Opening o = db.opening(room.id, date);
            LocalTime begin = o == null ? room.openTime : o.openTime,
                end = o == null ? room.closeTime : o.closeTime;
            boolean closed = o != null && o.isClosed;
            LocalDateTime earliest = date.atTime(begin);
            while (earliest.isBefore(time)) earliest = earliest.plusMinutes(30);
            boolean bookable =
                !closed &&
                earliest.plusMinutes(30).compareTo(date.atTime(end)) <= 0;
            rooms.add(
                Map.of(
                    "roomId",
                    room.id,
                    "openTime",
                    begin,
                    "closeTime",
                    end,
                    "closed",
                    closed,
                    "bookable",
                    bookable,
                    "earliestStart",
                    bookable ? earliest.toLocalTime().toString() : "",
                    "reason",
                    o == null ? "" : o.reason
                )
            );
        }
        return Map.of(
            "date",
            date,
            "rooms",
            rooms,
            "now",
            time,
            "serverTime",
            time.atOffset(ZoneOffset.ofHours(8))
        );
    }

    public Map<String, Object> bootstrap(User actor) {
        Map<String, Object> result = new HashMap<>();
        result.put("user", required(db.user(actor.id)));
        result.put("rooms", metadata.rooms());
        result.put("rule", required(metadata.ruleDisplay(now())));
        result.put("notices", db.notices(false));
        result.put("now", now());
        result.put("serverTime", now().atOffset(ZoneOffset.ofHours(8)));
        if (actor.role.equals("STUDENT")) {
            Reservation current = db
                .liveUser(actor.id, now())
                .stream()
                .findFirst()
                .orElse(null);
            result.put("currentReservation", current);
            if (current != null) result.put(
                "currentRule",
                required(db.rule(current.ruleId))
            );
        }
        return result;
    }

    public Map<String, Object> seats(
        long roomId,
        LocalDateTime start,
        LocalDateTime end
    ) {
        Rule q = currentRule();
        validateTime(start, end, q, false);
        Room room = required(db.room(roomId));
        boolean opened = open(room, start, end);
        List<Reservation> occupied = db.roomOccupancy(
            roomId,
            start,
            end,
            now()
        );
        List<Seat> seats = metadata.seats(roomId);
        for (Seat seat : seats)
            seat.available =
                opened &&
                seat.enabled &&
                occupied.stream().noneMatch(r -> r.seatId.equals(seat.id));
        return Map.of("room", room, "seats", seats, "open", opened);
    }

    public Reservation book(User actor, Booking input) {
        return writeAs(actor, () -> {
            User u = required(db.user(actor.id));
            check(
                u.enabled && u.role.equals("STUDENT"),
                "只有启用的学生账号可以预约"
            );
            Reservation old = db.byRequest(u.id, input.requestKey());
            if (old != null) {
                check(
                    old.seatId.equals(input.seatId()) &&
                        old.startTime.equals(input.startTime()) &&
                        old.endTime.equals(input.endTime()),
                    "相同请求标识不能用于不同预约"
                );
                return old;
            }
            LocalDateTime time = now();
            expireRelated(actor.id, input.seatId(), time);
            Rule q = currentRule();
            validateTime(input.startTime(), input.endTime(), q, true);
            u = db.user(actor.id);
            check(
                u.blockedUntil == null || !u.blockedUntil.isAfter(time),
                "当前限制预约至 " + u.blockedUntil
            );
            Seat seat = required(db.seat(input.seatId()));
            Room room = required(db.room(seat.roomId));
            check(
                seat.enabled && open(room, input.startTime(), input.endTime()),
                "座位停用、闭馆或所选时段不在开放时间内"
            );
            List<Reservation> active = db.liveUser(actor.id, time);
            check(
                active.stream().noneMatch(r -> r.userId.equals(actor.id)),
                "您已有一条有效预约，请先结束或取消"
            );
            check(
                db
                    .liveSeat(seat.id, input.startTime(), input.endTime(), time)
                    .stream()
                    .noneMatch(
                        r ->
                            r.seatId.equals(seat.id) &&
                            overlaps(
                                input.startTime(),
                                input.endTime(),
                                r.startTime,
                                r.endTime
                            )
                    ),
                "该时段已被预约，请重新选择"
            );
            Reservation r = new Reservation();
            r.reservationNo =
                "LS" +
                UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .substring(0, 16)
                    .toUpperCase(Locale.ROOT);
            r.userId = u.id;
            r.seatId = seat.id;
            r.startTime = input.startTime();
            r.endTime = input.endTime();
            r.status = "RESERVED";
            r.ruleId = q.id;
            r.requestKey = input.requestKey();
            r.createdAt = time;
            r.checkinDeadline = r.startTime.plusMinutes(q.checkinGraceMinutes);
            db.insertReservation(r);
            event(r, null, "CREATE", time, u.id, "预约成功");
            return db.reservation(r.id);
        });
    }

    public Reservation action(User actor, long id, String action) {
        return writeAs(actor, () -> {
            Reservation r = required(db.reservation(id));
            owner(r, actor);
            check(required(db.user(actor.id)).enabled, "账号已停用");
            LocalDateTime time = now();
            Rule q = required(db.rule(r.ruleId));
            if (ACTIVE.contains(r.status)) expire(r, time);
            if (!ACTIVE.contains(r.status)) return r;
            switch (action) {
                case "cancel" -> {
                    check(
                        r.status.equals("RESERVED") &&
                            time.isBefore(r.startTime),
                        "只可在预约开始前取消尚未签到的预约"
                    );
                    finish(r, "CANCELLED", time, actor.id, "学生取消预约");
                }
                case "check-in" -> {
                    if (r.status.equals("CHECKED_IN")) return r;
                    check(r.status.equals("RESERVED"), "当前状态不能签到");
                    check(
                        !time.isBefore(
                            r.startTime.minusMinutes(q.checkinEarlyMinutes)
                        ) &&
                            !time.isAfter(
                                r.startTime.plusMinutes(q.checkinGraceMinutes)
                            ),
                        "不在允许的签到时间范围内"
                    );
                    r.checkinAt = time;
                    db.startUsage(
                        r.id,
                        time.isBefore(r.startTime) ? r.startTime : time
                    );
                    change(
                        r,
                        "CHECKED_IN",
                        "CHECK_IN",
                        time,
                        actor.id,
                        "学生签到"
                    );
                }
                case "leave" -> {
                    if (r.status.equals("TEMP_LEFT")) return r;
                    check(
                        r.status.equals("CHECKED_IN") &&
                            !time.isBefore(r.startTime),
                        "预约开始后且已签到才可暂离"
                    );
                    check(
                        r.leaveCount < q.leaveLimit,
                        "本次预约的暂离次数已用完"
                    );
                    r.leaveAt = time;
                    r.leaveCount++;
                    r.leaveDeadline = time.plusMinutes(q.leaveMinutes);
                    if (r.leaveDeadline.isAfter(r.endTime)) r.leaveDeadline =
                        r.endTime;
                    db.endUsage(r.id, time, "暂离");
                    change(
                        r,
                        "TEMP_LEFT",
                        "LEAVE",
                        time,
                        actor.id,
                        "暂离，座位保留至 " + r.leaveDeadline
                    );
                }
                case "return" -> {
                    if (r.status.equals("CHECKED_IN")) return r;
                    check(
                        r.status.equals("TEMP_LEFT") &&
                            !time.isAfter(r.leaveDeadline),
                        "当前状态不能返回"
                    );
                    db.startUsage(r.id, time);
                    change(
                        r,
                        "CHECKED_IN",
                        "RETURN",
                        time,
                        actor.id,
                        "学生返回"
                    );
                }
                case "check-out" -> {
                    check(
                        Set.of("CHECKED_IN", "TEMP_LEFT").contains(r.status),
                        "请先签到，或在开始前取消预约"
                    );
                    finish(r, "COMPLETED", time, actor.id, "学生主动签退");
                }
                default -> throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "未知操作"
                );
            }
            return db.reservation(id);
        });
    }

    public Map<String, Object> detail(User actor, long id) {
        Reservation r = display(required(db.reservation(id)));
        if (!actor.role.equals("ADMIN")) owner(r, actor);
        return Map.of(
            "reservation",
            r,
            "rule",
            required(db.rule(r.ruleId)),
            "events",
            db.events(id),
            "now",
            now(),
            "serverTime",
            now().atOffset(ZoneOffset.ofHours(8))
        );
    }

    public Map<String, Object> reservations(
        Long userId,
        String status,
        String search,
        LocalDate date,
        int page
    ) {
        return reservations(userId, status, search, date, page, 12);
    }

    public Map<String, Object> reservations(
        Long userId,
        String status,
        String search,
        LocalDate date,
        int page,
        int size
    ) {
        check(
            status == null ||
                status.isBlank() ||
                Set.of(
                    "ACTIVE",
                    "RESERVED",
                    "CHECKED_IN",
                    "TEMP_LEFT",
                    "COMPLETED",
                    "CANCELLED",
                    "NO_SHOW",
                    "LEAVE_TIMEOUT",
                    "ADMIN_CLOSED"
                ).contains(status),
            "预约状态不合法"
        );
        int p = Math.max(1, page);
        check(size >= 1 && size <= 100, "分页大小须为1至100");
        return Map.of(
            "items",
            db
                .reservations(
                    userId,
                    status,
                    search,
                    date,
                    (p - 1) * size,
                    size,
                    now()
                )
                .stream()
                .map(this::display)
                .toList(),
            "total",
            db.countReservations(userId, status, search, date, now()),
            "page",
            p,
            "size",
            size
        );
    }

    public Map<String, Object> violations(Long userId, int page) {
        int p = Math.max(1, page),
            size = 12;
        return Map.of(
            "items",
            db.violations(userId, (p - 1) * size, size),
            "total",
            db.countViolations(userId),
            "page",
            p,
            "size",
            size
        );
    }

    public void changePassword(User actor, PasswordInput input) {
        writeAs(actor, () -> {
            User u = required(db.user(actor.id));
            check(
                passwords.matches(input.oldPassword(), u.passwordHash),
                "原密码不正确"
            );
            check(
                !input.oldPassword().equals(input.newPassword()),
                "新密码不能与原密码相同"
            );
            db.password(u.id, passwords.encode(input.newPassword()));
            return null;
        });
    }

    public void resetPassword(User actor, long id, ResetPasswordInput input) {
        check(actor.role.equals("ADMIN"), "只有管理员可以重置密码");
        writeAs(actor, () -> {
            User target = required(db.user(id));
            check(target.role.equals("STUDENT"), "只能重置学生账号密码");
            db.password(id, passwords.encode(input.newPassword()));
            audit(actor, "重置学生密码", "user", id, input.reason());
            return null;
        });
    }

    private void audit(
        User actor,
        String op,
        String type,
        long id,
        String reason
    ) {
        Audit a = new Audit();
        a.operatorId = actor.id;
        a.operation = op;
        a.targetType = type;
        a.targetId = id;
        a.reason = reason;
        db.insertAudit(a);
    }

    public void close(User actor, long id, String reason) {
        boolean closed = writeAs(actor, () -> {
            Reservation r = required(db.reservation(id));
            expire(r, now());
            if (!ACTIVE.contains(r.status)) return false;
            finish(r, "ADMIN_CLOSED", now(), actor.id, reason);
            audit(actor, "关闭预约", "reservation", id, reason);
            return true;
        });
        check(closed, "预约已结束");
    }

    public void revoke(User actor, long id, String reason) {
        writeAs(actor, () -> {
            Violation v = required(db.violation(id));
            check(!v.isRevoked, "违约已撤销");
            v.revokedBy = actor.id;
            v.revokedAt = now();
            v.revokeReason = reason;
            db.revokeViolation(v);
            recalculateBlock(v.userId);
            audit(actor, "撤销违约", "violation", id, reason);
            return null;
        });
    }

    public void addUser(User actor, UserInput input) {
        writeAs(actor, () -> {
            check(db.userByAccount(input.studentNo()) == null, "学号已存在");
            User u = new User();
            u.studentNo = input.studentNo();
            u.name = input.name();
            u.className = input.className();
            u.passwordHash = passwords.encode(input.password());
            u.role = "STUDENT";
            u.enabled = true;
            db.insertUser(u);
            audit(actor, "创建学生", "user", u.id, "创建学生账号");
            return null;
        });
    }

    public void enableUser(User actor, long id, EnabledInput input) {
        writeAs(actor, () -> {
            User u = required(db.user(id));
            check(u.role.equals("STUDENT"), "本页面只管理学生账号");
            if (!input.enabled()) expireRelated(id, null, now());
            db.enableUser(id, input.enabled());
            if (!input.enabled()) for (Reservation r : db.relatedActive(
                id,
                null
            ))
                if (r.userId.equals(id)) finish(
                    r,
                    "ADMIN_CLOSED",
                    now(),
                    actor.id,
                    input.reason()
                );
            audit(
                actor,
                input.enabled() ? "启用学生" : "停用学生",
                "user",
                id,
                input.reason()
            );
            return null;
        });
    }

    private void openingTimes(LocalTime start, LocalTime end) {
        check(start.isBefore(end), "开放时间必须早于关闭时间，不能跨天");
        check(
            start.getSecond() == 0 &&
                end.getSecond() == 0 &&
                start.getMinute() % 30 == 0 &&
                end.getMinute() % 30 == 0,
            "开放时间须对齐整点或半点"
        );
    }

    public void saveRoom(User actor, Long id, RoomInput input) {
        openingTimes(input.openTime(), input.closeTime());
        writeAs(actor, () -> {
            if (id != null) for (Reservation related : db.roomActive(id))
                expire(related, now());
            Room r = id == null ? new Room() : required(db.room(id));
            r.code = input.code();
            r.name = input.name();
            r.floor = input.floor();
            r.description = input.description();
            r.openTime = input.openTime();
            r.closeTime = input.closeTime();
            r.enabled = input.enabled();
            if (id == null) db.insertRoom(r);
            else db.updateRoom(r);
            metadata.invalidateAfterCommit("rooms");
            for (Reservation a : db.roomActive(r.id))
                if (
                    required(db.seat(a.seatId)).roomId.equals(r.id) &&
                    !open(r, a.startTime, a.endTime)
                ) finish(a, "ADMIN_CLOSED", now(), actor.id, input.reason());
            audit(
                actor,
                id == null ? "新增阅览室" : "修改阅览室",
                "room",
                r.id,
                input.reason()
            );
            return null;
        });
    }

    public void saveSeat(User actor, Long id, SeatInput input) {
        writeAs(actor, () -> {
            required(db.room(input.roomId()));
            if (id != null) expireRelated(null, id, now());
            Seat s = id == null ? new Seat() : required(db.seat(id));
            check(
                id == null || s.roomId.equals(input.roomId()),
                "已有座位不能迁移到另一阅览室"
            );
            s.roomId = input.roomId();
            s.seatNo = input.seatNo();
            s.rowNo = input.rowNo();
            s.colNo = input.colNo();
            s.hasSocket = input.hasSocket();
            s.nearWindow = input.nearWindow();
            s.enabled = input.enabled();
            if (id == null) db.insertSeat(s);
            else db.updateSeat(s);
            metadata.invalidateAfterCommit("rooms", "seats:" + s.roomId);
            if (!s.enabled) for (Reservation r : db.relatedActive(null, s.id))
                if (r.seatId.equals(s.id)) finish(
                    r,
                    "ADMIN_CLOSED",
                    now(),
                    actor.id,
                    input.reason()
                );
            audit(
                actor,
                id == null ? "新增座位" : "修改座位",
                "seat",
                s.id,
                input.reason()
            );
            return null;
        });
    }

    public void saveOpening(User actor, OpeningInput input) {
        openingTimes(input.openTime(), input.closeTime());
        check(
            !input.specialDate().isBefore(now().toLocalDate()),
            "只能设置今天或未来的开放安排"
        );
        writeAs(actor, () -> {
            Room room = required(db.room(input.roomId()));
            Opening o = new Opening();
            o.roomId = room.id;
            o.specialDate = input.specialDate();
            o.isClosed = input.isClosed();
            o.openTime = input.openTime();
            o.closeTime = input.closeTime();
            o.reason = input.reason();
            for (Reservation related : db.roomActive(room.id))
                if (
                    related.startTime.toLocalDate().equals(o.specialDate)
                ) expire(related, now());
            db.saveOpening(o);
            for (Reservation r : db.roomActive(room.id))
                if (
                    required(db.seat(r.seatId)).roomId.equals(room.id) &&
                    r.startTime.toLocalDate().equals(o.specialDate) &&
                    !open(room, r.startTime, r.endTime)
                ) finish(r, "ADMIN_CLOSED", now(), actor.id, input.reason());
            audit(actor, "设置日期开放安排", "room", room.id, input.reason());
            return null;
        });
    }

    public void saveNotice(User actor, Long id, NoticeInput input) {
        writeAs(actor, () -> {
            Notice n = new Notice();
            n.id = id;
            n.title = input.title();
            n.content = input.content();
            n.published = input.published();
            n.createdBy = actor.id;
            if (id == null) db.insertNotice(n);
            else check(db.updateNotice(n) == 1, "公告不存在");
            audit(
                actor,
                id == null ? "新增公告" : "修改公告",
                "announcement",
                n.id,
                n.published ? "发布" : "撤下或草稿"
            );
            return null;
        });
    }

    public void saveRule(User actor, RuleInput input) {
        check(
            !input.effectiveAt().isBefore(now()),
            "规则生效时间须为当前或未来时间"
        );
        check(
            input.maxDurationMinutes() % 30 == 0,
            "最大时长须为 30 分钟的整数倍"
        );
        writeAs(actor, () -> {
            Rule q = new Rule();
            q.effectiveAt = input.effectiveAt();
            q.advanceDays = input.advanceDays();
            q.maxDurationMinutes = input.maxDurationMinutes();
            q.checkinEarlyMinutes = input.checkinEarlyMinutes();
            q.checkinGraceMinutes = input.checkinGraceMinutes();
            q.leaveMinutes = input.leaveMinutes();
            q.leaveLimit = input.leaveLimit();
            q.violationWindowDays = input.violationWindowDays();
            q.violationLimit = input.violationLimit();
            q.blockDays = input.blockDays();
            db.insertRule(q);
            metadata.invalidateAfterCommit("rules");
            audit(
                actor,
                "新增规则版本",
                "rule",
                q.id,
                "生效时间 " + q.effectiveAt + "，既有预约沿用原版本"
            );
            return null;
        });
    }

    public Map<String, Object> statistics(LocalDate start, LocalDate end) {
        check(
            !start.isAfter(end) && !end.isAfter(start.plusDays(366)),
            "统计范围须为 1 至 367 天"
        );
        LocalDateTime time = now();
        Map<String, Object> result = new HashMap<>(
            db.statistics(
                start.atStartOfDay(),
                end.plusDays(1).atStartOfDay(),
                time
            )
        );
        result.putAll(db.liveCounts(time));
        result.put(
            "rooms",
            db
                .rooms()
                .stream()
                .filter(r -> r.enabled)
                .count()
        );
        result.put(
            "seats",
            db
                .rooms()
                .stream()
                .filter(r -> r.enabled)
                .mapToInt(r -> r.seatCount)
                .sum()
        );
        return result;
    }
}
