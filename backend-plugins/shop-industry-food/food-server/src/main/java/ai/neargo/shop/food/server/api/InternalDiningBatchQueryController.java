package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.DiningBatchSummaryVO;
import ai.neargo.shop.food.service.DiningBatchQueryService;
import ai.neargo.shop.food.spi.DiningBatchQueryInternalApi;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Existing internal token security precedes parsing and the original store authority still validates entity ownership. */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalDiningBatchQueryController {
    private final DiningBatchQueryService service;
    public InternalDiningBatchQueryController(DiningBatchQueryService service) { this.service=service; }
    @PostMapping(DiningBatchQueryInternalApi.LIST_PATH)
    public PageData<DiningBatchSummaryVO> list(@Valid @RequestBody DiningBatchQueryInternalApi.Listing request) {
        return service.list(request.entityNo(),request.storeNo(),request.actorNo(),request.query());
    }
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> businessFailure(BizException failure) {
        int status=switch(failure.errorCode()) {
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            default -> 500;
        };
        return ResponseEntity.status(status).build();
    }
}
