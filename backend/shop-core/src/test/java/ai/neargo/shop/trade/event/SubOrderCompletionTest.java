package ai.neargo.shop.trade.event;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** SUB_ORDER_COMPLETED 的唯一出口：通知、报表脏日、库存出库三个消费方读的就是这份载荷。 */
class SubOrderCompletionTest {

    private static OrdSubOrder sub(String status) {
        var sub = new OrdSubOrder();
        sub.setTenantNo("MAIN");
        sub.setEntityNo("E1");
        sub.setStoreNo("S1");
        sub.setOrderNo("O1");
        sub.setSubOrderNo("O1-1");
        sub.setUserNo("U1");
        sub.setFulfillment(OrdSubOrder.STORE_PICKUP);
        sub.setStatus(status);
        return sub;
    }

    @Test
    void completedSubOrderPublishesThePinnedPayloadOnce() {
        var bus = mock(OutboxEventBus.class);

        assertThat(SubOrderCompletion.publishIfCompleted(bus, sub(OrdSubOrder.COMPLETED))).isTrue();

        var captor = ArgumentCaptor.forClass(OrderEvents.SubOrderCompleted.class);
        verify(bus).publish(captor.capture());
        var event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("SUB_ORDER_COMPLETED");
        assertThat(event.aggregateType()).isEqualTo("SUB_ORDER");
        assertThat(event.aggregateId()).isEqualTo("O1-1");
        // 与 OutboxEventBus 同一套 Jackson 序列化：字段名、取值都钉死，消费方按名读
        assertThat(JsonMapper.builder().build().writeValueAsString(event))
                .isEqualTo("{\"subOrderNo\":\"O1-1\",\"orderNo\":\"O1\",\"merchantNo\":\"E1\","
                        + "\"userNo\":\"U1\",\"fulfillment\":\"STORE_PICKUP\"}");
    }

    @Test
    void subOrderLeftWaitingForBuyerConfirmationPublishesNothing() {
        var bus = mock(OutboxEventBus.class);

        assertThat(SubOrderCompletion.publishIfCompleted(bus, sub(OrdSubOrder.FULFILLING))).isFalse();

        verifyNoInteractions(bus);
    }
}
