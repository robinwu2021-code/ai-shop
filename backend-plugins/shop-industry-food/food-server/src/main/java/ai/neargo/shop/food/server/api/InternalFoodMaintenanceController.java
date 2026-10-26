package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.service.FoodMaintenanceService;
import ai.neargo.shop.food.spi.FoodMaintenanceInternalApi;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Existing FOOD internal token filter protects a bounded, identity-free maintenance trigger from the platform job runtime. */
@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalFoodMaintenanceController {
    private final FoodMaintenanceService maintenance;
    public InternalFoodMaintenanceController(FoodMaintenanceService maintenance) { this.maintenance = maintenance; }
    @PostMapping(FoodMaintenanceInternalApi.PATH)
    public FoodMaintenanceService.Report run(@RequestBody FoodMaintenanceInternalApi.Run request) {
        return maintenance.run(request.task(), request.limit());
    }
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> businessFailure(BizException failure) {
        return ResponseEntity.status(failure.errorCode() == ErrorCode.BAD_REQUEST ? 400 : 503).build();
    }
}
