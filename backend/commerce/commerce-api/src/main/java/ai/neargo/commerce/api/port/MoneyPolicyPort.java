package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.MoneyPolicy;
import java.util.Optional;

/** Deployment authority; never accepts policy parameters from the checkout caller. */
@FunctionalInterface
public interface MoneyPolicyPort {
    Optional<MoneyPolicy> policy(String tenantNo, String currency);
    MoneyPolicyPort LEGACY = (tenant, currency) -> Optional.of(MoneyPolicy.legacy(currency));
    MoneyPolicyPort CNY_COMPATIBILITY = (tenant, currency) -> "CNY".equals(currency)
            ? Optional.of(MoneyPolicy.legacy(currency)) : Optional.empty();
}
