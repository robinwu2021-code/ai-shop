package ai.neargo.shop.spi.trade;

import java.util.Optional;

/**
 * message → trade：一张子单是谁买的、属于哪一单。
 *
 * <p>物流事件只带业务单号（{@code bizRef} = 子单号）—— 物流不认识买家（ADR-032：将来独立成服务，
 * 「通知谁」是交易域的事，不该跟着运单一起登记）。通知要发给人，所以由通知这一侧回头问一次交易域。
 */
public interface SubOrderBuyerPort {

    /** 查不到子单时为空 */
    Optional<Buyer> buyerOf(String subOrderNo);

    /**
     * 这张子单买了什么 —— 一句话的摘要，不是明细。
     *
     * <p><b>为什么要它</b>：来单提醒要让人一眼看出「值不值得现在去备货」，
     * 而订单事件的 payload 只有单号与金额（{@code OrderEvents.SubOrderPaid}）——
     * 「￥12.34」看不出是一袋米还是一棵葱（TDD-商家企微群来单通知 §2.4）。
     *
     * <p><b>加在这个端口而不是新开一个</b>：这里已经是「通知侧回头问交易域一张子单的事」
     * 那条缝，调用方也已经注入了它。
     *
     * <p>查不到子单、或子单一件明细都没有时为空 —— 调用方据此省掉那一行，
     * 而不是显示「共 0 件」。
     */
    Optional<ItemsBrief> itemsOf(String subOrderNo);

    record Buyer(String userNo, String orderNo) {
    }

    /**
     * @param firstGoodsName 第一件商品的名字。<b>只取一件</b> —— 通道要的是摘要，
     *                       全列出来在群里是一屏；而「第一件 + 共几件」已经够判断了
     * @param itemCount      件数合计（各行 qty 之和，不是行数）。顾客买 3 袋米是 3 件，
     *                       不是 1 件 —— 备货看的是件数
     */
    record ItemsBrief(String firstGoodsName, int itemCount) {
    }
}
