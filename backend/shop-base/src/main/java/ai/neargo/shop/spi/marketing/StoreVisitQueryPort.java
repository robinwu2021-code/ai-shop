package ai.neargo.shop.spi.marketing;

import java.util.Collection;
import java.util.Map;

/**
 * 门店访问埋点的<b>跨域只读口</b>。
 *
 * <p>埋点表（{@code mkt_store_visit}）在 marketing 域（shop-core），
 * 而店铺码档案在 merchant 域（shop-merchant）—— 两者是兄弟模块，互相够不着。
 * 店铺码页要显示「这个码被扫了多少次」，只能走这一条口。
 *
 * <p><b>刻意只给聚合数，不给明细</b>：明细里有 device_id 与 IP，
 * 那是风控与归因的口径，不该因为「顺便也能拿到」而漏进商家治理页。
 */
public interface StoreVisitQueryPort {

    /**
     * 批量取扫码次数（PV）。
     *
     * <p><b>批量而不是逐个</b>：店铺码页一屏几十行，逐行查就是 N+1，
     * 而它与商家名的批量解析是同一屏上的同一类问题。
     *
     * @param entityNos 主体号；空集合直接返回空 Map，不打库
     * @param from      起（毫秒，含）
     * @param to        止（毫秒，含）
     * @return 主体号 → 扫码次数；<b>没有扫码记录的主体不出现在结果里</b>
     *         （调用方自己决定显示 0 还是「未登记」—— 这两件事不一样）
     */
    Map<String, Long> scanCounts(Collection<String> entityNos, long from, long to);

    /**
     * 平台级漏斗的**前两环**（扫码 / 进店）。
     *
     * <p>给平台看板用。它与门店获客看板<b>必须是同一个口径</b> ——
     * 两处各算一份的话，首页漏斗和门店看板会给出两个不一样的「扫码数」，
     * 而两个都看起来是对的，只有人在会上对不上账时才发现。
     *
     * @return 该区间内的扫码人数与进店人数
     */
    Funnel platformFunnel(long from, long to);

    /**
     * @param scanUv 扫码人数（UV，匿名按设备去重）
     * @param enter  进店人数（归因到某店的去重用户数）
     */
    record Funnel(long scanUv, long enter) {
    }
}
