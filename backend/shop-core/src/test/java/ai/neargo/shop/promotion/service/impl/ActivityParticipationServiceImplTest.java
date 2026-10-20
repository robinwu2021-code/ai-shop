package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.mapper.PmtActivityVersionMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityReservationMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityBudgetMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityParticipantMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.policy.ActivityEligibilityPolicy;
import ai.neargo.shop.promotion.policy.PromotionFeatureGate;
import ai.neargo.shop.promotion.service.ActivityParticipationService;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort;
import ai.neargo.shop.spi.promotion.ActivityParticipationOutcomePort;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class ActivityParticipationServiceImplTest {
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ActivityParticipationOutcomePort> providers=mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ActivityParticipationService> self=mock(ObjectProvider.class);
    private final PmtActivityReservationMapper reservations=mock(PmtActivityReservationMapper.class);
    private final PmtActivityBudgetMapper budgets=mock(PmtActivityBudgetMapper.class);
    private final PmtActivityParticipantMapper participants=mock(PmtActivityParticipantMapper.class);
    private final ActivityParticipationServiceImpl service=new ActivityParticipationServiceImpl(
            mock(ActivityMapper.class),mock(PmtActivityVersionMapper.class),reservations,budgets,participants,
            mock(ActivityEligibilityPolicy.class),mock(PromotionFeatureGate.class),providers,self,new ObjectMapper());
    private final SourceRef source=new SourceRef("PAY","RECHARGE","recharge",null,null,null,0);
    private final EntitlementContext ctx=new EntitlementContext("tenant","SYSTEM","request","a".repeat(64));
    @Test void unknownOutcomeCannotConfirmOrReleaseAndNeverTouchesCapacity() {
        var provider=mock(ActivityParticipationOutcomePort.class);
        when(provider.supports(source)).thenReturn(true);
        when(provider.outcome(ctx,source)).thenReturn(new ActivityParticipationOutcomePort.Outcome(
                ActivityParticipationOutcomePort.State.UNKNOWN,null,null,null));
        when(providers.orderedStream()).thenAnswer(i->Stream.of(provider));
        assertThrows(BizException.class,()->service.confirm(ctx,new ActivityParticipationPort.ConfirmParticipation("reservation",source,"fact")));
        assertThrows(BizException.class,()->service.release(ctx,new ActivityParticipationPort.ReleaseParticipation("reservation",source,"abort")));
        verifyNoInteractions(reservations,budgets,participants);
    }
    @Test void callerDeclaredSuccessDoesNotReplaceMissingSourceOwner() {
        when(providers.orderedStream()).thenAnswer(i->Stream.empty());
        assertThrows(BizException.class,()->service.confirm(ctx,new ActivityParticipationPort.ConfirmParticipation("reservation",source,"fact")));
        verifyNoInteractions(reservations,budgets,participants);
    }
}
