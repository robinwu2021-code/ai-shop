package ai.neargo.shop.portal.mp;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.ActivityBenefitCoordinator;
import ai.neargo.shop.paybridge.ActivityBenefitCoordinator.ClaimView;
import ai.neargo.shop.promotion.dto.BenefitClaimCommand;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Claim identity always comes from the consumer session, never an arbitrary request recipient. */
@Profile("api")
@RestController
public class MpEntitlementController {
    private final ActivityBenefitCoordinator benefits;
    public MpEntitlementController(ActivityBenefitCoordinator benefits) {
        this.benefits = benefits;
    }
    @PostMapping("/mp/entitlement-claims")
    public ClaimReceipt claim(@RequestBody BenefitClaimCommand command, @RequestHeader("Idempotency-Key") String key) {
        return receipt(benefits.claim(context(key, command.toString()), command, key));
    }
    @GetMapping("/mp/entitlement-claims/{intentNo}")
    public ClaimReceipt query(@PathVariable String intentNo) {
        return receipt(benefits.query(context("query-claim", intentNo), intentNo));
    }
    private EntitlementContext context(String request, String payload) {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.CONSUMER) throw BizException.of(ErrorCode.FORBIDDEN);
        // Context IDs are bounded independently from the public idempotency key (up to 128 characters).
        return new EntitlementContext(user.tenantNo(), user.userNo(),
                ActivityCommandDeduplicator.hash(request), ActivityCommandDeduplicator.hash(payload));
    }
    private ClaimReceipt receipt(ClaimView view) {
        return new ClaimReceipt(view.intentNo(), view.status(), view.grants().stream()
                .flatMap(g -> g.instanceNos().stream()).toList());
    }
    public record ClaimReceipt(String intentNo, String status, List<String> instanceNos) {
        public ClaimReceipt { instanceNos = List.copyOf(instanceNos); }
    }
}
