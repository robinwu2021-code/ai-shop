package ai.neargo.shop.scenario;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.invbridge.InventoryMirrorConsumer;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.marketing.attribution.FissionInviteService;
import ai.neargo.shop.marketing.attribution.FissionOutboxConsumer;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;

class OutboxHandlerFailureTest {
    @Test
    void reservationDatabaseFailureCannotBecomeSuccessfulDelivery() {
        var reservations=mock(ReservationService.class);
        doThrow(new IllegalStateException("database unavailable")).when(reservations).releaseByRef("R1");
        var consumer=new InventoryMirrorConsumer(null,reservations,null,null,new ObjectMapper());
        SysOutbox event=new SysOutbox();
        event.setEventType("INV_MIRROR_RELEASE");
        event.setPayload("{\"ref\":\"R1\"}");
        assertThatThrownBy(() -> consumer.consume(event)).isInstanceOf(IllegalStateException.class);
    }
    @Test
    void attributionFailureMustRemainRetryable() {
        var service=mock(FissionInviteService.class);
        doThrow(new IllegalStateException("database unavailable")).when(service).onFirstOrder("U1","O1");
        var consumer=new FissionOutboxConsumer(service,new ObjectMapper());
        SysOutbox event=new SysOutbox();event.setPayload("{\"userNo\":\"U1\",\"orderNo\":\"O1\"}");
        assertThatThrownBy(() -> consumer.consume(event)).isInstanceOf(IllegalStateException.class);
    }
    @Test
    void shippingMaterialNotReadyCannotBecomeSuccessfulDelivery() {
        var queue = mock(ai.neargo.shop.paybridge.ShippingUploadQueue.class);
        var resolver = mock(ai.neargo.shop.paybridge.WxShippingUploadResolver.class);
        var uploads = mock(ai.neargo.shop.paybridge.WxShippingUploadService.class);
        var channel = mock(ai.neargo.shop.spi.trade.WxShippingPort.class);
        var row = new ai.neargo.shop.trade.entity.TrdShippingUpload(); row.setId(1L); row.setStatus("PENDING");
        org.mockito.Mockito.when(channel.enabled()).thenReturn(true);
        org.mockito.Mockito.when(queue.byOrder("O1")).thenReturn(row);
        org.mockito.Mockito.when(resolver.resolve(row)).thenReturn(
                new ai.neargo.shop.paybridge.WxShippingUploadResolver.Material(null,null,null,null,"missing payer identity"));
        org.mockito.Mockito.when(uploads.statusOf(1L)).thenReturn("PENDING");
        SysOutbox event = new SysOutbox(); event.setPayload("{\"orderNo\":\"O1\"}");
        var consumer = new ai.neargo.shop.paybridge.WxShippingUploadImmediateConsumer(queue,resolver,uploads,channel);
        org.assertj.core.api.Assertions.assertThat(consumer.deliver(event).kind())
                .isEqualTo(ai.neargo.shop.event.outbox.DeliveryResult.Kind.RETRYABLE);
    }
}
