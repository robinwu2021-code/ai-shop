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
 * Retail transport is deliberately separate from FOOD routes and always addresses the original
 * PLATFORM retail owner.
 */
public interface RetailChannelOrderIngestInternalApi {
    record Cancel(Reference reference, String reason) {}

    @PostExchange("/internal/retail/channel-orders/prepare")
    Prepared prepare(@RequestBody Command command);

    @PostExchange("/internal/retail/channel-orders/get")
    Prepared get(@RequestBody Reference reference);

    @PostExchange("/internal/retail/channel-orders/confirm")
    Accepted confirm(@RequestBody Confirmation command);

    @PostExchange("/internal/retail/channel-orders/cancel")
    Cancelled cancel(@RequestBody Cancel command);
}
