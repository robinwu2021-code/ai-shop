package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.service.DiningTransactionRecoveryService;
import ai.neargo.shop.food.spi.DiningTransactionRecoveryInternalApi;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Existing FOOD internal token filter protects a bounded, identity-free backend scanner request. */
@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalDiningTransactionRecoveryController {
    private final DiningTransactionRecoveryService recovery;
    public InternalDiningTransactionRecoveryController(DiningTransactionRecoveryService recovery) { this.recovery = recovery; }
    @PostMapping(DiningTransactionRecoveryInternalApi.PATH)
    public DiningTransactionRecoveryService.Report recover(@RequestBody DiningTransactionRecoveryInternalApi.Scan request) {
        return recovery.recoverDue(request.limit());
    }
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> businessFailure(BizException failure) {
        return ResponseEntity.status(failure.errorCode() == ErrorCode.BAD_REQUEST ? 400 : 503).build();
    }
}
