package ai.neargo.shop.merchant.reach;

import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.merchant.entity.MchChannelArea;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchFulfillmentChannel;
import ai.neargo.shop.merchant.reach.ReachRule.Route;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一家店「开着哪几条履约路、每路服务范围的全部还是子集」（ADR-034 把它从 {@code StoreReachLoader} 迁出）。
 *
 * <p>迁出来是因为两个调用方要用同一份：{@code StoreReachLoader}（B 端回显自己的范围）与
 * {@link ReachSnapshotLoader}（消费者侧判定用的门店元数据快照）。
 * 留在私有方法里的话快照那边就得抄一遍 —— 而「路怎么算」抄两份，迟早有一份忘了跟上。
 *
 * <p><b>逐字保留原有语义</b>，包括那段旧单值列的兜底：{@code mch_fulfillment_channel} 一行都没有
 * （或全关、全被运营锁）的门店回落到主体的 {@code fulfillment_reach}。V397 的存量回填判据也复刻这一段 ——
 * 两处必须一致，否则「迁移前实际可见」与「迁移后仍可见」对不上。
 */
public final class StoreRoutes {

    /** 旧单值列 {@code fulfillment_reach}：channel 表一行都没有（或全关）的门店回落到它 */
    static final String LEGACY_PICKUP = "PICKUP";
    static final String LEGACY_SHIPPING = "SHIPPING";

    private StoreRoutes() {
    }

    /**
     * @param storeNo 为空 = 主体口径：一律按「全部」，不套门店的 SUBSET
     *                （「这家商家覆盖哪儿」问的是足迹，不该被某一路的收窄裁小）
     */
    public static List<Route> of(MchEntity m, String storeNo,
                                 List<MchFulfillmentChannel> channels, List<MchChannelArea> subsets) {
        Map<String, Route> out = new LinkedHashMap<>();
        for (MchFulfillmentChannel ch : channels) {
            // 运营锁路：锁着的路买家侧不可选 —— 与 enabledFulfillments 同一个口径
            if (!Boolean.TRUE.equals(ch.getEnabled()) || Boolean.TRUE.equals(ch.getOpsLocked())) {
                continue;
            }
            if (storeNo != null && MchFulfillmentChannel.SCOPE_SUBSET.equals(ch.getScopeMode())) {
                Set<String> picked = new LinkedHashSet<>();
                for (MchChannelArea ca : subsets) {
                    if (ch.getChannel().equals(ca.getChannel())) {
                        picked.add(ca.getAreaNo());
                    }
                }
                out.put(ch.getChannel(), new Route(ch.getChannel(), Set.copyOf(picked)));
            } else {
                // 主体口径一律「全部」；同一路多家店都开着时只留一条
                out.putIfAbsent(ch.getChannel(), Route.all(ch.getChannel()));
            }
        }
        if (!out.isEmpty()) {
            return List.copyOf(out.values());
        }
        /*
         * 一路都没开（或该店还没迁到 channel 模型）→ 回落旧单值列。语义与迁移前逐字一致：
         *   SHIPPING → 快递；PICKUP / 空 → 自提（没框 = 谁也看不到）；其余 → 自送。
         */
        String reach = m.getFulfillmentReach() == null ? LEGACY_PICKUP : m.getFulfillmentReach();
        if (LEGACY_SHIPPING.equals(reach)) {
            return List.of(Route.all(Fulfillments.EXPRESS));
        }
        if (LEGACY_PICKUP.equals(reach)) {
            return List.of(Route.all(Fulfillments.STORE_PICKUP));
        }
        return List.of(Route.all(Fulfillments.MERCHANT_DELIVERY));
    }
}
