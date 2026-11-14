package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.IndustryFulfillmentPort;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Receipt;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.VerifyPickup;

/** Original trade owner retains one durable pickup issue and verification journal. */
public interface IndustryFulfillmentService {
    int recover(int limit);

    Receipt fulfill(IndustryFulfillmentPort.Fulfill command, String key);

    Receipt verifyPickup(VerifyPickup command, String key);

    Receipt query(Reference reference, String actorNo, String key);
}
