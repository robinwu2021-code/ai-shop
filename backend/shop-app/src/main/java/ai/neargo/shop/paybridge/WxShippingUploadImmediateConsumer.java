package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.outbox.RetryableOutboxException;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.trade.WxShippingPort;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Immediate upload after queue commit; the ten-minute job repairs failures and older rows. */
@Component
public class WxShippingUploadImmediateConsumer implements OutboxConsumer {
    private final ShippingUploadQueue queue;
    private final WxShippingUploadResolver resolver;
    private final WxShippingUploadService uploads;
    private final WxShippingPort channel;
    private final ObjectMapper json = new ObjectMapper();

    public WxShippingUploadImmediateConsumer(ShippingUploadQueue queue, WxShippingUploadResolver resolver,
                                             WxShippingUploadService uploads, WxShippingPort channel) {
        this.queue = queue;
        this.resolver = resolver;
        this.uploads = uploads;
        this.channel = channel;
    }

    @Override
    public DeliveryResult deliver(SysOutbox event) {
        if (!channel.enabled()) { return DeliveryResult.failed("NOT_CONFIGURED", "WeChat shipping disabled"); }
        consume(event);
        var row = queue.byOrder(json.readTree(event.getPayload()).path("orderNo").asString(""));
        String status = row == null ? null : uploads.statusOf(row.getId());
        if (TrdShippingUpload.FAILED.equals(status)) {
            return DeliveryResult.failed("UPLOAD_FAILED", "See shipping upload action state");
        }
        if (TrdShippingUpload.PENDING.equals(status)) {
            return DeliveryResult.retry("UPLOAD_NOT_READY", "See shipping upload material blocker");
        }
        return DeliveryResult.sent();
    }

    @Override public String handlerId() { return "wx-shipping-upload-immediate"; }
    @Override public boolean externalWorkflow() { return true; }

    @Override public boolean supports(String type) { return WxShippingUploadRequested.TYPE.equals(type); }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void consume(SysOutbox event) {
        if (!channel.enabled()) return;
        String orderNo = json.readTree(event.getPayload()).path("orderNo").asString("");
        if (orderNo.isBlank()) return;
        TrdShippingUpload row = queue.byOrder(orderNo);
        if (row == null || !TrdShippingUpload.PENDING.equals(row.getStatus())) return;
        WxShippingUploadResolver.Material material = resolver.resolve(row);
        if (!material.ready()) {
            uploads.blocked(row, material.missReason());
            return;
        }
        uploads.upload(row, material.itemDesc(), material.trackingNo(),
                material.expressCompany(), material.payerOpenid());
        if (TrdShippingUpload.PENDING.equals(uploads.statusOf(row.getId()))) {
            throw new RetryableOutboxException("WeChat shipping rejected; retryable");
        }
    }
}
