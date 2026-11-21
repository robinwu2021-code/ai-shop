package ai.neargo.shop.invoice.regime.tw;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 台湾统一发票的期别与民国日期。两个月一期，期别 = 民国年 3 位 + 双月 2 位（11、12 月 → {@code 11512}）。
 *
 * <p>时区固定 {@code Asia/Taipei}：期别是法定日历概念，跟门店设置的展示时区无关 —— 用 UTC 算，
 * 双月最后一天 16:00 之后开的票会被算进下一期，号段对不上。
 */
public final class TwPeriods {

    public static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private TwPeriods() {
    }

    public static String periodOf(Instant at) {
        var date = LocalDate.ofInstant(at, TAIPEI);
        int month = date.getMonthValue();
        return "%03d%02d".formatted(date.getYear() - 1911, month % 2 == 0 ? month : month + 1);
    }

    /** {@code 11512} → {@code 115年11-12月}。 */
    public static String label(String periodCode) {
        int even = Integer.parseInt(periodCode.substring(3));
        return Integer.parseInt(periodCode.substring(0, 3)) + "年%02d-%02d月".formatted(even - 1, even);
    }

    /** 民国日期 7 位，如 2026-11-21 → {@code 1151121}。 */
    public static String rocDate(Instant at) {
        var date = LocalDate.ofInstant(at, TAIPEI);
        return "%03d%02d%02d".formatted(date.getYear() - 1911, date.getMonthValue(), date.getDayOfMonth());
    }

    public static LocalDateTime local(Instant at) {
        return LocalDateTime.ofInstant(at, TAIPEI);
    }
}
