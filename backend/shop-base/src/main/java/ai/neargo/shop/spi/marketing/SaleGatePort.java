package ai.neargo.shop.spi.marketing;

import java.util.Collection;
import java.util.Set;

/**
 * 商品 → 营销：<b>一件「仅活动」的货，此刻能不能买、能按哪条路买</b>（TDD-商品仅活动可售）。
 *
 * <p>判定只有一句：此刻有点名这件货、正在进行的活动，才能买；且只能按那个活动的路径买。
 * 拆成两个集合，调用方只用这两个：
 *
 * <ul>
 *   <li>{@code direct}：普通下单开着 —— 进行中的<b>集单</b>（此刻有一期可下），
 *       或点名它的进行中的<b>特价 / 买赠</b></li>
 *   <li>{@code any}：有活动在跑 —— {@code direct} ∪ 点名它的进行中的<b>拼团</b></li>
 * </ul>
 *
 * <p>「只有拼团在跑」时它在 {@code any} 里、不在 {@code direct} 里 ——
 * 那正是要拦的「单买」：开团 / 参团照常，普通下单与加购拒。
 *
 * <p><b>整单级的不算</b>：满减、立减、满件减、组合活动都不点名商品，
 * 它们照常叠加在活动单上，但不能单独让一件仅活动的货变得可买。
 *
 * <p>批量：列表一页几十件，逐件问会打几十条查询。
 */
public interface SaleGatePort {

    Live live(Collection<String> goodsNos, long now);

    /** @param direct 开着普通下单的；@param any 有任何活动在跑的（含拼团）。两者都只含入参里的货 */
    record Live(Set<String> direct, Set<String> any) {

        public static Live none() {
            return new Live(Set.of(), Set.of());
        }
    }
}
