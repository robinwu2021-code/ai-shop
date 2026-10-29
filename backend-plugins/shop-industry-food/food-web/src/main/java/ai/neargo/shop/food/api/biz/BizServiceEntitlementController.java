package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts;
import ai.neargo.shop.food.service.ServiceEntitlementService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Staff operational quotas and banquet planning use the same trusted active-store and permission
 * boundary as original service admissions.
 */
@RestController
@Validated
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
@ConditionalOnProperty(name = "shop.food.service-entitlements.enabled", havingValue = "true")
public class BizServiceEntitlementController {
    private final ServiceEntitlementService service;
    private final FoodBizStoreScope scope;

    public BizServiceEntitlementController(
            ServiceEntitlementService service,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        this.service = service;
        scope = new FoodBizStoreScope(stores, validator);
    }

    public record GrantRequest(
            @NotBlank String formNo, @Valid @NotNull ServiceEntitlementContracts.Grant request) {}

    @PostMapping("/biz/x/food/service-entitlements/grant")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceEntitlementContracts.Entitlement grant(
            @Valid @RequestBody GrantRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return service.grant(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.formNo(),
                r.request(),
                key);
    }

    public record ConsumeRequest(
            @NotBlank String entitlementNo,
            @Valid @NotNull ServiceEntitlementContracts.Consume request) {}

    @PostMapping("/biz/x/food/service-entitlements/consume")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceEntitlementContracts.Entitlement consume(
            @Valid @RequestBody ConsumeRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return service.consume(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.entitlementNo(),
                r.request(),
                key);
    }

    public record ExtensionRequest(
            @NotBlank String formNo,
            @Valid @NotNull ServiceEntitlementContracts.Extension request) {}

    @PostMapping("/biz/x/food/service-entitlements/extend")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceEntitlementContracts.ExtensionReceipt extend(
            @Valid @RequestBody ExtensionRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return service.extend(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.formNo(),
                r.request(),
                key);
    }

    public record PlanRequest(
            @NotBlank String formNo, @Valid @NotNull ServiceEntitlementContracts.Plan request) {}

    @PostMapping("/biz/x/food/service-entitlements/banquet-plan")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceEntitlementContracts.PlanRevision revisePlan(
            @Valid @RequestBody PlanRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return service.revisePlan(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.formNo(),
                r.request(),
                key);
    }

    public record ConfirmRequest(
            @NotBlank String formNo,
            @Valid @NotNull ServiceEntitlementContracts.ConfirmAttendance request) {}

    @PostMapping("/biz/x/food/service-entitlements/confirm-attendance")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public ServiceEntitlementContracts.AttendanceConfirmation confirmAttendance(
            @Valid @RequestBody ConfirmRequest r,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return service.confirmAttendance(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                r.formNo(),
                r.request(),
                key);
    }

    public record ListRequest(@NotBlank String formNo) {}

    @PostMapping("/biz/x/food/service-entitlements/list")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public List<ServiceEntitlementContracts.Entitlement> list(
            @Valid @RequestBody ListRequest r, HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var c = scope.current(http);
        return service.entitlements(c.merchantNo(), c.currentStoreNo(), r.formNo());
    }
}
