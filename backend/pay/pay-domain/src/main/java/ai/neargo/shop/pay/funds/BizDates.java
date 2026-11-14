package ai.neargo.shop.pay.funds;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

/** 业务日按市场时区切（凭证与分录的 {@code biz_date}）：CN 的 23:30 不能落到 UTC 的第二天 */
public final class BizDates {

    private static final Map<String, ZoneId> ZONES = Map.of("CN", ZoneId.of("Asia/Shanghai"),
            "TW", ZoneId.of("Asia/Taipei"), "AE", ZoneId.of("Asia/Dubai"));

    private BizDates() {
    }

    public static LocalDate of(String market, Instant at) {
        return at.atZone(zoneOf(market)).toLocalDate();
    }

    /** 这个市场的这一业务日从 UTC 几点开始（按日查库用：[startUtc(d), startUtc(d+1))） */
    public static LocalDateTime startUtc(String market, LocalDate bizDate) {
        return bizDate.atStartOfDay(zoneOf(market)).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static ZoneId zoneOf(String market) {
        return ZONES.getOrDefault(market, ZoneId.of("UTC"));
    }
}
