package cn.edu.library.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

import java.time.*;

public final class Models {
    private Models() {}

    @Data
    public static class User {
        public Long id;
        public String studentNo;
        @JsonIgnore public String passwordHash;
        public String name, className, role;
        public boolean enabled;
        public LocalDateTime blockedUntil, createdAt;
    }

    @Data
    public static class Room {
        public Long id;
        public String code, name, floor, description;
        public boolean enabled;
        public LocalTime openTime, closeTime;
        public Integer seatCount;
    }

    @Data
    public static class Seat {
        public Long id, roomId;
        public String seatNo;
        public int rowNo, colNo;
        public boolean hasSocket, nearWindow, enabled, available;
        public String roomName;
    }

    @Data
    public static class Opening {
        public Long id, roomId;
        public LocalDate specialDate;
        public boolean isClosed;
        public LocalTime openTime, closeTime;
        public String reason;
    }

    @Data
    public static class Rule {
        public Long id;
        public LocalDateTime effectiveAt;
        public int advanceDays,
                maxDurationMinutes,
                checkinEarlyMinutes,
                checkinGraceMinutes,
                leaveMinutes,
                leaveLimit,
                violationWindowDays,
                violationLimit,
                blockDays;
    }

    @Data
    public static class Reservation {
        public Long id, userId, seatId, ruleId;
        public String reservationNo, status, requestKey;
        public LocalDateTime startTime,
                endTime,
                checkinAt,
                leaveAt,
                leaveDeadline,
                finishedAt,
                createdAt;
        public int leaveCount;
        public String finishReason, seatNo, roomName, studentNo, userName;
    }

    @Data
    public static class Violation {
        public Long id, reservationId, userId, revokedBy;
        public String type, reason, revokeReason, studentNo, userName;
        public LocalDateTime occurredAt, revokedAt;
        public boolean isRevoked;
    }

    @Data
    public static class Notice {
        public Long id, createdBy;
        public String title, content;
        public boolean published;
        public LocalDateTime createdAt;
    }

    @Data
    public static class Event {
        public Long id, reservationId, actorId;
        public String fromStatus, toStatus, eventType, reason;
        public LocalDateTime occurredAt;
    }

    @Data
    public static class Audit {
        public Long id, operatorId, targetId;
        public String operation, targetType, reason, operatorName;
        public LocalDateTime createdAt;
    }
}
