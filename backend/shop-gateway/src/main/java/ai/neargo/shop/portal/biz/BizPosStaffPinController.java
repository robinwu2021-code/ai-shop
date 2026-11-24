package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.merchant.service.PosStaffPinService;
import ai.neargo.shop.merchantbridge.impl.PosPinRecoveryService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Profile("api")
@RestController
public class BizPosStaffPinController {
    private final PosStaffPinService pins;

    private final PosPinRecoveryService recovery;
    private final AuditLogPort audit;

    public BizPosStaffPinController(PosStaffPinService pins, PosPinRecoveryService recovery, AuditLogPort audit) {
        this.pins = pins;
        this.recovery = recovery;
        this.audit = audit;
    }


    public record SavePin(@NotNull @Pattern(regexp = "[0-9]{4}") String newPin,
                          @Pattern(regexp = "[0-9]{4}") String currentPin) {}
    public record VerifyPin(@NotNull @Pattern(regexp = "[0-9]{4}") String pin) {}
    public record UpdatePolicy(@NotNull Boolean enabled) {}
    public record PinStatus(boolean available, boolean configured, boolean enabled, boolean canManagePolicy) {}

    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    @GetMapping("/biz/auth/pos-pin")
    public PinStatus status(@RequestHeader(BizContextFilter.STORE_HEADER) String store) {
        return pinStatus(scope(store));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PutMapping("/biz/auth/pos-pin/policy")
    public PinStatus setPolicy(@RequestHeader(BizContextFilter.STORE_HEADER) String store,
            @RequestBody @Valid UpdatePolicy body) {
        var scope = scope(store);
        pins.setEnabled(scope, body.enabled());
        audit.record("BIZ_POS_PIN_POLICY", scope.entity(), body.enabled() ? "ENABLED" : "DISABLED");
        return pinStatus(scope);
    }

    private PinStatus pinStatus(PosStaffPinService.Scope scope) {
        var status = pins.status(scope);
        return new PinStatus(status.available(), status.configured(), status.enabled(),
                BizContext.current().can(BizPerms.STORE_ADMIN));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    @PutMapping("/biz/auth/pos-pin")
    public PosStaffPinService.Result save(@RequestHeader(BizContextFilter.STORE_HEADER) String store,
                                          @RequestBody @Valid SavePin body) {
        var scope = scope(store);
        var result = pins.save(scope, body.newPin(), body.currentPin());
        audit.record(body.currentPin() == null ? "BIZ_POS_PIN_SET" : "BIZ_POS_PIN_CHANGE",
                scope.store() + "/" + scope.actor(), result.outcome().name());
        return result;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    @PostMapping("/biz/auth/pos-pin/verify")
    public PosStaffPinService.Result verify(@RequestHeader(BizContextFilter.STORE_HEADER) String store,
                                            @RequestBody @Valid VerifyPin body) {
        return pins.verify(scope(store), body.pin());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    @GetMapping("/biz/auth/pos-pin/recovery/methods")
    public java.util.List<PosPinRecoveryService.Method> methods(@RequestHeader(BizContextFilter.STORE_HEADER) String store) {
        return recovery.methods(scope(store), SecurityUtils.requireUser());
    }

    public record RecoveryChallenge(@NotNull PosPinRecoveryService.Method method) {}
    public record RecoveryProof(@NotBlank @Size(max = 1024) String credential) {}
    public record RecoveryReset(@NotBlank @Size(max = 64) String resetToken,
                                @NotNull @Pattern(regexp = "[0-9]{4}") String newPin) {}

    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    @PostMapping("/biz/auth/pos-pin/recovery/challenges")
    public PosPinRecoveryService.Challenge challenge(@RequestHeader(BizContextFilter.STORE_HEADER) String store,
            @RequestBody @Valid RecoveryChallenge body, HttpServletRequest http) {
        return recovery.challenge(scope(store), SecurityUtils.requireUser(), token(), body.method(), http.getRemoteAddr());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    @PostMapping("/biz/auth/pos-pin/recovery/challenges/{challengeId}/verify")
    public PosPinRecoveryService.Proof proof(@RequestHeader(BizContextFilter.STORE_HEADER) String store,
            @PathVariable String challengeId, @RequestBody @Valid RecoveryProof body, HttpServletRequest http) {
        return recovery.verify(scope(store), SecurityUtils.requireUser(), token(), challengeId, body.credential(), http.getRemoteAddr());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    @PutMapping("/biz/auth/pos-pin/recovery")
    public PosStaffPinService.Result reset(@RequestHeader(BizContextFilter.STORE_HEADER) String store,
                                            @RequestBody @Valid RecoveryReset body) {
        var scope = scope(store);
        var result = recovery.reset(scope, token(), body.resetToken(), body.newPin());
        audit.record("BIZ_POS_PIN_RESET", scope.store() + "/" + scope.actor(), result.outcome().name());
        return result;
    }

    private static String token() { return SecurityUtils.currentToken().orElseThrow(() -> BizException.of(ErrorCode.TOKEN_EXPIRED)); }

    private PosStaffPinService.Scope scope(String store) {
        if (!store.equals(BizContext.requireStoreNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        var actor = SecurityUtils.requireUser();
        return new PosStaffPinService.Scope(actor.tenantNo(), BizContext.requireMerchantNo(),
                BizContext.requireStoreAccess(store), actor.subjectKind().name(), actor.userNo());
    }
}
