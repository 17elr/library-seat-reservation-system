package cn.edu.library.web;

import jakarta.validation.constraints.*;
import java.time.*;

public final class Requests {

    private Requests() {}

    public record Booking(
        @NotNull Long seatId,
        @NotNull LocalDateTime startTime,
        @NotNull LocalDateTime endTime,
        @NotBlank @Size(max = 64) String requestKey
    ) {}

    public record Reason(@NotBlank @Size(max = 200) String reason) {}

    public record RoomInput(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String code,
        @NotBlank @Size(max = 60) String name,
        @NotBlank @Size(max = 40) String floor,
        @NotNull @Size(max = 500) String description,
        @NotNull LocalTime openTime,
        @NotNull LocalTime closeTime,
        boolean enabled,
        @NotBlank @Size(max = 200) String reason
    ) {}

    public record SeatInput(
        @NotNull Long roomId,
        @NotBlank @Size(max = 20) String seatNo,
        @Min(1) @Max(30) int rowNo,
        @Min(1) @Max(20) int colNo,
        boolean hasSocket,
        boolean nearWindow,
        boolean enabled,
        @NotBlank @Size(max = 200) String reason
    ) {}

    public record UserInput(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{3,32}") String studentNo,
        @NotBlank @Size(max = 50) String name,
        @NotNull @Size(max = 100) String className,
        @NotBlank @Size(min = 8, max = 64) String password
    ) {}

    public record PasswordInput(
        @NotBlank String oldPassword,
        @NotBlank @Size(min = 8, max = 64) String newPassword
    ) {}

    public record ResetPasswordInput(
        @NotBlank @Size(min = 8, max = 64) String newPassword,
        @NotBlank @Size(max = 200) String reason
    ) {}

    public record EnabledInput(
        boolean enabled,
        @NotBlank @Size(max = 200) String reason
    ) {}

    public record NoticeInput(
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Size(max = 5000) String content,
        boolean published
    ) {}

    public record OpeningInput(
        @NotNull Long roomId,
        @NotNull LocalDate specialDate,
        boolean isClosed,
        @NotNull LocalTime openTime,
        @NotNull LocalTime closeTime,
        @NotBlank @Size(max = 200) String reason
    ) {}

    public record RuleInput(
        @NotNull LocalDateTime effectiveAt,
        @Min(0) @Max(14) int advanceDays,
        @Min(30) @Max(480) int maxDurationMinutes,
        @Min(0) @Max(30) int checkinEarlyMinutes,
        @Min(1) @Max(29) int checkinGraceMinutes,
        @Min(1) @Max(120) int leaveMinutes,
        @Min(0) @Max(10) int leaveLimit,
        @Min(1) @Max(30) int violationWindowDays,
        @Min(1) @Max(20) int violationLimit,
        @Min(1) @Max(30) int blockDays
    ) {}
}
