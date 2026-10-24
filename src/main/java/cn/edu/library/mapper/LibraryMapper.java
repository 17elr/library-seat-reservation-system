package cn.edu.library.mapper;

import cn.edu.library.model.Models.*;
import java.time.*;
import java.util.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LibraryMapper {
    int lockWrites();

    User userByAccount(String account);

    User user(long id);

    List<User> users(String search);

    int insertUser(User user);

    int password(@Param("id") long id, @Param("hash") String hash);

    int enableUser(@Param("id") long id, @Param("enabled") boolean enabled);

    int blockUser(@Param("id") long id, @Param("until") LocalDateTime until);

    List<Room> rooms();

    Room room(long id);

    int insertRoom(Room room);

    int updateRoom(Room room);

    List<Seat> seats(long roomId);

    Seat seat(long id);

    int insertSeat(Seat seat);

    int updateSeat(Seat seat);

    Opening opening(
        @Param("roomId") long roomId,
        @Param("date") LocalDate date
    );

    List<Opening> openings();

    int saveOpening(Opening opening);

    Rule currentRule(LocalDateTime now);

    Rule rule(long id);

    List<Rule> rules();

    int insertRule(Rule rule);

    Reservation reservation(long id);

    Reservation byRequest(
        @Param("userId") long userId,
        @Param("key") String key
    );

    List<Reservation> active(); // Test/diagnostic only; production paths use bounded queries.
    List<Rule> allRules();
    List<Long> dueCandidates(
        @Param("now") LocalDateTime now,
        @Param("after") long after,
        @Param("limit") int limit
    );
    List<Reservation> relatedActive(
        @Param("userId") Long userId,
        @Param("seatId") Long seatId
    );
    List<Reservation> roomActive(long roomId);
    List<Reservation> liveUser(
        @Param("userId") long userId,
        @Param("now") LocalDateTime now
    );
    List<Reservation> liveSeat(
        @Param("seatId") long seatId,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end,
        @Param("now") LocalDateTime now
    );
    List<Reservation> roomOccupancy(
        @Param("roomId") long roomId,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end,
        @Param("now") LocalDateTime now
    );
    Map<String, Object> liveCounts(LocalDateTime now);

    List<Reservation> reservations(
        @Param("userId") Long userId,
        @Param("status") String status,
        @Param("search") String search,
        @Param("date") LocalDate date,
        @Param("offset") int offset,
        @Param("limit") int limit,
        @Param("now") LocalDateTime now
    );

    int countReservations(
        @Param("userId") Long userId,
        @Param("status") String status,
        @Param("search") String search,
        @Param("date") LocalDate date,
        @Param("now") LocalDateTime now
    );

    int insertReservation(Reservation reservation);

    int updateReservation(Reservation reservation);

    List<Event> events(long reservationId);

    int insertEvent(Event event);

    int startUsage(@Param("id") long id, @Param("start") LocalDateTime start);

    int endUsage(
        @Param("id") long id,
        @Param("end") LocalDateTime end,
        @Param("reason") String reason
    );

    List<Violation> violations(
        @Param("userId") Long userId,
        @Param("offset") int offset,
        @Param("limit") int limit
    );

    int countViolations(Long userId);

    Violation violation(long id);

    List<Violation> effectiveViolations(long userId);

    int insertViolation(Violation violation);

    int revokeViolation(Violation violation);

    List<Notice> notices(boolean all);

    int insertNotice(Notice notice);

    int updateNotice(Notice notice);

    int insertAudit(Audit audit);

    List<Audit> audits(@Param("offset") int offset, @Param("limit") int limit);

    int countAudits();

    Map<String, Object> statistics(
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end,
        @Param("now") LocalDateTime now
    );
}
