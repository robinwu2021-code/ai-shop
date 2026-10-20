package ai.neargo.shop.promotion.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.member.MemberQueryPort;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;

class ActivityEligibilityPolicyTest {
    private final MemberQueryPort members=mock(MemberQueryPort.class);
    private final ActivityEligibilityPolicy policy=new ActivityEligibilityPolicy(members);
    private final Instant start=Instant.parse("2026-10-19T00:00:00Z");
    private UnifiedActivityDefinition definition() {
        return new UnifiedActivityDefinition("activity","ORDER","ORDER_DISCOUNT",1,
                new RedemptionScope("STORES",List.of("store")),"audience",
                new UnifiedActivityDefinition.Schedule("RANGE",start,start.plusSeconds(3600),"UTC",List.of(),null,null),
                new UnifiedActivityDefinition.Rule(1,null,List.of(),List.of()),10L,100L,1,"CNY");
    }
    private ReserveActivity command(String store,String currency) {
        return new ReserveActivity(new SourceRef("CORE","ORDER","order",null,null,null,0),
                "activity","version",null,"user","entity",store,currency,1);
    }
    @Test void checksFrozenAudienceRatherThanCurrentSegment() {
        when(members.matchesRule("entity","user","frozen")).thenReturn(true);
        assertDoesNotThrow(()->policy.requireEligible(definition(),"frozen",command("store","CNY"),start));
        verify(members).matchesRule("entity","user","frozen");
        verifyNoMoreInteractions(members);
    }
    @Test void rejectsEndBoundaryWrongStoreAndCurrency() {
        assertThrows(BizException.class,()->policy.requireEligible(definition(),null,command("store","CNY"),start.plusSeconds(3600)));
        assertThrows(BizException.class,()->policy.requireEligible(definition(),null,command("other","CNY"),start));
        assertThrows(BizException.class,()->policy.requireEligible(definition(),null,command("store","USD"),start));
    }
    @Test void rejectsUnmatchedFrozenAudience() {
        assertThrows(BizException.class,()->policy.requireEligible(definition(),"frozen",command("store","CNY"),start));
    }
}
