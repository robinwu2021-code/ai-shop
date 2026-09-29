package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.SettleStatsService;
import ai.neargo.shop.pay.SettleStatsService.Dim;
import ai.neargo.shop.payclient.OpsSettleStatsAppService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link OpsSettleStatsAppService} 实现。 */
@Service
public class OpsSettleStatsAppServiceImpl implements OpsSettleStatsAppService {

    private final SettleStatsService stats;
    private final MerchantQueryPort merchantPort;

    public OpsSettleStatsAppServiceImpl(SettleStatsService stats, MerchantQueryPort merchantPort) {
        this.stats = stats;
        this.merchantPort = merchantPort;
    }

    @Override
    public List<StatRowVO> stats(String dim, String from, String to, String businessMode) {
        Dim d = parseDim(dim);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate f = parseDate(from);
        LocalDate t = parseDate(to);
        if (t.isBefore(f)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        long fromMillis = f.atStartOfDay(zone).toInstant().toEpochMilli();
        // **取到当日最后一毫秒**：用次日零点做上界的话，恰好落在零点那一笔会被算进后一天
        long toMillis = t.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;

        List<SettleStatsService.StatRow> rows = stats.stats(d, fromMillis, toMillis, businessMode);
        Map<String, String> names = namesOf(d, rows);

        return rows.stream().map(r -> new StatRowVO(
                r.dimKey(),
                displayName(d, r.dimKey(), names),
                r.grossMinor(), r.commissionMinor(), r.serviceFeeMinor(),
                r.channelFeeMinor(), r.netMinor(), r.billCount())).toList();
    }

    /**
     * 批量取展示名。<b>批量而不是逐行</b> —— 逐行查是 N+1，而这是一张可能几十行的表。
     *
     * <p>{@link Dim#PAY_MERCHANT} 不查名字：收款商户号本来就没有「名字」这个东西，
     * 硬去查会取到主体名，而那会让人以为这一行是按主体聚合的 ——
     * 恰恰是类注释里规则 3 要防的混淆。
     */
    private Map<String, String> namesOf(Dim d, List<SettleStatsService.StatRow> rows) {
        Set<String> keys = rows.stream().map(SettleStatsService.StatRow::dimKey)
                .filter(k -> !SettleStatsService.UNASSIGNED.equals(k))
                .collect(Collectors.toSet());
        if (keys.isEmpty()) {
            return Map.of();
        }
        return switch (d) {
            case STORE -> merchantPort.storeNames(keys);
            case ENTITY -> merchantPort.findAll(keys).entrySet().stream()
                    .filter(e -> e.getValue() != null && e.getValue().merchantName() != null)
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().merchantName()));
            case PAY_MERCHANT -> Map.of();
        };
    }

    /** 空门店那一行给个能读的名字；其余查不到就回落成键本身，不给空串 */
    private static String displayName(Dim d, String key, Map<String, String> names) {
        if (SettleStatsService.UNASSIGNED.equals(key)) {
            return d == Dim.STORE ? "未分配门店" : "未分配";
        }
        String n = names.get(key);
        return n == null || n.isBlank() ? key : n;
    }

    /** 维度名非法时拒，**不要默认回落到某个维度** —— 那会让传错参数的人拿到一份看似正常的数 */
    private static Dim parseDim(String dim) {
        try {
            return Dim.valueOf(dim == null ? "" : dim.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
