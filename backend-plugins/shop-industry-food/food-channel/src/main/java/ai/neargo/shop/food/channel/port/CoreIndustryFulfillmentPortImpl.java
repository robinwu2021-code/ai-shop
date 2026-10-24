package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.IndustryFulfillmentInternalApi;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.Set;
import java.util.function.Supplier;

/** Exact original reference and operation identity survive standalone FOOD RPC retries. */
public class CoreIndustryFulfillmentPortImpl implements IndustryFulfillmentPort {
    private final Supplier<IndustryFulfillmentInternalApi> api;
    public CoreIndustryFulfillmentPortImpl(Supplier<IndustryFulfillmentInternalApi> api) { this.api=api; }
    @Override public Receipt verifyPickup(VerifyPickup c,String key) {
        return invoke(c.reference(),() -> api.get().verifyPickup(new IndustryFulfillmentInternalApi.Verify(c,key)));
    }
    @Override public Receipt query(Reference ref,String actor,String key) {
        return invoke(ref,() -> api.get().query(new IndustryFulfillmentInternalApi.Query(ref,actor,key)));
    }
    private Receipt invoke(Reference ref,Supplier<Receipt> call) {
        try {
            var result=ServiceCalls.call(ServiceName.PLATFORM,call);
            if (result==null || !ref.equals(result.reference()) || result.status()==null
                    || !Set.of("NOT_STARTED","NOT_READY_CODE","PREPARED","UNKNOWN","VERIFIED",
                    "BUYER_CONFIRM_PENDING").contains(result.status()) || result.subOrderVersion()<0
                    || result.inventoryLifecycleVersion()<2 || result.inventoryLifecycleVersion()>3) invalid();
            if (!result.pending() && (result.fulfillmentNo()==null || result.inventoryReservationNo()==null
                    || !("COMMITTED".equals(result.inventoryState()) || "ISSUED_UNPAID".equals(result.inventoryState()))
                    || "VERIFIED".equals(result.status()) && !"COMPLETED".equals(result.subOrderStatus())
                    || "BUYER_CONFIRM_PENDING".equals(result.status()) && (!result.requireBuyerConfirm()
                    || !"FULFILLING".equals(result.subOrderStatus())))) invalid();
            return result;
        } catch (ServiceCallException failure) {
            throw switch(failure.statusCode()) {
                case 400 -> BizException.of(ErrorCode.BAD_REQUEST);
                case 403 -> BizException.of(ErrorCode.FORBIDDEN);
                case 404 -> BizException.of(ErrorCode.NOT_FOUND);
                case 409 -> BizException.of(ErrorCode.CONFLICT);
                case 503 -> BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
                default -> failure;
            };
        }
    }
    private static void invalid() { throw new IllegalStateException("Invalid original pickup fulfillment receipt"); }
}
