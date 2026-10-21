package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.ReplaceMenuScheduleReq;
import ai.neargo.shop.food.dto.WeekScheduleInput;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;

/** Canonical definitions are shared with typed clients; store operating policy stays in the core module. */
public final class MenuScheduleDefinitions {
    private MenuScheduleDefinitions() { }
    public static MenuScheduleVO normalize(ReplaceMenuScheduleReq req) {
        require(req != null && "SCHEDULE".equals(req.section()) && req.version() != null
                && req.version() >= 0 && req.version() < Long.MAX_VALUE);
        return normalize(new MenuScheduleVO(req.validFrom(), req.validTo(), req.weekSchedules(), req.exceptions()));
    }
    public static MenuScheduleVO normalize(MenuScheduleVO v) {
        require(v != null && v.weekSchedules() != null && v.weekSchedules().size() <= 100
                && v.exceptions() != null && v.exceptions().size() <= 366);
        var from = v.validFrom() == null ? null : date(v.validFrom());
        var to = v.validTo() == null ? null : date(v.validTo());
        require(from == null || to == null || !from.isAfter(to));
        var ranges = new ArrayList<int[]>();
        for (var rule : v.weekSchedules()) {
            require(rule != null && rule.weekday() != null && rule.weekday() >= 1 && rule.weekday() <= 7);
            int length = duration(rule.startTime(), rule.endTime(), rule.endDayOffset());
            int start = (rule.weekday() - 1) * 1440 + minute(rule.startTime());
            for (var old : ranges) {
                for (int shift : new int[] {-10080, 0, 10080}) {
                    require(!(start < old[1] + shift && start + length > old[0] + shift));
                }
            }
            ranges.add(new int[] {start, start + length});
        }
        var seen = new HashSet<LocalDate>();
        for (var rule : v.exceptions()) {
            require(rule != null);
            var date = date(rule.localDate());
            require(seen.add(date) && (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to)));
            if ("CLOSED".equals(rule.mode())) {
                require(rule.startTime() == null && rule.endTime() == null
                        && Integer.valueOf(0).equals(rule.endDayOffset()));
            } else {
                require("OPEN".equals(rule.mode()));
                duration(rule.startTime(), rule.endTime(), rule.endDayOffset());
            }
        }
        return new MenuScheduleVO(v.validFrom(), v.validTo(), v.weekSchedules().stream()
                .sorted(Comparator.comparing(WeekScheduleInput::weekday).thenComparing(WeekScheduleInput::startTime))
                .toList(), v.exceptions().stream().sorted(Comparator.comparing(r -> r.localDate())).toList());
    }
    public static LocalDate date(String text) {
        require(text != null && text.matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}"));
        try { return LocalDate.parse(text); }
        catch (DateTimeParseException invalid) { throw BizException.of(ErrorCode.BAD_REQUEST); }
    }
    private static int minute(String text) {
        require(text != null && text.matches("([01][0-9]|2[0-3]):[0-5][0-9]"));
        var time = LocalTime.parse(text);
        return time.getHour() * 60 + time.getMinute();
    }
    private static int duration(String start, String end, Integer offset) {
        require(offset != null && (offset == 0 || offset == 1));
        int length = minute(end) - minute(start) + offset * 1440;
        require(length > 0 && length <= 1440);
        return length;
    }
    private static void require(boolean ok) { if (!ok) throw BizException.of(ErrorCode.BAD_REQUEST); }
}
