package cn.edu.library.service;

import cn.edu.library.model.Models.*;
import java.time.LocalDateTime;
import java.util.Set;

public final class EffectiveState {

    public static final Set<String> ACTIVE = Set.of(
        "RESERVED",
        "CHECKED_IN",
        "TEMP_LEFT"
    );

    private EffectiveState() {}

    public static String evaluateEffectiveState(
        Reservation r,
        Rule q,
        LocalDateTime now
    ) {
        if (!ACTIVE.contains(r.status)) return r.status;
        if (
            r.status.equals("RESERVED") &&
            now.isAfter(r.startTime.plusMinutes(q.checkinGraceMinutes))
        ) return "NO_SHOW";
        if (
            r.status.equals("TEMP_LEFT") &&
            r.leaveDeadline.isBefore(r.endTime) &&
            now.isAfter(r.leaveDeadline)
        ) return "LEAVE_TIMEOUT";
        return !now.isBefore(r.endTime) ? "COMPLETED" : r.status;
    }
}
