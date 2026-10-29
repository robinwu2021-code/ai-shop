package ai.neargo.shop.food.server.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts;
import ai.neargo.shop.food.service.ServiceEntitlementService;
import ai.neargo.shop.food.spi.ServiceEntitlementInternalApi;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The existing FOOD service-token and exact signed scope filters protect these original service
 * operations.
 */
@RestController
@Validated
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
@ConditionalOnProperty(name = "shop.food.service-entitlements.enabled", havingValue = "true")
public class InternalServiceEntitlementController {
    private final ServiceEntitlementService service;

    public InternalServiceEntitlementController(ServiceEntitlementService service) {
        this.service = service;
    }

    @PostMapping("/internal/food/service-entitlements/grant")
    public ServiceEntitlementContracts.Entitlement grant(
            @Valid @RequestBody ServiceEntitlementInternalApi.GrantCommand c) {
        return service.grant(c.entity(), c.store(), c.actor(), c.formNo(), c.request(), c.key());
    }

    @PostMapping("/internal/food/service-entitlements/consume")
    public ServiceEntitlementContracts.Entitlement consume(
            @Valid @RequestBody ServiceEntitlementInternalApi.ConsumeCommand c) {
        return service.consume(
                c.entity(), c.store(), c.actor(), c.entitlementNo(), c.request(), c.key());
    }

    @PostMapping("/internal/food/service-entitlements/extend")
    public ServiceEntitlementContracts.ExtensionReceipt extend(
            @Valid @RequestBody ServiceEntitlementInternalApi.ExtensionCommand c) {
        return service.extend(c.entity(), c.store(), c.actor(), c.formNo(), c.request(), c.key());
    }

    @PostMapping("/internal/food/service-entitlements/banquet-plan")
    public ServiceEntitlementContracts.PlanRevision revisePlan(
            @Valid @RequestBody ServiceEntitlementInternalApi.PlanCommand c) {
        return service.revisePlan(
                c.entity(), c.store(), c.actor(), c.formNo(), c.request(), c.key());
    }

    @PostMapping("/internal/food/service-entitlements/confirm-attendance")
    public ServiceEntitlementContracts.AttendanceConfirmation confirmAttendance(
            @Valid @RequestBody ServiceEntitlementInternalApi.ConfirmCommand c) {
        return service.confirmAttendance(
                c.entity(), c.store(), c.actor(), c.formNo(), c.request(), c.key());
    }

    @PostMapping("/internal/food/service-entitlements/list")
    public List<ServiceEntitlementContracts.Entitlement> list(
            @Valid @RequestBody ServiceEntitlementInternalApi.ListCommand c) {
        return service.entitlements(c.entity(), c.store(), c.formNo());
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Void> failure(BizException failure) {
        int code =
                switch (failure.errorCode()) {
                    case BAD_REQUEST -> 400;
                    case FORBIDDEN -> 403;
                    case NOT_FOUND -> 404;
                    case CONFLICT -> 409;
                    case PRICING_OWNER_UNSUPPORTED, ENTITLEMENT_FEATURE_UNAVAILABLE -> 503;
                    default -> 500;
                };
        return ResponseEntity.status(code).build();
    }
}
