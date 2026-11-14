package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** Stable business error status; clients must not interpret a transport failure as absence. */
abstract class FoodStockInternalErrors {
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> businessFailure(BizException failure) {
        int status=switch(failure.errorCode()) {
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case PRICING_OWNER_UNSUPPORTED, ENTITLEMENT_FEATURE_UNAVAILABLE, RESOURCE_DEPENDENCY_UNAVAILABLE -> 503;
            default -> 500;
        };
        return ResponseEntity.status(status).build();
    }
}
