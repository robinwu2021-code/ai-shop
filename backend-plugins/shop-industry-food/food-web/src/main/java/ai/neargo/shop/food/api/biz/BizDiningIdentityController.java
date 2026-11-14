package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceCredentialVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.PartyVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.RegisterDeviceReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.SessionCredentialVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.StaffPartyReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.VersionReq;
import ai.neargo.shop.food.service.DiningIdentityService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keeps the BizDiningIdentityController protocol scoped to original restaurant facts and lifecycle
 * authority.
 */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
@Validated
public class BizDiningIdentityController {
    private final DiningIdentityService service;
    private final FoodBizStoreScope scope;

    public BizDiningIdentityController(
            DiningIdentityService service,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        this.service = service;
        this.scope = new FoodBizStoreScope(stores, validator);
    }

    @PostMapping("/biz/x/food/dining-devices")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    public DeviceCredentialVO register(
            @Valid @RequestBody RegisterDeviceReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        var c = scope.current(request);
        return service.registerDevice(
                c.merchantNo(), c.currentStoreNo(), SecurityUtils.currentUserNo(), req, key);
    }

    @GetMapping("/biz/x/food/dining-devices/{deviceNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    public DeviceVO device(
            @PathVariable @NotBlank @Size(max = 32) String deviceNo, HttpServletRequest request) {
        var c = scope.current(request);
        return service.device(c.merchantNo(), c.currentStoreNo(), deviceNo);
    }

    @PostMapping("/biz/x/food/dining-devices/{deviceNo}:revoke")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    public DeviceVO revokeDevice(
            @PathVariable @NotBlank @Size(max = 32) String deviceNo,
            @Valid @RequestBody VersionReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        var c = scope.current(request);
        return service.revokeDevice(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                deviceNo,
                req,
                key);
    }

    @PostMapping("/biz/x/food/parties")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public SessionCredentialVO createParty(
            @Valid @RequestBody StaffPartyReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        var c = scope.current(request);
        return service.createStaffParty(
                c.merchantNo(), c.currentStoreNo(), SecurityUtils.currentUserNo(), req, key);
    }

    @GetMapping("/biz/x/food/parties/{sessionNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public PartyVO party(
            @PathVariable @NotBlank @Size(max = 32) String sessionNo, HttpServletRequest request) {
        var c = scope.current(request);
        return service.party(c.merchantNo(), c.currentStoreNo(), sessionNo);
    }

    @PostMapping("/biz/x/food/parties/{sessionNo}:revoke")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public PartyVO revokeParty(
            @PathVariable @NotBlank @Size(max = 32) String sessionNo,
            @Valid @RequestBody VersionReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        var c = scope.current(request);
        return service.revokeParty(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                sessionNo,
                req,
                key);
    }
}
