package ai.neargo.shop.invbridge;

import java.util.List;

/**
 * 存量库存搬运：平台库 → 进销存库。
 *
 * <h2>为什么它在 shop-app 而不在任何一个域里</h2>
 * 它是**唯一被允许同时认识两边**的东西：读平台的 {@code prd_sku} / {@code prd_store_stock}，
 * 写进销存的物料与余额。放进 {@code shop-inventory} 会让那个域反过来依赖平台
 * （独立交付时整段没法编译）；放进 {@code shop-core} 会让商品域知道进销存的存在。
 * 装配层是它唯一正确的位置 —— 也正因为它特殊，才单独一个包，一眼看得出来。
 *
 * <h2>三条硬要求</h2>
 * <ol>
 *   <li><b>只读平台库</b>。搬运不改 {@code prd_*} 一个字节 —— 真相源切换是 D2 的事，
 *       不是搬运的事。搬错了要能原样重来。</li>
 *   <li><b>幂等可重跑</b>。每条余额落一张 {@code source_type=INIT} 的入库单，
 *       {@code source_ref} 是平台键；重跑时先查这张单，有就跳过。
 *       靠「余额是不是 0」判断不行 —— 搬完之后正常出入库会让它变成任何数。</li>
 *   <li><b>结束打对差</b>。逐条比两边的数，不为零的列出来。
 *       这是 G3 闸门的数据来源：<b>对差连续为零才准切真相源</b>。</li>
 * </ol>
 *
 * <p><b>为什么不设回退</b>：回退比对差更危险 —— 它要在「已经切了一半」的状态下
 * 把两边都还原，而那时哪一边是对的本身就说不清。双写对差期比回退按钮可靠得多。
 */
public interface InventoryBackfillService {

    /**
     * @param dryRun  只算不写。**先跑一次 dryRun 看报告**，再决定要不要真搬
     * @param limit   一次最多处理多少个 SKU；分批跑，别让一次搬运变成一个长事务
     * @param afterId <b>游标：只扫 {@code prd_sku.id} 大于它的行</b>；{@code null} 从头开始。
     *
     *                <p>它原本不存在，每一轮都从第一行扫 {@code LIMIT limit×4} ——
     *                于是第二轮起扫到的是同一批、全部「已搬过」，报告长得
     *                <b>和「搬完了」一模一样</b>，而第 501 个 SKU 永远搬不到。
     *                注释里写的「分批跑，中断了下一轮从没搬的那条继续」当时是假的。
     */
    Report run(boolean dryRun, int limit, Long afterId);

    /**
     * 只对差，不搬。切真相源之前每天跑一次，直到连续为零。
     *
     * <p><b>内部翻到底</b>，不是只看一批：一道只抽样的闸门比没有闸门更坏 ——
     * 它给的是「看过的那些没问题」，而读的人以为是「没问题」。
     *
     * @param maxScan 最多扫多少个 SKU 才停。停下来时 {@code clean} 一律为 false
     *                并在日志里说明被截断（<b>不静默截断</b>）
     */
    Report diffOnly(int maxScan);

    /**
     * @param moved       本次真正搬了几条余额
     * @param skipped     已经搬过（有 INIT 单）而跳过的
     * @param pending     扫到了但**还没搬**的。<b>切真相源之前它必须是 0</b> ——
     *                    没搬的那些在进销存侧余额为 0，切过去就是「全都卖不了」
     * @param nextAfterId 下一轮的游标；{@code null} = 这一轮已经扫到末尾
     * @param diffs       两边数不一致的明细 —— <b>空列表是唯一可接受的结果</b>
     */
    record Report(int scannedSkus, int moved, int skipped, int pending, boolean clean,
                  Long nextAfterId, List<Diff> diffs) {

        /*
         * clean **必须是一个字段，不能只是一个方法**。
         *
         * 它原本写成 `boolean clean() { return diffs.isEmpty(); }` —— Java record
         * 只序列化它的组件，额外的访问器 Jackson 一律不出，于是 JSON 里根本没有这一列。
         * 前端读到 undefined，而 undefined 是 falsy：对差**干净的时候**界面照样显示
         * 「有差异，不得切换」。不报错，且方向恰好是「永远不让切」——
         * 看起来像很保守，实际是这个闸门坏了而没有人会发现。
         *
         * 保留便利构造：clean 由 diffs 与 pending 推出来，调用方**不该自己传** ——
         * 传错一次，G3 的唯一判据就废了。
         *
         * <b>clean 不只是「没有差异」，还要「没有待搬的」。</b>
         * 原来只看 diffs：而 {@code moveOne} 在只算不写时**故意不把没搬过的算成差异**
         * （「没搬过的当然对不上」），{@code doRun} 又把它们计成既不 moved 也不 skipped ——
         * 于是没搬过的 SKU 在报告里一个字都不出现，`clean` 照样是 true。
         * 那句「连续为零才准切」守的是一个它没在看的东西。
         */
        public Report(int scannedSkus, int moved, int skipped, int pending,
                      Long nextAfterId, List<Diff> diffs) {
            this(scannedSkus, moved, skipped, pending, diffs.isEmpty() && pending == 0,
                    nextAfterId, diffs);
        }
    }

    /** @param platformQty 平台侧的数 · @param inventoryQty 进销存侧的数 */
    record Diff(String entityNo, String storeNo, String skuNo,
                int platformQty, int inventoryQty) {
    }
}
