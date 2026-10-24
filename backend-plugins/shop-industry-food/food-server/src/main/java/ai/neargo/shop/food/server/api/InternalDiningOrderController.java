package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.DiningOrderBatchVO;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.food.spi.DiningOrderInternalApi;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Internal token filter runs before command parsing; caller still needs original cart credentials. */
@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalDiningOrderController {
    private final DiningOrderService service;
    public InternalDiningOrderController(DiningOrderService service) { this.service = service; }
    @PostMapping(DiningOrderInternalApi.ROOT + "/authorize-cancellation")
    public Snapshot authorizeCancellation(@RequestBody DiningOrderInternalApi.CancellationSource request) {
        return service.authorizeCancellation(request.command(), request.key(), request.fulfillment());
    }
    @PostMapping(DiningOrderInternalApi.ROOT + "/staff/cancel")
    public DiningOrderBatchVO staffCancel(@RequestBody DiningOrderInternalApi.StaffCancel request) {
        return service.staffCancel(request.entity(), request.store(), request.actor(), request.batchNo(),
                request.request(), request.key());
    }
    @PostMapping(DiningOrderInternalApi.ROOT + "/staff/place")
    public DiningOrderBatchVO staffPlace(@RequestBody DiningOrderInternalApi.StaffPlace request) {
        return service.staffPlace(request.entity(), request.store(), request.actor(), request.request(), request.key());
    }
    @PostMapping(DiningOrderInternalApi.ROOT + "/place")
    public DiningOrderBatchVO place(@RequestBody DiningOrderInternalApi.Place r) {
        return service.customerPlace(r.store(), r.access(), r.request(), r.key());
    }
    @PostMapping(DiningOrderInternalApi.ROOT + "/customer/get")
    public DiningOrderBatchVO customerGet(@RequestBody DiningOrderInternalApi.CustomerGet r) {
        return service.customerGet(r.access(), r.batchNo());
    }
    @PostMapping(DiningOrderInternalApi.ROOT + "/staff/get")
    public DiningOrderBatchVO staffGet(@RequestBody DiningOrderInternalApi.StaffGet r) {
        return service.staffGet(r.entity(), r.store(), r.actor(), r.batchNo());
    }
    @PostMapping(DiningOrderInternalApi.ROOT + "/recover-original")
    public DiningOrderBatchVO recoverOriginal(@RequestBody DiningOrderInternalApi.Original request) {
        return service.recoverOriginal(request.entity(), request.store(), request.number());
    }
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> businessFailure(BizException failure) {
        int status = switch (failure.errorCode()) {
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case PRICING_OWNER_UNSUPPORTED -> 503;
            default -> 500;
        };
        return ResponseEntity.status(status).build();
    }
}
