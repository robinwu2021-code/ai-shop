package ai.neargo.shop.portal.biz;

import ai.neargo.shop.merchant.service.StoreEntityClaimService;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Account recovery never depends on the old store/entity selection. Ownership is checked by the service. */
@RestController
@Profile("api")
@Validated
public class BizStoreClaimRecoveryController {
    private final StoreEntityClaimService claims;
    public BizStoreClaimRecoveryController(StoreEntityClaimService claims) {this.claims=claims;}
    @GetMapping("/biz/merchant/store-claims/by-request/{requestNo}")
    public StoreEntityClaimService.View byRequest(@PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String requestNo) {
        var user=BizAuthController.personalUser();return claims.byRequest(user.tenantNo(),user.userNo(),requestNo);
    }
    @GetMapping("/biz/merchant/store-claims/{claimNo}")
    public StoreEntityClaimService.View get(@PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String claimNo) {
        var user=BizAuthController.personalUser();return claims.byNo(user.tenantNo(),user.userNo(),claimNo);
    }
    @PostMapping("/biz/merchant/store-claims/{claimNo}/resume")
    public StoreEntityClaimService.View resume(@PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String claimNo) {
        var user=BizAuthController.personalUser();return claims.resume(user.tenantNo(),user.userNo(),claimNo);
    }
}
