package ai.neargo.shop.promotion.service;

import ai.neargo.shop.numbering.InvitationDocumentNumberReservationAdvisor;
import ai.neargo.shop.numbering.CoreMarketingDocumentNumbers;
import ai.neargo.shop.marketing.attribution.entity.MktFissionCampaign;
import ai.neargo.shop.marketing.attribution.mapper.AttributionMappers.FissionCampaignMapper;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.user.service.AuthService;
import ai.neargo.shop.user.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvitationDocumentNumberReservationAdvisorTest {
    @Test void reservesAllConfiguredAwardsBeforeAuthTransactionAndClearsSnapshot() {
        var coordinator = mock(DocumentNumberCoordinator.class);
        var campaigns = mock(FissionCampaignMapper.class);
        var campaign = new MktFissionCampaign(); campaign.setFissionNo("FI"); campaign.setEnabled(true);
        campaign.setRewardType(MktFissionCampaign.COUPON); campaign.setCouponNo("CO");
        campaign.setInviterCount(3); campaign.setInviteeCount(2);
        when(campaigns.selectOne(any())).thenReturn(campaign);
        when(coordinator.withReservation(anyMap(), any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(Map.of(DocumentType.MARKETING_USER_COUPON, 5), call.getArgument(0));
            return ((Supplier<?>) call.getArgument(1)).get();
        });
        var target = mock(AuthServiceImpl.class);
        when(target.login(any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertSame(campaign, InvitationDocumentNumberReservationAdvisor.currentCampaign().orElseThrow());
            return null;
        });
        var reservation = new InvitationDocumentNumberReservationAdvisor(new CoreMarketingDocumentNumbers(coordinator), campaigns);
        var transaction = new DefaultPointcutAdvisor(new TransactionInterceptor(new LocalTransactions(),
                new AnnotationTransactionAttributeSource()));
        var advisors = new ArrayList<>(List.of(transaction, reservation));
        AnnotationAwareOrderComparator.sort(advisors);
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true); advisors.forEach(factory::addAdvisor);
        ((AuthServiceImpl) factory.getProxy()).login(new AuthService.LoginCommand("PHONE_OTP", "phone", "otp", null, "inviter", true));
        assertFalse(InvitationDocumentNumberReservationAdvisor.hasSnapshot());
        verify(coordinator).withReservation(anyMap(), any());
    }
    @Test void naturalRegistrationDoesNotReadOrReserveMarketingNumbers() throws Throwable {
        var coordinator = mock(DocumentNumberCoordinator.class);
        var campaigns = mock(FissionCampaignMapper.class);
        var advisor = new InvitationDocumentNumberReservationAdvisor(new CoreMarketingDocumentNumbers(coordinator), campaigns);
        var invocation = mock(org.aopalliance.intercept.MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[]{new AuthService.LoginCommand("PHONE_OTP", "phone", "otp", null, null, true)});
        ((org.aopalliance.intercept.MethodInterceptor) advisor.getAdvice()).invoke(invocation);
        verify(invocation).proceed(); verifyNoInteractions(coordinator, campaigns);
    }
    private static class LocalTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
