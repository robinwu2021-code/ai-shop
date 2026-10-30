package ai.neargo.shop.food.foodclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.food.spi.FoodStockSyncInternalApi;
import ai.neargo.shop.spi.food.FoodOutboxRouting;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.shop.svc.ServiceName;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RemoteFoodStockSyncDispatcherTest {
    static final OutboxEnvelope EVENT=new OutboxEnvelope("EVENT","PLATFORM","EVENT","FOOD_STOCK_SYNC","SYNC",
            "FOOD_STOCK_SYNC_REQUESTED",1,"FOOD_STOCK_SYNC:S",1,
            "{\"syncNo\":\"SYNC\",\"entityNo\":\"E\",\"storeNo\":\"S\",\"generation\":1,\"action\":\"ACTIVATE\",\"payloadHash\":\"HASH\"}",Instant.parse("2026-10-30T00:00:00Z"));
    @Test void forwardsOriginalEnvelopeAndRetryResultWithoutChangingIdentity() {
        var api=mock(FoodStockSyncInternalApi.class);
        var command=new FoodStockSyncInternalApi.Dispatch("E","S",EVENT);
        var result=DeliveryResult.retry("BASELINE_PENDING","resume cursor");
        when(api.dispatch(command)).thenReturn(result);
        var handler=new RemoteFoodStockSyncDispatcher(api,JsonMapper.builder().build());
        assertThat(handler.handle(EVENT)).isSameAs(result);
        assertThat(handler.handlerId()).isEqualTo(FoodOutboxRouting.STOCK_SYNC_HANDLER);
        assertThat(handler.externalWorkflow()).isTrue();
        assertThat(FoodOutboxRouting.DELIVERY_TARGETS.get(EVENT.eventType())).containsEntry(handler.handlerId(),true);
        verify(api).dispatch(command);
    }
    @Test void uncertainTransportRetriesOriginalDurableIntent() {
        var api=mock(FoodStockSyncInternalApi.class);
        when(api.dispatch(any())).thenThrow(new ServiceCallException(CallOutcome.NOT_CONFIGURED,ServiceName.FOOD,0,"missing"));
        var result=new RemoteFoodStockSyncDispatcher(api,JsonMapper.builder().build()).handle(EVENT);
        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.RETRYABLE);
        assertThat(result.errorCode()).isEqualTo("FOOD_DISPATCH_UNAVAILABLE");
    }
}
