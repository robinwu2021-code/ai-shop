package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.DiningReturnVO;
import ai.neargo.shop.food.service.DiningReturnService;
import ai.neargo.shop.food.spi.DiningReturnInternalApi;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Existing FOOD internal token filter authenticates before parsing; original buyer/store checks remain mandatory. */
@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalDiningReturnController {
    private final DiningReturnService service;
    public InternalDiningReturnController(DiningReturnService service) { this.service = service; }
    @PostMapping(DiningReturnInternalApi.ROOT + "/customer/apply")
    public DiningReturnVO apply(@RequestBody DiningReturnInternalApi.CustomerApply r) {
        return service.customerApply(r.store(), r.access(), r.request(), r.key());
    }
    @PostMapping(DiningReturnInternalApi.ROOT + "/customer/get")
    public DiningReturnVO get(@RequestBody DiningReturnInternalApi.CustomerReference r) {
        return service.customerGet(r.access(), r.returnNo());
    }
    @PostMapping(DiningReturnInternalApi.ROOT + "/customer/cancel")
    public DiningReturnVO cancel(@RequestBody DiningReturnInternalApi.CustomerReference r) {
        return service.customerCancel(r.access(), r.returnNo());
    }
    @PostMapping(DiningReturnInternalApi.ROOT + "/staff/get")
    public DiningReturnVO staff(@RequestBody DiningReturnInternalApi.StaffReference r) {
        return service.staffGet(r.entity(), r.store(), r.actor(), r.returnNo());
    }
    @PostMapping(DiningReturnInternalApi.ROOT + "/recover-original")
    public DiningReturnVO recoverOriginal(@RequestBody DiningReturnInternalApi.Original request) {
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
