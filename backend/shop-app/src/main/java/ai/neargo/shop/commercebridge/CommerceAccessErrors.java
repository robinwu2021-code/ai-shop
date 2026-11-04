package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.access.*;
import ai.neargo.shop.common.ApiResult;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class CommerceAccessErrors {
    @ExceptionHandler(AccessDenied.class)
    ResponseEntity<ApiResult<Map<String, String>>> denied(AccessDenied failure) {
        var status = failure.status();
        return ResponseEntity.status(ShopCommerceEndContextAdapter.http(status))
                .body(
                        new ApiResult<>(
                                ShopCommerceEndContextAdapter.errorCode(status),
                                status.name(),
                                Map.of("nextAction", failure.nextAction())));
    }
}
