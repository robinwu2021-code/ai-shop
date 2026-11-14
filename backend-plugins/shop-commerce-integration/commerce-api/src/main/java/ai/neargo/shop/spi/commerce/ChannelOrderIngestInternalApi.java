package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Accepted;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Cancelled;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Command;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Confirmation;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Prepared;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Reference;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Private original industry transport; local deployment binds the identical owner port directly.
 */
public interface ChannelOrderIngestInternalApi {
    record Cancel(Reference reference, String reason) {}

    @PostExchange("/internal/food/channel-orders/prepare")
    Prepared prepare(@RequestBody Command command);

    @PostExchange("/internal/food/channel-orders/get")
    Prepared get(@RequestBody Reference reference);

    @PostExchange("/internal/food/channel-orders/confirm")
    Accepted confirm(@RequestBody Confirmation command);

    @PostExchange("/internal/food/channel-orders/cancel")
    Cancelled cancel(@RequestBody Cancel command);
}
