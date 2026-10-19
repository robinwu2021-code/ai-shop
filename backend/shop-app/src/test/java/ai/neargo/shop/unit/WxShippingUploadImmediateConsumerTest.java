package ai.neargo.shop.unit;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.paybridge.ShippingUploadQueue;
import ai.neargo.shop.paybridge.WxShippingUploadImmediateConsumer;
import ai.neargo.shop.paybridge.WxShippingUploadResolver;
import ai.neargo.shop.paybridge.WxShippingUploadService;
import ai.neargo.shop.spi.trade.WxShippingPort;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WxShippingUploadImmediateConsumerTest {
    @Test
    void uploadsQueuedShipmentImmediatelyWhenMaterialsAreReady() {
        ShippingUploadQueue queue = mock(ShippingUploadQueue.class);
        WxShippingUploadResolver resolver = mock(WxShippingUploadResolver.class);
        WxShippingUploadService uploads = mock(WxShippingUploadService.class);
        WxShippingPort channel = mock(WxShippingPort.class);
        TrdShippingUpload row = new TrdShippingUpload();
        row.setOrderNo("O1");
        row.setStatus(TrdShippingUpload.PENDING);
        when(channel.enabled()).thenReturn(true);
        when(queue.byOrder("O1")).thenReturn(row);
        when(resolver.resolve(row)).thenReturn(new WxShippingUploadResolver.Material(
                "茶叶", "YT1", "YTO", "buyer", null));
        SysOutbox event = new SysOutbox();
        event.setEventType("WX_SHIPPING_UPLOAD_REQUESTED");
        event.setPayload("{\"orderNo\":\"O1\"}");

        new WxShippingUploadImmediateConsumer(queue, resolver, uploads, channel).consume(event);

        verify(uploads).upload(row, "茶叶", "YT1", "YTO", "buyer");
    }
}
