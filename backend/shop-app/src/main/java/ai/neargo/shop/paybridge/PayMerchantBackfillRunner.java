package ai.neargo.shop.paybridge;

import ai.neargo.shop.pay.merchant.backfill.PayMerchantBackfill;
import ai.neargo.shop.pay.merchant.backfill.PayMerchantBackfill.LegacyMerchant;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 收款商户回填 B1–B4（执行方案 §4.5；表结构说明 §8.1 / §8.2；TDD-支付服务-I4进件与收款商户 S3）。
 * 旧表 {@code mch_payment_merchant / mch_store / mch_entity} 在这里读，支付域只收解析好的行。
 * <p>
 * 冲突一律进报告、不自动取舍：Q2 主体级号找不到默认门店；Q3 门店上指定的号与门店自己那行的号不一致；
 * METHOD 旧支付方式映射不到支付产品。按主体跑，幂等，可重复跑。
 */
@Component
public class PayMerchantBackfillRunner {

    private static final TypeReference<List<String>> METHODS = new TypeReference<>() {
    };

    private final PayMerchantBackfill backfill;
    private final PayMerchantBindingService bindings;
    private final PayMerchantQueryPort routes;
    private final JdbcTemplate jdbc;
    // pay_methods 只是一个字符串数组，不借容器里的 JSON 组件
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String wechatMode;
    private final String wechatMchid;

    public PayMerchantBackfillRunner(PayMerchantBackfill backfill, PayMerchantBindingService bindings,
                                     PayMerchantQueryPort routes, JdbcTemplate jdbc,
                                     @Value("${shop.pay.wechat.mode:direct}") String wechatMode,
                                     @Value("${shop.pay.wechat.mchid:}") String wechatMchid) {
        this.backfill = backfill;
        this.bindings = bindings;
        this.routes = routes;
        this.jdbc = jdbc;
        this.wechatMode = wechatMode;
        this.wechatMchid = wechatMchid;
    }

    public record Report(int merchants, int bindings, List<String> conflicts) {
    }

    /** @param entityNos 要回填的主体；空 = 全部 */
    public Report run(Collection<String> entityNos) {
        List<String> entities = entityNos == null || entityNos.isEmpty()
                ? jdbc.queryForList("SELECT DISTINCT entity_no FROM mch_payment_merchant WHERE deleted=0 ORDER BY entity_no",
                        String.class)
                : List.copyOf(entityNos);
        int merchants = 0;
        int bound = 0;
        List<String> conflicts = new ArrayList<>();
        for (String entityNo : entities) {
            int[] n = entity(entityNo, conflicts, false);
            merchants += n[0];
            bound += n[1];
        }
        return new Report(merchants, bound, conflicts);
    }

    /**
     * 双写（S4）：同一套规则，但<b>旧表说了算</b> —— 收款商户与进件单跟上旧行，门店绑定与旧表不一致就改绑，
     * 旧表已解析不出号的门店解绑。冲突照样只报不取舍（Q3 的门店保持原样）。
     */
    public Report sync(String entityNo) {
        List<String> conflicts = new ArrayList<>();
        int[] n = entity(entityNo, conflicts, true);
        return new Report(n[0], n[1], conflicts);
    }

    private record Store(Long id, String storeNo, boolean isDefault, String payMerchantNo) {
    }

    private int[] entity(String entityNo, List<String> conflicts, boolean follow) {
        Map<String, Object> e = jdbc.queryForList("SELECT name, market, funds_mode FROM mch_entity WHERE entity_no=?"
                + " AND deleted=0", entityNo).stream().findFirst().orElse(null);
        if (e == null) {
            conflicts.add("ENTITY " + entityNo + "：主体不存在或已删除");
            return new int[] {0, 0};
        }
        String market = Objects.requireNonNullElse((String) e.get("market"), "CN");
        String currency = currencyOf(market);
        if (currency == null) {
            conflicts.add("CURRENCY " + entityNo + "：市场 " + market + " 没有对应币种");
            return new int[] {0, 0};
        }
        Map<String, Store> stores = new LinkedHashMap<>();
        Store defaultStore = null;
        for (Map<String, Object> s : jdbc.queryForList("SELECT id, store_no, is_default, pay_merchant_no FROM mch_store"
                + " WHERE entity_no=? AND deleted=0 ORDER BY id", entityNo)) {
            Store st = new Store(((Number) s.get("id")).longValue(), (String) s.get("store_no"),
                    ((Number) s.get("is_default")).intValue() == 1, (String) s.get("pay_merchant_no"));
            stores.put(st.storeNo(), st);
            if (st.isDefault()) {
                defaultStore = st;
            }
        }
        // B2 / B4：逐行迁收款商户；号 → (渠道, 新 id, 行上门店)
        Map<String, Long> idByNo = new HashMap<>();
        Map<String, String> channelByNo = new HashMap<>();
        // 本主体全部的号（不论状态）→ 行上门店（主体级为空串）
        Map<String, String> levelOfNo = new HashMap<>();
        Map<String, String> entityLevelByChannel = new HashMap<>();
        Map<String, String> storeLevel = new HashMap<>();
        int merchants = 0;
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM mch_payment_merchant WHERE entity_no=? AND deleted=0"
                + " ORDER BY id", entityNo)) {
            String storeNo = (String) r.get("store_no");
            String no = (String) r.get("pay_merchant_no");
            String channel = (String) r.get("pay_channel");
            if (no != null) {
                levelOfNo.put(no, storeNo == null ? "" : storeNo);
            }
            Store owner = storeNo == null || storeNo.isEmpty() ? defaultStore : stores.get(storeNo);
            if (owner == null) {
                conflicts.add((storeNo == null || storeNo.isEmpty() ? "Q2 " + entityNo + "：主体级号 " + no
                        + " 找不到默认门店" : "STORE " + entityNo + "/" + storeNo + "：行上的门店不存在") + "，未迁");
                continue;
            }
            String accessMode = accessModeOf(channel);
            Long parentId = null;
            String parentMchid = null;
            if (!"MERCHANT_OWN".equals(accessMode)) {
                parentMchid = "WECHAT".equals(channel) && !wechatMchid.isBlank() ? wechatMchid : "PLATFORM-" + channel;
                parentId = backfill.platformMerchant(channel, parentMchid, market, currency, accessMode);
            }
            var row = new LegacyMerchant(((Number) r.get("id")).longValue(), (String) e.get("name"),
                    owner.id(), channel, no, (String) r.get("apply_status"), (String) r.get("sub_mchid"),
                    (String) r.get("channel_apply_no"), (String) r.get("reject_reason"), (String) r.get("legal_form"),
                    bool(r.get("invoice_capable")), (String) r.get("settle_account_type"),
                    (String) r.get("settle_account_masked"), (String) r.get("fee_bearer"), (String) r.get("settle_cycle"),
                    bool(r.get("split_reversible")), lng(r.get("quota_limit_minor")), lng(r.get("quota_used_minor")),
                    (String) r.get("quota_period"), market, currency,
                    Objects.requireNonNullElse((String) e.get("funds_mode"), "AGGREGATED"), accessMode, parentId,
                    parentMchid, methods((String) r.get("pay_methods")));
            var result = follow ? backfill.mirror(row) : backfill.merchant(row);
            conflicts.addAll(result.conflicts());
            if (result.payMerchantId() == null) {
                continue;
            }
            merchants++;
            if ("ACTIVE".equals(r.get("apply_status"))) {
                idByNo.put(no, result.payMerchantId());
                channelByNo.put(no, channel);
                if (storeNo == null || storeNo.isEmpty()) {
                    entityLevelByChannel.put(channel, no);
                } else {
                    storeLevel.put(storeNo + "|" + channel, no);
                }
            }
        }
        // B3：顺序同旧解析（MerchantPortImpl#accountOf）—— 门店指定的号（须是主体级或本店专属、ACTIVE、同渠道，
        // 否则往下落）→ 本店专属号 → 主体级号；前两者都有且不同 → Q3，不取舍
        int bound = 0;
        Set<String> channels = new LinkedHashSet<>(channelByNo.values());
        for (Store st : stores.values()) {
            String explicit = st.payMerchantNo() == null || st.payMerchantNo().isBlank() ? null : st.payMerchantNo();
            if (explicit != null && !levelOfNo.containsKey(explicit)) {
                conflicts.add("UNKNOWN " + st.storeNo() + "：门店指定的号 " + explicit + " 不是本主体的号，按没指定处理");
            }
            for (String channel : channels) {
                Set<String> candidates = new LinkedHashSet<>();
                if (explicit != null && idByNo.containsKey(explicit) && channel.equals(channelByNo.get(explicit))
                        && (levelOfNo.get(explicit).isEmpty() || levelOfNo.get(explicit).equals(st.storeNo()))) {
                    candidates.add(explicit);
                }
                String own = storeLevel.get(st.storeNo() + "|" + channel);
                if (own != null) {
                    candidates.add(own);
                }
                if (candidates.isEmpty() && entityLevelByChannel.containsKey(channel)) {
                    candidates.add(entityLevelByChannel.get(channel));
                }
                if (candidates.size() > 1) {
                    conflicts.add("Q3 " + st.storeNo() + " " + channel + "：两个来源给了不同的号 " + candidates + "，不绑");
                    continue;
                }
                Long target = candidates.isEmpty() ? null : idByNo.get(candidates.iterator().next());
                var current = routes.routeOf(st.id(), channel);
                if (current.isPresent() && current.get().payMerchantId().equals(target)) {
                    continue;
                }
                if (current.isPresent() && !follow) {
                    conflicts.add("BOUND " + st.storeNo() + " " + channel + "：新模型已绑 "
                            + current.get().payMerchantNo() + "，与旧表 " + candidates + " 不一致，不改");
                    continue;
                }
                if (target != null) {
                    // 改绑时 bind 在同一事务里把旧的 ACTIVE 转 INACTIVE
                    bindings.bind(st.id(), channel, target, follow ? "DUAL-WRITE" : "BACKFILL-B3");
                    bound++;
                } else if (current.isPresent()) {
                    bindings.unbind(st.id(), channel, "DUAL-WRITE");
                }
            }
        }
        return new int[] {merchants, bound};
    }

    /** 微信收付通 / 支付宝走服务商；微信直连是商户自己的号；测试与桩渠道挂平台 */
    private String accessModeOf(String channel) {
        return switch (channel) {
            case "WECHAT" -> "ecommerce".equals(wechatMode) ? "SERVICE_PROVIDER" : "MERCHANT_OWN";
            case "ALIPAY" -> "SERVICE_PROVIDER";
            default -> "PLATFORM_SUB";
        };
    }

    private static String currencyOf(String market) {
        return switch (market) {
            case "CN" -> "CNY";
            case "AE" -> "AED";
            case "SA" -> "SAR";
            default -> null;
        };
    }

    private List<String> methods(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(raw, METHODS);
        } catch (Exception ex) {
            return List.of(raw.split(","));
        }
    }

    private static boolean bool(Object v) {
        return v != null && (v instanceof Boolean b ? b : ((Number) v).intValue() == 1);
    }

    private static long lng(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }
}
