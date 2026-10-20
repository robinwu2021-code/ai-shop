package ai.neargo.shop.paybridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.member.config.MemberCodeProperties;
import ai.neargo.shop.pay.client.PayCapabilityRequests;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.pay.entitlement.port.EntitlementCapabilityPortImpl;
import ai.neargo.shop.payclient.port.RemoteEntitlementCapabilityPort;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.spi.entitlement.EntitlementCapabilityPort;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import ai.neargo.shop.trade.entitlement.config.EntitlementAuthorizationProperties;
import org.junit.jupiter.api.Test;

class PaidCardActivityCapabilityEvidenceTest {
    @Test void missingOwnerOptInsAndSourceConsentNeverCertifyPurchasingOrConsumption() {
        var properties = new PayEntitlementProperties();
        var members = new MemberCodeProperties();
        var consent = new EntitlementAuthorizationProperties();
        var owner = new EntitlementCapabilityPortImpl(properties);
        var evidence = new PaidCardActivityCapabilityEvidence(owner, members, consent, true);
        var actor = new ActivityActor("MAIN", "M1", "U1", null);
        assertThat(evidence.verifiedFor(actor)).isEmpty();
        properties.getPaidCard().setEnabled(true);
        properties.getEntitlement().getCatalog().setEnabled(true);
        properties.getEntitlement().getIssue().setEnabled(true);
        properties.getEntitlement().getUse().setEnabled(true);
        assertThat(evidence.verifiedFor(actor)).contains(ActivityCapability.CARD_PRODUCT, ActivityCapability.PAID_ORDER,
                ActivityCapability.IDEMPOTENT_CARD_ISSUE, ActivityCapability.ENTITLEMENT_LEDGER,
                ActivityCapability.VALIDITY_POLICY).doesNotContain(ActivityCapability.ATOMIC_USE_DEDUCT);
        members.setEnabled(true);
        assertThat(evidence.verifiedFor(actor)).doesNotContain(ActivityCapability.ATOMIC_USE_DEDUCT);
        consent.setEnabled(true);
        assertThat(evidence.verifiedFor(actor)).hasSize(6).contains(ActivityCapability.ATOMIC_USE_DEDUCT);
        assertThat(new PaidCardActivityCapabilityEvidence(owner, members, consent, false).verifiedFor(actor))
                .doesNotContain(ActivityCapability.PAID_ORDER);
        properties.getEntitlement().getIssue().setEnabled(false);
        assertThat(evidence.verifiedFor(actor)).doesNotContain(ActivityCapability.IDEMPOTENT_CARD_ISSUE,
                ActivityCapability.ENTITLEMENT_LEDGER, ActivityCapability.VALIDITY_POLICY);
        assertThat(evidence.verifiedFor(new ActivityActor("OTHER", "M1", "U1", null))).isEmpty();
        assertThat(owner.status("OTHER")).isEqualTo(new EntitlementCapabilityPort.Status("OTHER", false, false,
                false, false));
    }
    @Test void remoteMissingAndForeignOwnerResponsesNeverAttestReadiness() {
        var api = mock(PayInternalApi.class);
        var remote = new RemoteEntitlementCapabilityPort(api);
        var request = new PayCapabilityRequests.Status("MAIN");
        assertThatThrownBy(() -> remote.status("MAIN")).isInstanceOf(BizException.class);
        when(api.entitlementCapabilities(request))
                .thenReturn(new EntitlementCapabilityPort.Status("OTHER", true, true, true, true));
        assertThatThrownBy(() -> remote.status("MAIN")).isInstanceOf(BizException.class);
        var correct = new EntitlementCapabilityPort.Status("MAIN", true, true, true, true);
        when(api.entitlementCapabilities(request)).thenReturn(correct);
        assertThat(remote.status("MAIN")).isEqualTo(correct);
    }
}
