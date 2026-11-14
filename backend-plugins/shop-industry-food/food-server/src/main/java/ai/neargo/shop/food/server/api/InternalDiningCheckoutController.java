package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.DiningSettlementVO;
import ai.neargo.shop.food.service.DiningCheckoutService;
import ai.neargo.shop.food.spi.DiningCheckoutInternalApi;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalDiningCheckoutController {
    private final DiningCheckoutService service;
    public InternalDiningCheckoutController(DiningCheckoutService service) { this.service = service; }
    @PostMapping(DiningCheckoutInternalApi.ROOT + "/create") public DiningSettlementVO create(@RequestBody DiningCheckoutInternalApi.Create r) { return service.customerCreate(r.access(), r.batchNo(), r.request(), r.key()); }
    @PostMapping(DiningCheckoutInternalApi.ROOT + "/get") public DiningSettlementVO get(@RequestBody DiningCheckoutInternalApi.Reference r) { return service.customerGet(r.access(), r.settlementNo()); }
    @PostMapping(DiningCheckoutInternalApi.ROOT + "/pay") public DiningSettlementVO pay(@RequestBody DiningCheckoutInternalApi.Pay r) { return service.customerPay(r.access(), r.settlementNo(), r.request(), r.key()); }
    @PostMapping(DiningCheckoutInternalApi.ROOT + "/query") public DiningSettlementVO query(@RequestBody DiningCheckoutInternalApi.Reference r) { return service.customerQuery(r.access(), r.settlementNo()); }
    @PostMapping(DiningCheckoutInternalApi.ROOT + "/finalize") public DiningSettlementVO finalizeCheckout(@RequestBody DiningCheckoutInternalApi.Reference r) { return service.customerFinalize(r.access(), r.settlementNo()); }
    @PostMapping(DiningCheckoutInternalApi.ROOT + "/cancel") public DiningSettlementVO cancel(@RequestBody DiningCheckoutInternalApi.Cancel r) { return service.customerCancel(r.access(), r.settlementNo(), r.request()); }
    @PostMapping(DiningCheckoutInternalApi.ROOT + "/staff/get") public DiningSettlementVO staffGet(@RequestBody DiningCheckoutInternalApi.StaffGet r) { return service.staffGet(r.entity(), r.store(), r.actor(), r.settlementNo()); }
    @PostMapping(DiningCheckoutInternalApi.ROOT + "/recover-original")
    public DiningSettlementVO recoverOriginal(@RequestBody DiningCheckoutInternalApi.Original request) {
        return service.recoverOriginal(request.entity(), request.store(), request.number());
    }
    @ExceptionHandler(BizException.class) public ResponseEntity<Void> failure(BizException error) {
        int status = switch (error.errorCode()) {
            case BAD_REQUEST -> 400; case FORBIDDEN -> 403; case NOT_FOUND -> 404; case CONFLICT -> 409;
            case PRICING_OWNER_UNSUPPORTED -> 503; default -> 500;
        }; return ResponseEntity.status(status).build();
    }
}
