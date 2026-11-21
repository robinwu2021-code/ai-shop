package ai.neargo.shop.paybridge;

import ai.neargo.shop.promotion.dto.BenefitClaimCommand;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantView;
import java.util.List;

public interface ActivityBenefitCoordinator {
    ClaimView claim(EntitlementContext context, BenefitClaimCommand command, String requestKey);
    ClaimView query(EntitlementContext context, String intentNo);
    ClaimView recover(EntitlementContext context, String intentNo);
    record ClaimView(String intentNo, String status, List<GrantView> grants) {
        public ClaimView { grants = List.copyOf(grants); }
    }
}
