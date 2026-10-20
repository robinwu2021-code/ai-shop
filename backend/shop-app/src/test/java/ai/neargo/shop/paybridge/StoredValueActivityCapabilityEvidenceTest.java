package ai.neargo.shop.paybridge;

import static org.assertj.core.api.Assertions.assertThat;
import ai.neargo.shop.member.config.MemberCodeProperties;
import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.pay.storedvalue.port.StoredValueCapabilityPortImpl;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import org.junit.jupiter.api.Test;

/** Availability is the intersection of implemented owner capabilities and the existing independent opt-ins. */
class StoredValueActivityCapabilityEvidenceTest {
    @Test void creditAndUseAndMemberIdentityStayIndependentAndOtherTenantIsUnavailable() {
        var properties=new PayEntitlementProperties();
        var members=new MemberCodeProperties();
        var owner=new StoredValueCapabilityPortImpl(properties);
        var evidence=new StoredValueActivityCapabilityEvidence(owner,members);
        var actor=new ActivityActor("MAIN","M1","U1",null);
        assertThat(evidence.verifiedFor(actor)).doesNotContain(ActivityCapability.PAY_CREDIT,
                ActivityCapability.STORED_VALUE_REDEEM,ActivityCapability.MEMBER_IDENTITY);
        properties.getStoredValue().setEnabled(true);
        assertThat(evidence.verifiedFor(actor)).contains(ActivityCapability.PAY_CREDIT)
                .doesNotContain(ActivityCapability.STORED_VALUE_REDEEM);
        properties.getEntitlement().getUse().setEnabled(true); members.setEnabled(true);
        assertThat(evidence.verifiedFor(actor)).contains(ActivityCapability.PAY_CREDIT,
                ActivityCapability.STORED_VALUE_REDEEM,ActivityCapability.MEMBER_IDENTITY);
        assertThat(evidence.verifiedFor(new ActivityActor("OTHER","M1","U1",null))).isEmpty();
        assertThat(owner.status("OTHER").creditReady()).isFalse();
    }
}
