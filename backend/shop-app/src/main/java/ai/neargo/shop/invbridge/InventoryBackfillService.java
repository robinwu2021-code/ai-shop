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
     * @param dryRun 只算不写。**先跑一次 dryRun 看报告**，再决定要不要真搬
     * @param limit  一次最多处理多少个 SKU；分批跑，别让一次搬运变成一个长事务
     */
    Report run(boolean dryRun, int limit);

    /** 只对差，不搬。切真相源之前每天跑一次，直到连续为零。 */
    Report diffOnly(int limit);

    /**
     * @param moved     本次真正搬了几条余额
     * @param skipped   已经搬过（有 INIT 单）而跳过的
     * @param diffs     两边数不一致的明细 —— <b>空列表是唯一可接受的结果</b>
     */
    record Report(int scannedSkus, int moved, int skipped, List<Diff> diffs) {

        public boolean clean() {
            return diffs.isEmpty();
        }
    }

    /** @param platformQty 平台侧的数 · @param inventoryQty 进销存侧的数 */
    record Diff(String entityNo, String storeNo, String skuNo,
                int platformQty, int inventoryQty) {
    }
}
