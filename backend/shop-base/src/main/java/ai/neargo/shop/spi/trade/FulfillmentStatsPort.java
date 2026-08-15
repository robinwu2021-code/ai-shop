package ai.neargo.shop.spi.trade;

import java.util.Collection;
import java.util.List;

/**
 * trade → fulfillment：<b>平台侧履约看板的全部数字</b>（P-5.1.1 / 5.1.2 / 5.1.3 / 5.2.1）。
 *
 * <p>与 {@link FulfillmentQueryPort} 的分工：那个是 B 端核销台的「单」（逐单读、逐单推状态），
 * 这个是平台端的「数」（跨商家、跨自提点的聚合）。分开不是因为不能塞进去，
 * 是因为两边的<b>作用域相反</b> —— 那个必须按 {@code pickupNo} 收敛（越权防线④），
 * 这个天然要看全平台。混在一个 Port 上，将来给它加数据域裁剪时会顾此失彼。
 *
 * <h2>为什么每个方法都是「现算」而不是读一张统计表</h2>
 *
 * <p><b>都从同一份订单数据算，不另存计数器</b>（B-6.0 的原话）。
 * 存一份的代价是「总览说 3 单、点进去只有 2 单」—— 而这种不一致
 * 既不报错、也无从复现，只会让运营慢慢不再相信这个看板。
 *
 * <p>规模判断：一个自提点一天几十到几百单，实时聚合完全够。
 * 等它慢了再加缓存，也比现在就存一份会分岔的计数安全。
 */
public interface FulfillmentStatsPort {

    /**
     * 有「还没取走的单」的自提点 × 到货日，以及这一堆的件数与涉及商家数。
     *
     * <p><b>到货日一期取下单日</b>：次日达/预售/生鲜截单各有各的到货口径，
     * 编一个统一公式就是编数字。口径写在 TDD-运营端履约调度 §4.3。
     */
    List<PickupDay> pickupDays();

    /**
     * @param itemCount     这一堆的件数（Σ 各单各行的数量）
     * @param merchantCount 涉及的商家数。<b>平台视角存在的理由就是它</b> ——
     *                      一个批次混装多家的货，分拣工作量按这个数预判
     */
    record PickupDay(String pickupNo, String arriveDate, int itemCount, int merchantCount) {
    }

    /**
     * 待分拣明细：给定自提点上「还没取走」的单，逐行拆到 SKU × 供货商家。
     *
     * <p>聚合留给调用方 —— Port 只负责把原始行取出来，
     * 「按什么聚合」是看板的事，换一种聚合不该改跨模块契约。
     */
    List<SortingItem> sortingItems(Collection<String> pickupNos);

    record SortingItem(String pickupNo, String skuNo, String title, String merchantName, int qty) {
    }

    /**
     * 核销健康度。
     *
     * @param overdueBefore 「到货时刻早于它」即算逾期。由逾期规则的宽限小时数算出来 ——
     *                      <b>这就是逾期规则真正被消费的地方</b>：宽限期一改，
     *                      同一批订单里逾期数立刻变。
     *                      到货时刻取自订单状态日志里推进到「已到货」那一条，
     *                      不是 {@code updated_at}（那一列任何一次写都会动）
     */
    List<PickupRedeem> redeemStats(Collection<String> pickupNos, long overdueBefore);

    /**
     * @param pending  待核销（含还没到点的，与已到点但仍在宽限期内的）
     * @param redeemed 已核销
     * @param overdue  已到点、超过宽限期还没人来取
     */
    record PickupRedeem(String pickupNo, int pending, int redeemed, int overdue) {
    }

    /**
     * 快递履约且已回填快递单号的子单（P-5.2.1）。
     *
     * <p>没回填单号的不给：那种单还没发货，平台侧的「运单记录」无从建起，
     * 建了也是一条永远没有轨迹的空记录。
     */
    List<ExpressOrder> expressOrders();

    /**
     * @param status 子单状态。运单状态由它推导 —— 一期不接承运商回传，
     *               <b>编一个假的轨迹推进比没有更糟</b>
     * @param region 收件地区（省 市），取自下单时的地址快照。超区判断看的就是它
     */
    record ExpressOrder(String subOrderNo, String expressNo, String status,
                        String receiver, String region, long createdAt) {
    }
}
