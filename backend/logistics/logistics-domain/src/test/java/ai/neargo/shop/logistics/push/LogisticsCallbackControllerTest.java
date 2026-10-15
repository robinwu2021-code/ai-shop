package ai.neargo.shop.logistics.push;

import ai.neargo.shop.logistics.api.callback.LogisticsCallbackController;
import ai.neargo.shop.logistics.capability.PushReceiver;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LogisticsCallbackControllerTest {
    @Test
    void databaseFailureRequestsChannelRetry() {
        ChannelRouter router = mock(ChannelRouter.class);
        PushReceiver receiver = mock(PushReceiver.class);
        PushIngestion ingestion = mock(PushIngestion.class);
        when(router.receiver("kuaidi100")).thenReturn(Optional.of(receiver));
        when(receiver.parse(any())).thenReturn(new PushReceiver.Parsed(true, "shentong", "STO123",
                null, false, null, "cb-1"));
        when(ingestion.ingest(eq("kuaidi100"), any(), anyString()))
                .thenThrow(new IllegalStateException("database unavailable"));

        String ack = new LogisticsCallbackController(router, ingestion)
                .push("kuaidi100", Map.of("callbackId", "cb-1"), "payload");

        assertThat(ack).contains("\"result\":false").contains("\"returnCode\":\"500\"");
    }
}
