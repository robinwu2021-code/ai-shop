package ai.neargo.shop.trade.order.refund.port;

import ai.neargo.shop.trade.order.refund.model.RefundLine;

import java.util.List;

/** 售后行存储（{@code ord_after_sale_item}，{@code uk(after_sale_no, item_id)}：重复写报唯一键冲突）。 */
public interface AfterSaleLineStore {

    void saveLines(String afterSaleNo, String subOrderNo, List<RefundLine> lines);

    /** 空 = 5.5 之前建的售后单（没有行），按整单剩余处理 */
    List<RefundLine> lines(String afterSaleNo);

    /** 商家核定 / 协商额分回各行后，把各行实退额写回 */
    void updateAmounts(String afterSaleNo, List<RefundLine> lines);
}
