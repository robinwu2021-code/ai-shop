package ai.neargo.shop.common;

import java.time.LocalTime;
import java.time.ZoneId;
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
        return openAt(hours, LocalTime.now(ZoneId.systemDefault()));
    }

    static Boolean openAt(String hours, LocalTime t) {
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
