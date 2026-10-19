package ai.neargo.shop.member.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.MemberQueryPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EntitlementIssueAudiencePortImplTest {
    private final MemberQueryPort members = mock(MemberQueryPort.class);
    private final MerchantScopeFactsPort merchants = mock(MerchantScopeFactsPort.class);
    private final UserScopeFactsPort users = mock(UserScopeFactsPort.class);
    private final EntitlementIssueAudiencePortImpl owner = new EntitlementIssueAudiencePortImpl(members, merchants, users);
    private final EntitlementContext context = new EntitlementContext("MAIN", "A", "R", "a".repeat(64));
    @Test void rejectsInactiveMerchantBeforeResolvingItsAudience() {
        assertThatThrownBy(() -> owner.resolve(context, "M1", "SEG1")).isInstanceOf(BizException.class);
        verifyNoInteractions(members, users);
    }
    @Test void resolvesOnlyOwnedAudienceAndRetainsExplicitAccountSkipReasons() {
        when(merchants.entityActive("MAIN", "M1")).thenReturn(true);
        when(members.resolveSegment("M1", "SEG1")).thenReturn(new MemberQueryPort.SegmentAudience(2, List.of(
                new MemberQueryPort.Audience("MEM1", "U1"), new MemberQueryPort.Audience("MEM2", "U2"))));
        when(users.findActive("MAIN", "U1")).thenReturn(Optional.of(new UserScopeFactsPort.ActiveUser("U1", "User")));
        when(users.findActive("MAIN", "U2")).thenReturn(Optional.empty());
        var result = owner.resolve(context, "M1", "SEG1");
        assertThat(result.recipients()).hasSize(2);
        assertThat(result.recipients().get(0).eligible()).isTrue();
        assertThat(result.recipients().get(1).reasonCode()).isEqualTo("ACCOUNT_UNAVAILABLE");
    }
}
