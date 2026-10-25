package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.shop.spi.printing.PrintPort.Acknowledge;
import ai.neargo.shop.spi.printing.PrintPort.Claim;
import ai.neargo.shop.spi.printing.PrintPort.DeviceReq;
import ai.neargo.shop.spi.printing.PrintPort.DeviceVO;
import ai.neargo.shop.spi.printing.PrintPort.JobVO;
import ai.neargo.shop.spi.printing.PrintPort.Scope;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A hardware bridge uses a bound authenticated actor; knowing a device number never grants delivery
 * access.
 */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizPrintDeviceController {
    private final PrintPort printing;
    private final FoodBizStoreScope stores;

    public BizPrintDeviceController(
            PrintPort printing, StoreDirectoryPort directory, FoodRequestScopeValidator validator) {
        this.printing = printing;
        this.stores = new FoodBizStoreScope(directory, validator);
    }

    @PostMapping("/biz/x/food/print-devices")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    public DeviceVO bind(@Valid @RequestBody DeviceReq req, HttpServletRequest request) {
        return printing.bindDevice(scope(request), req);
    }

    public record RevokeReq(@NotBlank @Size(max = 255) String reason) {}

    @PostMapping("/biz/x/food/print-devices/{deviceNo}:revoke")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    public DeviceVO revoke(
            @PathVariable("deviceNo") @NotBlank @Size(max = 32) String no,
            @Valid @RequestBody RevokeReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        return printing.revokeDevice(scope(request), no, req.reason(), key);
    }

    public record HeartbeatReq(boolean online) {}

    @PostMapping("/biz/x/food/print-devices/{deviceNo}:heartbeat")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public DeviceVO heartbeat(
            @PathVariable("deviceNo") @NotBlank @Size(max = 32) String no,
            @Valid @RequestBody HeartbeatReq req,
            HttpServletRequest request) {
        return printing.heartbeat(scope(request), no, req.online());
    }

    @PostMapping("/biz/x/food/print-devices/{deviceNo}:claim")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public Claim claim(
            @PathVariable("deviceNo") @NotBlank @Size(max = 32) String no,
            HttpServletRequest request) {
        return printing.claim(scope(request), no);
    }

    @PostMapping("/biz/x/food/print-devices/{deviceNo}:acknowledge")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public JobVO acknowledge(
            @PathVariable("deviceNo") @NotBlank @Size(max = 32) String no,
            @Valid @RequestBody Acknowledge req,
            HttpServletRequest request) {
        return printing.acknowledge(scope(request), no, req);
    }

    private Scope scope(HttpServletRequest request) {
        var context = stores.current(request);
        return new Scope(
                context.merchantNo(), context.currentStoreNo(), SecurityUtils.currentUserNo());
    }
}
