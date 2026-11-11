package ai.neargo.shop.promotion.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.config.PromotionFeatureProperties;
import ai.neargo.shop.promotion.cost.BrandPromotionCostService;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition.*;
import ai.neargo.shop.promotion.entity.*;
import ai.neargo.shop.promotion.mapper.*;
import ai.neargo.shop.promotion.mapper.PromotionMappers.*;
import ai.neargo.shop.promotion.service.*;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.marketing.ActivityOrderFactsPort;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.member.MemberQueryPort;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/** Actual consuming entity remains the order/effect owner while the one shared activity holds its global budget. */
class BrandOrderActivityConsumerTest {
    @Test void sharedCandidateCommitsAgainstConsumerOrderAndKeepsOriginalVersionWithoutRetargeting() {
        var json=new ObjectMapper(); var flags=new PromotionFeatureProperties(); flags.getActivity().setEnabled(true);
        var heads=mock(ActivityMapper.class); var versions=mock(PmtActivityVersionMapper.class);
        var budgets=mock(PmtActivityBudgetMapper.class); var people=mock(PmtActivityParticipantMapper.class);
        var applies=mock(ApplyMapper.class); var facts=mock(ActivityOrderFactsPort.class);
        var effect=mock(MarketingEffectProjector.class); var ownership=mock(BrandActivityOwnershipService.class);
        var costs=mock(BrandPromotionCostService.class);
        var service=new UnifiedOrderActivityPricingServiceImpl(heads,versions,budgets,people,
                mock(UnifiedOrderActivityAccountingMapper.class),applies,facts,mock(MemberQueryPort.class),flags,json,effect);
        var beans=new StaticListableBeanFactory(); beans.addBean("owner",ownership);
        ReflectionTestUtils.setField(service,"brandOwners",beans.getBeanProvider(BrandActivityOwnershipService.class));
        ReflectionTestUtils.setField(service,"costs",costs);
        var head=new PmtActivity(); head.setTenantNo("MAIN"); head.setActivityNo("A1"); head.setOwner("BRAND");
        head.setEntityNo("HQ"); head.setScene("ORDER"); head.setStatus("RUNNING");
        head.setCurrentVersionNo("AV1"); head.setDraftSnapshot("original");
        var definition=new UnifiedActivityDefinition("cut","ORDER","AP009",1,new RedemptionScope("STORES",List.of("S2")),
                null,new Schedule("ONE_OFF",Instant.now().minusSeconds(60),Instant.now().plusSeconds(600),"UTC",List.of(),null,null),
                new Rule(1,new Trigger("AMOUNT",1000L,null),List.of(new Benefit("CUT",100L,null,null,null)),List.of()),
                10L,1000L,1,"CNY");
        var version=new PmtActivityVersion(); version.setRuleSnapshot(json.writeValueAsString(
                new ActivityVersionServiceImpl.PublishedSnapshot(definition,null)));
        when(heads.selectList(any())).thenReturn(List.of(),List.of(head)); when(heads.selectOne(any())).thenReturn(head);
        when(versions.selectOne(any())).thenReturn(version);
        when(ownership.ordinaryActivities("MAIN","E2","S2")).thenReturn(List.of("A1"));
        when(ownership.participates(head,"E2","S2")).thenReturn(true);
        var budget=new PmtActivityBudget(); budget.setCurrency("CNY"); budget.setPerUserLimit(1);
        when(budgets.selectOne(any())).thenReturn(budget); when(budgets.lock("MAIN","A1")).thenReturn(budget);
        when(budgets.reserve("MAIN","A1",1,100)).thenReturn(1);
        when(budgets.settle("MAIN","A1",1,100,1,100)).thenReturn(1);
        when(people.reserve("MAIN","A1","U1",1,1)).thenReturn(1);
        when(people.settle("MAIN","A1","U1",1,1)).thenReturn(1);
        when(applies.selectList(any())).thenReturn(List.of()); when(applies.insert(any(PmtApply.class))).thenReturn(1);
        when(facts.find("MAIN","O1","E2","S2")).thenReturn(
                new ActivityOrderFactsPort.Fact("SUB2","U1","WAIT_PAY",1000,1));
        var selected=service.candidates("U1",List.of(new CampaignPort.MerchantAmount("E2",1000,1,"S2")));
        assertThat(selected).singleElement().satisfies(a -> assertThat(a.merchantNo()).isEqualTo("E2"));
        assertThat(service.commit("U1","O1",selected.getFirst())).isTrue();
        var saved=ArgumentCaptor.forClass(PmtApply.class); verify(applies).insert(saved.capture());
        assertThat(saved.getValue().getEntityNo()).isEqualTo("E2");
        assertThat(saved.getValue().getSubOrderNo()).isEqualTo("SUB2");
        verify(costs).captureActivity("MAIN",saved.getValue().getApplyNo(),"AV1");
        verify(facts,never()).find("MAIN","O1","HQ","S2");
        when(ownership.participates(head,"E2","S2")).thenReturn(false);
        assertThatThrownBy(() -> service.commit("U1","O1",selected.getFirst())).isInstanceOf(BizException.class);
        verify(applies,times(1)).insert(any(PmtApply.class));
    }
}
