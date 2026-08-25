package ai.neargo.shop.promotion.entity;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 周期活动的规则：<b>每周几、每天的哪个时段</b>。
 *
 * <p>存成 JSON 而不是加四列：这几个字段只有 {@code RECURRING} 用得上，
 * 加成列会让另外两种排期的行里躺着四个恒为空的字段，而且加一种周期形式
 * （每月几号）就要再加一列。
 *
 * <p><b>但解析只有这一处</b>：JSON 字段散在各处解析，是「同一个规则两种理解」的开始。
 *
 * @param weekdays 周几生效（1=周一 … 7=周日）。空 = 每天
 * @param from     当天从几点（含）。空 = 从零点
 * @param to       到几点（不含）。空 = 到次日零点
 */
public record RecurringRule(Set<Integer> weekdays, LocalTime from, LocalTime to) {

    private static final RecurringRule ALWAYS = new RecurringRule(Set.of(), null, null);

    /**
     * 宽松解析：读不出来就当「每天全天」。
     *
     * <p><b>坏规则不该让活动静默失效</b> —— 商家看到「进行中」而一分不减，
     * 排查会先怀疑算价。当成全天生效的话，至少行为是可见的、可解释的。
     * 规则本身的合法性堵在保存那一步（{@code ActivityService.save}）。
     */
    public static RecurringRule parse(String json) {
        if (json == null || json.isBlank()) {
            return ALWAYS;
        }
        try {
            Set<Integer> days = Set.of();
            var m = java.util.regex.Pattern.compile("\"weekdays\"\\s*:\\s*\\[([^\\]]*)\\]")
                    .matcher(json);
            if (m.find() && !m.group(1).isBlank()) {
                days = java.util.Arrays.stream(m.group(1).split(","))
                        .map(String::trim).filter(x -> !x.isEmpty())
                        .map(Integer::parseInt).collect(Collectors.toSet());
            }
            return new RecurringRule(days, time(json, "from"), time(json, "to"));
        } catch (RuntimeException e) {
            return ALWAYS;
        }
    }

    private static LocalTime time(String json, String key) {
        var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"(\\d{1,2}):(\\d{2})\"")
                .matcher(json);
        return m.find()
                ? LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)))
                : null;
    }

    /**
     * 此刻在不在这个周期里。
     *
     * <p><b>按市场时区判，不按服务器时区</b>：「每周三 8 点到 20 点」说的是
     * 顾客那边的周三，而服务器可能在另一个时区 —— 差 8 小时就意味着
     * 周三早上八点的活动在真正的周三还没开始。
     */
    public boolean matches(long epochMillis, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochMilli(epochMillis).atZone(zone);
        if (!weekdays.isEmpty()) {
            DayOfWeek dow = t.getDayOfWeek();
            if (!weekdays.contains(dow.getValue())) {
                return false;
            }
        }
        LocalTime now = t.toLocalTime();
        if (from != null && now.isBefore(from)) {
            return false;
        }
        // to 是**不含**：写 20:00 表示 20:00 整就结束了，而不是还能再买一分钟
        return to == null || now.isBefore(to);
    }
}
