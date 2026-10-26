package ai.neargo.shop.pay.funds;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** 业务日按市场时区切（凭证与分录的 {@code biz_date}）：CN 的 23:30 不能落到 UTC 的第二天 */
public final class BizDates {

    private static final Map<String, ZoneId> ZONES = Map.of("CN", ZoneId.of("Asia/Shanghai"),
            "TW", ZoneId.of("Asia/Taipei"), "AE", ZoneId.of("Asia/Dubai"));

    private BizDates() {
    }

    public static LocalDate of(String market, Instant at) {
        return at.atZone(ZONES.getOrDefault(market, ZoneId.of("UTC"))).toLocalDate();
    }
}
