package ai.neargo.shop.paybridge;

import ai.neargo.shop.spi.pay.PayMerchantQueryPort;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort.PayRoute;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 比对 C07（执行方案 §4.5 S5；表结构说明 §8.2；TDD-支付服务-I4进件与收款商户 S5）：每个门店 × 真实渠道，
 * 旧解析 {@link MerchantQueryPort#payMerchantNoOf}（结算取号用的那条）与新 {@link PayMerchantQueryPort#routeOf} 逐条比 ——
 * 号；号一致时再比渠道子商户号与资金路径。
 * <p>
 * 差异要么修数据，要么人工确认；确认按 {@link Diff#key()}（门店、渠道、类别、新旧两个值）认，数据再变就作废。
 * <b>只有全量比对、未确认差异为 0</b> 才放行读切（S6 门禁）。线下 / 免单 / 桩渠道旧解析不按渠道过滤，不比。
 */
@Component
public class PayMerchantComparator {

    private final MerchantQueryPort merchants;
    private final PayMerchantQueryPort routes;
    private final JdbcTemplate jdbc;

    public PayMerchantComparator(MerchantQueryPort merchants, PayMerchantQueryPort routes, JdbcTemplate jdbc) {
        this.merchants = merchants;
        this.routes = routes;
        this.jdbc = jdbc;
    }

    /**
     * 一条差异。
     *
     * @param kind NO_PAY 旧有新无 · NO_LEGACY 旧无新有 · NUMBER 号不同 · SUB_MCHID 渠道子商户号不同 · FUNDS_PATH 资金路径不同
     */
    public record Diff(String entityNo, String storeNo, Long storeId, String channel, String kind, String legacyValue,
                       String payValue, boolean confirmed) {

        /** 人工确认的键：数据变了（任一侧的值变了）确认自然失效 */
        public String key() {
            return String.join("|", storeNo, channel, kind, String.valueOf(legacyValue), String.valueOf(payValue));
        }

        @Override
        public String toString() {
            return kind + " " + entityNo + "/" + storeNo + " " + channel + "：旧 " + legacyValue + " · 新 " + payValue
                    + (confirmed ? "（已确认）" : "");
        }
    }

    /**
     * @param checked   比过的门店 × 渠道数
     * @param fullScope 是否全量（按主体抽查不能当门禁）
     */
    public record Result(int checked, List<Diff> diffs, boolean fullScope) {

        public boolean passed() {
            return diffs.stream().allMatch(Diff::confirmed);
        }

        public long unconfirmed() {
            return diffs.stream().filter(d -> !d.confirmed()).count();
        }

        /** S6 门禁 */
        public boolean readSwitchAllowed() {
            return fullScope && passed();
        }
    }

    /**
     * @param entityNos 要比的主体；空 = 全量
     * @param confirmed 已人工确认的差异键（{@link Diff#key()}）
     */
    public Result compare(Collection<String> entityNos, Set<String> confirmed) {
        boolean full = entityNos == null || entityNos.isEmpty();
        List<String> entities = full
                ? jdbc.queryForList("SELECT entity_no FROM mch_entity WHERE deleted=0 ORDER BY entity_no", String.class)
                : List.copyOf(entityNos);
        Set<String> ok = confirmed == null ? Set.of() : confirmed;
        int checked = 0;
        List<Diff> diffs = new ArrayList<>();
        for (String entityNo : entities) {
            Map<String, Object> e = jdbc.queryForList("SELECT market, funds_mode FROM mch_entity WHERE entity_no=?"
                    + " AND deleted=0", entityNo).stream().findFirst().orElse(null);
            if (e == null) {
                continue;
            }
            String market = Objects.requireNonNullElse((String) e.get("market"), "CN");
            String funds = Objects.requireNonNullElse((String) e.get("funds_mode"), "AGGREGATED");
            List<String> legacyChannels = jdbc.queryForList("SELECT DISTINCT pay_channel FROM mch_payment_merchant"
                    + " WHERE entity_no=? AND deleted=0", String.class, entityNo);
            for (Map<String, Object> s : jdbc.queryForList("SELECT id, store_no FROM mch_store WHERE entity_no=?"
                    + " AND deleted=0 ORDER BY id", entityNo)) {
                Long storeId = ((Number) s.get("id")).longValue();
                String storeNo = (String) s.get("store_no");
                Set<String> channels = new LinkedHashSet<>(legacyChannels);
                routes.collectableRoutes(storeId, market).forEach(r -> channels.add(r.payChannel()));
                channels.removeAll(MerchantQueryPort.CHANNEL_FREE_PAY_CHANNELS);
                for (String channel : channels) {
                    checked++;
                    compareOne(entityNo, storeNo, storeId, channel, funds).forEach(d -> diffs.add(
                            new Diff(d.entityNo(), d.storeNo(), d.storeId(), d.channel(), d.kind(), d.legacyValue(),
                                    d.payValue(), ok.contains(d.key()))));
                }
            }
        }
        return new Result(checked, diffs, full);
    }

    private List<Diff> compareOne(String entityNo, String storeNo, Long storeId, String channel, String funds) {
        String legacy = merchants.payMerchantNoOf(entityNo, storeNo, channel).orElse(null);
        Optional<PayRoute> route = routes.routeOf(storeId, channel);
        String pay = route.map(PayRoute::payMerchantNo).orElse(null);
        List<Diff> out = new ArrayList<>();
        if (legacy == null && pay == null) {
            return out;
        }
        if (pay == null || legacy == null || !legacy.equals(pay)) {
            String kind = pay == null ? "NO_PAY" : legacy == null ? "NO_LEGACY" : "NUMBER";
            out.add(new Diff(entityNo, storeNo, storeId, channel, kind, legacy, pay, false));
            return out;
        }
        PayRoute r = route.get();
        String legacySub = jdbc.queryForList("SELECT sub_mchid FROM mch_payment_merchant WHERE pay_merchant_no=?",
                String.class, legacy).stream().findFirst().orElse(null);
        String paySub = "MERCHANT_OWN".equals(r.accessMode()) ? r.channelMerchantId() : r.subMerchantId();
        if (!Objects.equals(legacySub, paySub)) {
            out.add(new Diff(entityNo, storeNo, storeId, channel, "SUB_MCHID", legacySub, paySub, false));
        }
        if (!Objects.equals(funds, r.fundsPath())) {
            out.add(new Diff(entityNo, storeNo, storeId, channel, "FUNDS_PATH", funds, r.fundsPath(), false));
        }
        return out;
    }
}
