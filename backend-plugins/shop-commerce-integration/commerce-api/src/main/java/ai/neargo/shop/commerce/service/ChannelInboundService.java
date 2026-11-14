package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.InboundContracts;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.commerce.ChannelInboundGateway.Ack;
import ai.neargo.shop.spi.commerce.ChannelInboundGateway.RawMessage;

/** A protocol ACK is returned only after the verified original event and receipt commit. */
public interface ChannelInboundService {
    Ack receive(String providerCode, String endpointNo, RawMessage message);

    PageData<InboundContracts.Event> listEvents(
            String entity, String store, InboundContracts.Query query);

    PageData<InboundContracts.Receipt> listReceipts(
            String entity, String store, String eventNo, int page, int size);

    InboundContracts.Event recover(
            String entity,
            String store,
            String eventNo,
            String actor,
            InboundContracts.Recovery request,
            String key);
}
