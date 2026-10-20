package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.config.PromotionFeatureProperties;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityBudget;
import ai.neargo.shop.promotion.entity.PmtActivityVersion;
import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.promotion.mapper.PmtActivityBudgetMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityParticipantMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityVersionMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ApplyMapper;
import ai.neargo.shop.promotion.mapper.UnifiedOrderActivityAccountingMapper;
import ai.neargo.shop.promotion.service.MarketingEffectProjector;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.marketing.ActivityOrderFactsPort;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.member.MemberQueryPort;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UnifiedOrderActivityAccountingTest {
    private final ActivityMapper heads=mock(ActivityMapper.class);
    private final PmtActivityVersionMapper versions=mock(PmtActivityVersionMapper.class);
    private final PmtActivityBudgetMapper budgets=mock(PmtActivityBudgetMapper.class);
    private final PmtActivityParticipantMapper people=mock(PmtActivityParticipantMapper.class);
    private final UnifiedOrderActivityAccountingMapper accounting=mock(UnifiedOrderActivityAccountingMapper.class);
    private final ApplyMapper applies=mock(ApplyMapper.class);
    private final ActivityOrderFactsPort orders=mock(ActivityOrderFactsPort.class);
    private final MarketingEffectProjector effects=mock(MarketingEffectProjector.class);
    private final ObjectMapper json=mock(ObjectMapper.class);
    private final PromotionFeatureProperties flags=new PromotionFeatureProperties();
    private final UnifiedOrderActivityPricingServiceImpl service=new UnifiedOrderActivityPricingServiceImpl(heads,versions,
            budgets,people,accounting,applies,orders,mock(MemberQueryPort.class),flags,json,effects);
    private final CampaignPort.AppliedActivity selected=new CampaignPort.AppliedActivity("A1","M1",100L,1,0L,null,"优惠").atStore("S1");

    private void fixture() {
        flags.getActivity().setEnabled(true);
        var head=new PmtActivity(); head.setTenantNo("MAIN"); head.setActivityNo("A1"); head.setScene("ORDER");
        head.setDraftSnapshot("draft"); head.setCurrentVersionNo("V1"); head.setStatus("RUNNING"); head.setEntityNo("M1");
        when(heads.selectOne(any())).thenReturn(head);
        when(applies.selectList(any())).thenReturn(List.of());
        var version=new PmtActivityVersion(); version.setRuleSnapshot("snapshot");
        when(versions.selectOne(any())).thenReturn(version);
        var d=new UnifiedActivityDefinition("优惠","ORDER","AP002",1,new RedemptionScope("ALL",List.of()),null,
                new UnifiedActivityDefinition.Schedule("ONE_OFF",Instant.now().minusSeconds(60),Instant.now().plusSeconds(3600),"UTC",List.of(),null,null),
                new UnifiedActivityDefinition.Rule(1,new UnifiedActivityDefinition.Trigger("AMOUNT",1000L,null),
                        List.of(new UnifiedActivityDefinition.Benefit("CUT",100L,null,null,null)),List.of()),10L,1000L,1,"CNY");
        when(json.readValue("snapshot",ActivityVersionServiceImpl.PublishedSnapshot.class))
                .thenReturn(new ActivityVersionServiceImpl.PublishedSnapshot(d,null));
        when(orders.find("MAIN","O1","M1","S1")).thenReturn(new ActivityOrderFactsPort.Fact("SUB1","U1","WAIT_PAY",1000L,1));
        var budget=new PmtActivityBudget(); budget.setCurrency("CNY"); budget.setPerUserLimit(1);
        when(budgets.lock("MAIN","A1")).thenReturn(budget);
        when(budgets.reserve("MAIN","A1",1,100L)).thenReturn(1);
        when(people.reserve("MAIN","A1","U1",1,1)).thenReturn(1);
        when(budgets.settle("MAIN","A1",1,100L,1,100L)).thenReturn(1);
        when(people.settle("MAIN","A1","U1",1,1)).thenReturn(1);
        when(applies.insert(any(PmtApply.class))).thenReturn(1);
    }
    @Test void commitsOnlyRecomputedOrderDiscountAndProjectsTheActualLedgerRow() {
        fixture(); assertTrue(service.commit("U1","O1",selected));
        verify(budgets).reserve("MAIN","A1",1,100L);
        verify(people).settle("MAIN","A1","U1",1,1);
        verify(effects).recordOrderDiscount(eq("MAIN"),eq("A1"),anyString(),eq("M1"),eq("S1"),eq(100L),eq("CNY"),any());
    }
    @Test void exhaustedCapacityFailsBeforeWritingApplyOrProjection() {
        fixture(); when(budgets.reserve("MAIN","A1",1,100L)).thenReturn(0);
        assertThrows(BizException.class,()->service.commit("U1","O1",selected));
        verify(applies,never()).insert(any(PmtApply.class)); verifyNoInteractions(effects);
    }
    @Test void retryDoesNotConsumeAgainAndCannotReviveCancelledUsage() {
        fixture(); var prior=new PmtApply(); prior.setUserNo("U1"); prior.setAmountMinor(100L);
        when(applies.selectList(any())).thenReturn(List.of(prior));
        assertTrue(service.commit("U1","O1",selected)); verifyNoInteractions(budgets,people,effects);
        prior.setRevertedAt(1L); assertThrows(BizException.class,()->service.commit("U1","O1",selected));
    }
    @Test void cancellationReturnsConsumedBudgetAndPersonalUseOnlyOnce() {
        fixture(); var prior=new PmtApply(); prior.setId(1L); prior.setTenantNo("MAIN"); prior.setPromoNo("A1");
        prior.setOrderNo("O1"); prior.setEntityNo("M1"); prior.setStoreNo("S1"); prior.setSubOrderNo("SUB1");
        prior.setUserNo("U1"); prior.setAmountMinor(100L);
        when(applies.selectById(1L)).thenReturn(prior);
        when(orders.find("MAIN","O1","M1","S1"))
                .thenReturn(new ActivityOrderFactsPort.Fact("SUB1","U1","CANCELLED",1000L,1));
        when(accounting.reverseBudget("MAIN","A1",100L)).thenReturn(1);
        when(accounting.reverseParticipant("MAIN","A1","U1")).thenReturn(1);
        when(applies.updateById(any(PmtApply.class))).thenReturn(1);
        assertTrue(service.release(prior)); assertTrue(service.release(prior));
        verify(accounting).reverseBudget("MAIN","A1",100L);
        verify(accounting).reverseParticipant("MAIN","A1","U1");
    }
    @Test void forgedAmountOrWrongBuyerFailsBeforeCapacityChanges() {
        fixture(); when(orders.find("MAIN","O1","M1","S1"))
                .thenReturn(new ActivityOrderFactsPort.Fact("SUB1","OTHER","WAIT_PAY",1000L,1));
        assertThrows(BizException.class,()->service.commit("U1","O1",selected));
        verifyNoInteractions(budgets,people,effects);
    }
}
