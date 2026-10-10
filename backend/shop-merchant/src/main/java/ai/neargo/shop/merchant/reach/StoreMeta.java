package ai.neargo.shop.merchant.reach;

import ai.neargo.shop.merchant.reach.ReachRule.Route;

import java.util.List;

/**
 * 判定一家店要用的<b>门店属性</b>（ADR-034）。与 {@link StoreHits}（命中了哪几条范围项）正交：
 * 命中查库、属性走快照。
 *
 * @param unlimited           有没有显式的「全平台不限」纳入项（{@code level=UNLIMITED}）。
 *                            它只对快递/自送路为真，自提没有落点 —— 由 {@link ReachRule} 判
 * @param hasAdminExclude     有没有行政级（省/市/区/街道/村居）的排除项。
 *                            有而消费者没有区划码时 fail-closed：不能证明「不在排除里」就不放行
 * @param hasCommunityExclude 有没有聚落级排除项。<b>不 fail-closed</b> —— 聚落解析是尽力而为，
 *                            若也收紧，所有纯定位用户会看不到任何「排除了某一栋楼」的店，不成比例
 * @param hasPolygonExclude   有没有排除型多边形。有而消费者没有坐标时 fail-closed
 */
public record StoreMeta(String entityNo, String storeNo, List<Route> routes, boolean unlimited,
                        boolean hasAdminExclude, boolean hasCommunityExclude, boolean hasPolygonExclude) {

    public StoreMeta {
        routes = routes == null ? List.of() : List.copyOf(routes);
    }
}
