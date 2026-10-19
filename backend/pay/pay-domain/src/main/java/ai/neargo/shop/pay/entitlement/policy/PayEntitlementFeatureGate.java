package ai.neargo.shop.pay.entitlement.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import org.springframework.stereotype.Component;

/** Gate only new commands; replay, historical reads and completion of existing commitments must remain possible. */
@Component
public class PayEntitlementFeatureGate {
    private final PayEntitlementProperties properties;

    public PayEntitlementFeatureGate(PayEntitlementProperties properties) {
        this.properties = properties;
    }

    /** Catalog availability is required before new definitions or references can be established. */
    public void requireCatalog() {
        require(properties.getEntitlement().getCatalog().isEnabled());
    }

    /** New issuance cannot be enabled without its authoritative definition catalog. */
    public void requireIssuance() {
        requireCatalog();
        require(properties.getEntitlement().getIssue().isEnabled());
    }

    /** Usage is independent of new issuance, so existing assets remain usable when issuance is stopped. */
    public void requireUse() {
        require(properties.getEntitlement().getUse().isEnabled());
    }

    /** Optional exchange-code compatibility never enables account-wide or stored-value credentials. */
    public void requireCode() {
        requireUse(); require(properties.getEntitlement().getCode().isEnabled());
    }

    /** Stored value has its own account/posting owner; its use entry must also requireUse. */
    public void requireStoredValue() {
        require(properties.getStoredValue().isEnabled());
    }

    /** A sales opt-in alone cannot bypass the issuance and redemption foundations of paid cards. */
    public void requirePaidCard() {
        requireIssuance();
        requireUse();
        require(properties.getPaidCard().isEnabled());
    }

    private void require(boolean enabled) {
        if (!enabled) {
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
    }
}
