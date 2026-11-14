package ai.neargo.shop.beauty.policy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import ai.neargo.shop.beauty.entity.BtyWorkOrderContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCustomerRef;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import org.junit.jupiter.api.Test;

/** Wrong reception selectors are rejected before the caller persists an immutable intent. */
class ServiceSubmissionAccessTest {
    final ReceptionIdentity identities = mock(ReceptionIdentity.class);
    final ServiceSubmissionAccess access = new ServiceSubmissionAccess(null, identities, null, null);
    final BtyWorkOrderContext context = new BtyWorkOrderContext();
    ServiceSubmissionAccessTest() {
        context.setEntityNo("E"); context.setStoreNo("S");
        context.setIdentityKind("USER"); context.setIdentityRef("U1");
    }
    @Test void wrongMemberCanBeCorrectedWithoutCreatingOwnerQuote() {
        var party = new CustomerParty("USER", "U1", null, (Long) null);
        when(identities.customerMember("E", "S", "WRONG")).thenReturn("U2");
        when(identities.customerMember("E", "S", "RIGHT")).thenReturn("U1");
        assertThatThrownBy(() -> access.verifyManualSelector(context,
                new CounterCustomerRef("USER", "WRONG", null, null), party)).isInstanceOf(BizException.class);
        assertDoesNotThrow(() -> access.verifyManualSelector(context,
                new CounterCustomerRef("USER", "RIGHT", null, null), party));
    }
    @Test void claimedGuestChoiceMustMatchVerifiedReceptionIdentity() {
        context.setIdentityKind("GUEST"); context.setIdentityRef("G1");
        when(identities.verifyGuest("E", "S", "G1", "a".repeat(43)))
                .thenReturn(new ReceptionIdentity.VerifiedIdentity("USER", "U1"));
        var party = new CustomerParty("USER", "U1", null, (Long) null);
        assertThatThrownBy(() -> access.verifyManualSelector(context,
                new CounterCustomerRef("GUEST", null, "G1", "a".repeat(43)), party)).isInstanceOf(BizException.class);
        assertDoesNotThrow(() -> access.verifyManualSelector(context,
                new CounterCustomerRef("CLAIMED_GUEST", null, "G1", "a".repeat(43)), party));
    }
}
