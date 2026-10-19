package ai.neargo.shop.trade.order.machine;

import ai.neargo.shop.trade.order.machine.aftersale.*;
import ai.neargo.shop.trade.order.statemachine.Actor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RefundTerminalGuardsTest {
    private AfterSaleContext context(long cash,boolean confirmed,boolean benefits) {
        return AfterSaleContext.builder().refundProof(new RefundTerminalProof(true,cash,confirmed,benefits,false)).build();
    }
    @Test void terminalEventsFailClosedWithoutEvidence() {
        assertThat(AfterSaleMachine.MACHINE.allowed(AfterSaleStatus.REFUNDING,Actor.SYSTEM,AfterSaleContext.builder().build()))
                .doesNotContain(AfterSaleEvent.REFUND_SUCCEEDED,AfterSaleEvent.BENEFITS_SETTLED);
        assertThat(AfterSaleMachine.MACHINE.allowed(AfterSaleStatus.OFFLINE_REFUNDED,Actor.BUYER,AfterSaleContext.builder().build()))
                .doesNotContain(AfterSaleEvent.OFFLINE_REFUND_ACKED);
    }
    @Test void cashSuccessStillWaitsForBenefits() {
        assertThat(AfterSaleMachine.MACHINE.allowed(AfterSaleStatus.REFUNDING,Actor.SYSTEM,context(100,true,false)))
                .doesNotContain(AfterSaleEvent.REFUND_SUCCEEDED);
        assertThat(AfterSaleMachine.MACHINE.fire(AfterSaleStatus.REFUNDING,AfterSaleEvent.REFUND_SUCCEEDED,Actor.SYSTEM,context(100,true,true)).to())
                .isEqualTo(AfterSaleStatus.REFUNDED);
    }
    @Test void zeroCashRequiresItsOwnEventAndExistingDecision() {
        var facts=context(0,false,true);
        assertThat(AfterSaleMachine.MACHINE.allowed(AfterSaleStatus.REFUNDING,Actor.SYSTEM,facts))
                .contains(AfterSaleEvent.BENEFITS_SETTLED).doesNotContain(AfterSaleEvent.REFUND_SUCCEEDED);
        assertThat(AfterSaleMachine.MACHINE.fire(AfterSaleStatus.REFUNDING,AfterSaleEvent.BENEFITS_SETTLED,Actor.SYSTEM,facts).to())
                .isEqualTo(AfterSaleStatus.REFUNDED);
        var unapproved=AfterSaleContext.builder().refundProof(new RefundTerminalProof(false,0,false,true,false)).build();
        assertThat(AfterSaleMachine.MACHINE.allowed(AfterSaleStatus.REFUNDING,Actor.SYSTEM,unapproved))
                .doesNotContain(AfterSaleEvent.BENEFITS_SETTLED);
    }
    @Test void zeroCashDoesNotAllowAChannelOrBuyerToAssertBenefitsSettled() {
        assertThatThrownBy(() -> AfterSaleMachine.MACHINE.fire(AfterSaleStatus.REFUNDING,AfterSaleEvent.BENEFITS_SETTLED,Actor.CHANNEL,context(0,false,true)))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }
}
