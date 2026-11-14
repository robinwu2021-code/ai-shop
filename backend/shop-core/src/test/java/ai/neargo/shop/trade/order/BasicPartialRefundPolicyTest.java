package ai.neargo.shop.trade.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.service.BasicRefundPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class BasicPartialRefundPolicyTest {
    @Test void sequentialThirdsConsumeExactOriginalGrossWithoutLosingTail() {
        var sub = new OrdSubOrder(); sub.setSubOrderNo("S"); sub.setPayAmount(101L); sub.setFreightAmount(0L); sub.setStatus(OrdSubOrder.WAIT_FULFILL);
        sub.setRefundedMinor(0L);
        var item = new OrdItem(); item.setId(1L); item.setQty(3); item.setShippedQty(0); item.setPaidShareMinor(101L);
        item.setRefundedQty(0); item.setRefundedMinor(0L);
        long[] expected = {33, 34, 34};
        for (long amount : expected) {
            var plan = BasicRefundPolicy.select(sub, List.of(item), List.of(new RefundLine(1, 1, 0)), null);
            assertThat(plan.totalMinor()).isEqualTo(amount);
            item.setRefundedQty(item.getRefundedQty() + 1);
            item.setRefundedMinor(item.getRefundedMinor() + amount);
            sub.setRefundedMinor(sub.getRefundedMinor() + amount);
        }
        assertThat(sub.getRefundedMinor()).isEqualTo(101L);
        assertThatThrownBy(() -> BasicRefundPolicy.select(sub, List.of(item), List.of(new RefundLine(1, 1, 0)), null))
                .isInstanceOf(RuntimeException.class);
    }
    @Test void partialRequiresFrozenMoneyAndRejectsBenefitAndDuplicateLineClaims() {
        var sub = new OrdSubOrder(); sub.setSubOrderNo("S"); sub.setPayAmount(100L); sub.setFreightAmount(0L); sub.setStatus(OrdSubOrder.WAIT_FULFILL);
        var item = new OrdItem(); item.setId(1L); item.setQty(2); item.setShippedQty(0); item.setAmount(100L);
        assertThatThrownBy(() -> BasicRefundPolicy.select(sub, List.of(item), List.of(new RefundLine(1,1,0)), null))
                .isInstanceOf(RuntimeException.class);
        item.setPaidShareMinor(100L); sub.setDiscountAmount(1L);
        assertThatThrownBy(() -> BasicRefundPolicy.select(sub, List.of(item), List.of(new RefundLine(1,1,0)), null))
                .isInstanceOf(RuntimeException.class);
        sub.setDiscountAmount(0L);
        assertThatThrownBy(() -> BasicRefundPolicy.select(sub, List.of(item), List.of(new RefundLine(1,1,0),new RefundLine(1,1,0)),null))
                .isInstanceOf(RuntimeException.class);
    }
    @Test void partialRejectsAnyShippedOriginalEvenWhenSelectedLineIsUnshipped() {
        var sub = unshippedChild(200L);
        var shipped = originalItem(1L, 2, 100L); shipped.setShippedQty(1);
        var selected = originalItem(2L, 2, 100L);
        assertThatThrownBy(() -> BasicRefundPolicy.select(sub, List.of(shipped, selected),
                List.of(new RefundLine(2, 1, 0)), null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.ORDER_STATE_ILLEGAL);
    }

    @Test void partialAndRemainderRejectFullyShippedOrMissingFulfillmentEvidence() {
        var sub = unshippedChild(100L);
        var item = originalItem(1L, 2, 100L); item.setShippedQty(2);
        sub.setStatus(OrdSubOrder.FULFILLING);
        assertIllegalPartial(sub, item);
        item.setRefundedQty(1); item.setRefundedMinor(50L); sub.setRefundedMinor(50L);
        assertIllegalPartial(sub, item);
        item.setRefundedQty(0); item.setRefundedMinor(0L); sub.setRefundedMinor(0L);
        item.setShippedQty(0); sub.setStatus(null);
        assertIllegalPartial(sub, item);
        sub.setStatus(OrdSubOrder.WAIT_FULFILL); item.setShippedQty(null);
        assertIllegalPartial(sub, item);
    }

    @Test void frozenPartialVerificationRechecksFulfillmentButFirstFullShippedRemainsSupported() {
        var sub = unshippedChild(100L);
        var item = originalItem(1L, 2, 100L);
        var frozen = BasicRefundPolicy.select(sub, List.of(item), List.of(new RefundLine(1, 1, 0)), null);
        item.setShippedQty(1);
        assertThatThrownBy(() -> BasicRefundPolicy.verify(sub, List.of(item), frozen))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.ORDER_STATE_ILLEGAL);
        item.setShippedQty(2); sub.setStatus(OrdSubOrder.FULFILLING);
        var full = BasicRefundPolicy.select(sub, List.of(item), List.of(new RefundLine(1, 2, 0)), null);
        assertThat(full.totalMinor()).isEqualTo(100L);
        assertThat(full.lines()).containsExactly(new RefundLine(1, 2, 100L));
        assertThat(BasicRefundPolicy.select(sub, List.of(item), null, null)).isEqualTo(full);
    }

    private static void assertIllegalPartial(OrdSubOrder sub, OrdItem item) {
        assertThatThrownBy(() -> BasicRefundPolicy.select(sub, List.of(item),
                List.of(new RefundLine(1, 1, 0)), null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.ORDER_STATE_ILLEGAL);
    }

    private static OrdSubOrder unshippedChild(long paid) {
        var sub = new OrdSubOrder(); sub.setSubOrderNo("S"); sub.setPayAmount(paid);
        sub.setFreightAmount(0L); sub.setRefundedMinor(0L); sub.setStatus(OrdSubOrder.WAIT_FULFILL);
        return sub;
    }

    private static OrdItem originalItem(long id, int qty, long paid) {
        var item = new OrdItem(); item.setId(id); item.setQty(qty); item.setShippedQty(0);
        item.setPaidShareMinor(paid); item.setRefundedQty(0); item.setRefundedMinor(0L);
        return item;
    }

}
