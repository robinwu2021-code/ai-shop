package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchantbridge.StoreRelinkPrecheck;
import ai.neargo.shop.merchant.service.StoreEntityClaimService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Read-only claim diagnostics; ownership mutation remains unavailable until its fences are implemented. */
@RestController
@Profile("api")
@Validated
public class BizStoreEntityController {
    private final StoreRelinkPrecheck checks;
    private final StoreEntityClaimService claims;
    public BizStoreEntityController(StoreRelinkPrecheck checks, StoreEntityClaimService claims) { this.checks=checks;this.claims=claims; }

    @GetMapping("/biz/store/{storeNo}/entity/relink-check")
    @PreAuthorize("@perm.canBiz('biz:store:admin')")
    public StoreRelinkPrecheck.Inspection check(
            @PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String storeNo,
            @RequestHeader("X-Store-No") @NotBlank String selected,
            @RequestParam @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String entityNo) {
        var user=BizAuthController.personalUser();
        if (!storeNo.equals(selected) || !storeNo.equals(BizContext.requireStoreNo()))
            throw BizException.of(ErrorCode.FORBIDDEN);
        return checks.inspect(user.tenantNo(),user.userNo(),BizContext.requireMerchantNo(),storeNo,entityNo);
    }
    public record ClaimRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String requestNo,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String entityNo,
            @NotNull @Min(0) Long expectedStoreVersion) {}
    @PostMapping("/biz/store/{storeNo}/entity")
    @PreAuthorize("@perm.canBiz('biz:store:admin')")
    public StoreEntityClaimService.View claim(@PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String storeNo,
            @RequestHeader("X-Store-No") @NotBlank String selected,@RequestBody @Valid ClaimRequest req) {
        var user=BizAuthController.personalUser();
        if(!storeNo.equals(selected)||!storeNo.equals(BizContext.requireStoreNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        return claims.create(user.tenantNo(),user.userNo(),BizContext.requireMerchantNo(),storeNo,
                new StoreEntityClaimService.Command(req.requestNo(),req.entityNo(),req.expectedStoreVersion()));
    }

}
