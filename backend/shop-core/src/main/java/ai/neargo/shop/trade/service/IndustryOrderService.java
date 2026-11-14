package ai.neargo.shop.trade.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Result;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

public interface IndustryOrderService {
    Result acceptUnpaid(Identity identity, String orderNo);
    Result placeForStore(Command command, String key);
    /** Older implementations cannot silently route a dining-room order through generic pickup. */
    default Result placeForDiningRoom(Command command, String key) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
    default Result placeForService(Command command, String key, String fulfillment, boolean staff) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
    Result queryForStore(Identity identity, String key);
    Result place(Command command, String key);
    Result cancel(Command command, String key);
    Result cancelForStore(Command command, String key, String fulfillment);
    Result query(Identity identity, String key);
    Result get(Identity identity, String orderNo);
    Result getForStore(Scope scope, String actorNo, String orderNo);
}
