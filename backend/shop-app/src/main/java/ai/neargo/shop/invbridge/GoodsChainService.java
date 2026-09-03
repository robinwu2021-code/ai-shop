package ai.neargo.shop.invbridge;

/**
 * 单商品全链路状态（M5）。
 *
 * <p>缺口清单里商品④ 的那一条：<b>「审核到哪了、建账了吗、有库存吗、卖了多少」
 * 今天要在四个页面之间跳着看</b>，而它们各自的主键还不一样
 * （商品池按 goodsNo、库存流水按 ownerId + itemId）。
 *
 * <p>它是链条画像（{@link MerchantChainService}）的<b>单件版</b>：
 * 那一页答「今天该找哪家商家」，这一条答「这一件货现在到底怎么了」——
 * 运营点开某个商品时的第一个问题。两处的卡点判据刻意用同一套词，
 * 分叉就是两套结论。
 */
public interface GoodsChainService {

    /** 查不到这件商品时返回 null —— 由调用方决定是 404 还是空态 */
    GoodsChain of(String goodsNo);

    /**
     * @param auditStatus  审核状态（AUDITING / APPROVED / REJECTED…）
     * @param onSale       在不在架
     * @param skuCount     这个 SPU 下有几个 SKU
     * @param bookedSkus   其中<b>在进销存里建了账</b>的有几个。
     *                     少于 skuCount 就说明投影没搬全，去看「链路健康」
     * @param onHand       建了账那些的实存合计。没建账时为 0
     * @param available    可用合计（实存 − 预留）
     * @param soldCount    累计卖出
     * @param stuckAt      卡在哪一层，词与 {@code MerchantChainService.Stuck} 同一套。
     *                     null = 这一件是通的
     */
    record GoodsChain(String goodsNo, String title, String entityNo,
                      String auditStatus, boolean onSale,
                      int skuCount, int bookedSkus,
                      int onHand, int available, int soldCount,
                      String stuckAt) {
    }
}
