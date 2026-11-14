package ai.neargo.shop.common;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 店主自填的营业时间文案 → 此刻开没开。
 *
 * <p>「我的店 / 附近」的卡片与门户的门头都要这一句，两处各写一遍迟早一个认「～」一个不认 ——
 * 同一家店在列表里写营业中、点进去写已打烊。
 */
public final class OpenHours {

    /** {@code 08:00-20:00}；也认全角破折号、波浪号与「至」 */
    private static final Pattern HOURS =
            Pattern.compile("^\\s*(\\d{1,2}):(\\d{2})\\s*[-–—~～至]\\s*(\\d{1,2}):(\\d{2})\\s*$");

    private OpenHours() {
    }

    /**
     * @return 认不出来返回 null —— 页面据此不画那个标签，不猜
     */
    public static Boolean openNow(String hours) {
        return openAtLegacy(hours, LocalTime.now(ZoneId.systemDefault()));
    }

    /** Strict given-clock entry for business decisions; old display behavior remains in openNow. */
    public static Boolean openAt(String hours, LocalTime time) {
        if (hours == null || time == null) return null;
        Matcher matcher = HOURS.matcher(hours);
        if (!matcher.matches() || Integer.parseInt(matcher.group(1)) > 23
                || Integer.parseInt(matcher.group(2)) > 59 || Integer.parseInt(matcher.group(3)) > 23
                || Integer.parseInt(matcher.group(4)) > 59) return null;
        return openAtLegacy(hours, time);
    }

    /** Calendar compilers reuse the original accepted syntax rather than inventing another hours parser. */
    public static List<LocalTime> boundaryTimes(String hours) {
        if (openAt(hours, LocalTime.MIDNIGHT) == null)
            throw new IllegalArgumentException("Unknown original opening hours");
        var matcher = HOURS.matcher(hours);
        if (!matcher.matches()) throw new IllegalArgumentException("Unknown original opening hours");
        return List.of(LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))),
                LocalTime.of(Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4))));
    }

    private static Boolean openAtLegacy(String hours, LocalTime t) {
        if (hours == null || hours.isBlank()) {
            return null;
        }
        Matcher m = HOURS.matcher(hours);
        if (!m.matches()) {
            return null;
        }
        int open = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
        int close = Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));
        int now = t.getHour() * 60 + t.getMinute();
        if (open == close) {
            return true;   // 00:00-00:00 这种写法是全天
        }
        return open < close ? now >= open && now < close
                // 跨夜：22:00-02:00
                : now >= open || now < close;
    }
}
