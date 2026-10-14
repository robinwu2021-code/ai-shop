package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.model.RefundableView;
import ai.neargo.shop.trade.order.refund.policy.DefaultAfterSaleWindowPolicy;
import ai.neargo.shop.trade.order.refund.policy.DefaultChannelRefundCapability;
import ai.neargo.shop.trade.order.refund.policy.DefaultFreightRefundPolicy;
import ai.neargo.shop.trade.order.refund.policy.DefaultLineAmountPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 可退额视图 + 退款方案（第 5 步对象模型 §3）。 */
class RefundPlannerTest {

    private final RefundableViewFactory views = new RefundableViewFactory(
            new DefaultAfterSaleWindowPolicy(7, 15), new DefaultChannelRefundCapability());
    private final RefundPlanner planner = new RefundPlanner(new DefaultLineAmountPolicy(), new DefaultFreightRefundPolicy());

    /** 子单：行 9 买 3 件、行 10 买 1 件；商品实付 13000，运费 500 */
    private RefundableView view(long item9Refunded, int item9RefundedQty, long subRefunded) {
        OrdSubOrder sub = new OrdSubOrder();
        sub.setSubOrderNo("S1");
        sub.setPayAmount(13500L);
        sub.setFreightAmount(500L);
        sub.setRefundedMinor(subRefunded);
        OrdItem a = item(9L, 3, 9000L, 9000L, item9RefundedQty, item9Refunded);
        OrdItem b = item(10L, 1, 4000L, 4000L, 0, 0L);
        return views.build(sub, List.of(a, b), "WECHAT");
    }

    @Test
    @DisplayName("★★★ 退 3 件中的 1 件：行 9 → 3000；不发运费（已发货、买家原因）")
    void partialLine() {
        RefundPlan p = planner.plan(view(0, 0, 0), List.of(new RefundLine(9, 1, 0)), true, "NOT_WANTED");
        assertThat(p.lines()).containsExactly(new RefundLine(9, 1, 3000));
        assertThat(p.freightMinor()).isZero();
    }

    @Test
    @DisplayName("★★ 不传行 = 整单剩余；未发货 → 连运费")
    void wholeRemaining() {
        RefundPlan p = planner.plan(view(0, 0, 0), null, false, "NOT_WANTED");
        assertThat(p.goodsMinor()).isEqualTo(13000);
        assertThat(p.freightMinor()).isEqualTo(500);
    }

    @Test
    @DisplayName("★★ 再次申请：剩余按已退扣减（行 9 已退 1 件 3000 → 剩 2 件 6000）")
    void secondApplication() {
        RefundableView v = view(3000, 1, 3000);
        assertThat(v.line(9).remainingQty()).isEqualTo(2);
        RefundPlan p = planner.plan(v, List.of(new RefundLine(9, 2, 0)), true, "NOT_WANTED");
        assertThat(p.goodsMinor()).isEqualTo(6000);
    }

    @Test
    @DisplayName("★★ 件数超过剩余 / 行不存在 / 件数 ≤ 0 → 拒")
    void invalid() {
        RefundableView v = view(3000, 1, 3000);
        assertThatThrownBy(() -> planner.plan(v, List.of(new RefundLine(9, 3, 0)), true, "X"))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.AFTERSALE_AMOUNT_INVALID);
        assertThatThrownBy(() -> planner.plan(v, List.of(new RefundLine(77, 1, 0)), true, "X"))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThatThrownBy(() -> planner.plan(v, List.of(new RefundLine(9, 0, 0)), true, "X"))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("已退完 → 没有可退的，拒")
    void nothingLeft() {
        RefundableView v = view(9000, 3, 13500);
        assertThatThrownBy(() -> planner.plan(new RefundableView(v.subOrderNo(),
                        List.of(v.line(9)), 0, null, true), null, true, "X"))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.AFTERSALE_AMOUNT_INVALID);
    }

    @Test
    @DisplayName("视图：运费剩余 = 运费 − 已退运费（已退总额 − 各行已退）；线下单不支持部分退")
    void viewFreightAndChannel() {
        assertThat(view(3000, 1, 3500).freightRemainingMinor()).as("已退 3500 中 500 是运费").isZero();
        OrdSubOrder sub = new OrdSubOrder();
        sub.setSubOrderNo("S2");
        sub.setPayAmount(1000L);
        assertThat(views.build(sub, List.of(item(1L, 1, 1000L, 1000L, 0, 0L)), "OFFLINE").partialSupported()).isFalse();
    }

    private static OrdItem item(long id, int qty, long amount, long share, int refundedQty, long refunded) {
        OrdItem it = new OrdItem();
        it.setId(id);
        it.setTitle("T" + id);
        it.setQty(qty);
        it.setAmount(amount);
        it.setPaidShareMinor(share);
        it.setRefundedQty(refundedQty);
        it.setRefundedMinor(refunded);
        return it;
    }
}
