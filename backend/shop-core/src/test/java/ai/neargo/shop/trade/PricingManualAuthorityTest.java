package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort.ManualKind;
import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort.Request;
import ai.neargo.shop.trade.assembler.PricingQuoteAssembler;
import ai.neargo.shop.trade.dto.PricingQuoteCommands;
import ai.neargo.shop.trade.dto.PricingPreparation.Prepared;
import ai.neargo.shop.trade.entity.OrdManualPriceAdjustment;
import ai.neargo.shop.trade.entity.OrdManualPricePolicy;
import ai.neargo.shop.trade.mapper.PricingManualMappers.AdjustmentMapper;
import ai.neargo.shop.trade.mapper.PricingManualMappers.PolicyMapper;
import ai.neargo.shop.trade.service.PricingPreparationService;
import ai.neargo.shop.trade.service.PricingQuoteService;
import ai.neargo.shop.trade.service.impl.PricingManualAdjustmentServiceImpl;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** Missing platform identity never turns a client-selected actor or approval reference into authority. */
class PricingManualAuthorityTest {
    @Test void absentIdentityAuthorityDeniesBeforeSourceOrPolicyReads() {
        var policies=mock(PolicyMapper.class); var adjustments=mock(AdjustmentMapper.class);
        var preparations=mock(PricingPreparationService.class); var quotes=mock(PricingQuoteService.class);
        var service=new PricingManualAdjustmentServiceImpl(policies,adjustments,BizIdentityResolver.NONE,
                preparations,quotes,new PricingQuoteAssembler(),mock(IdempotencyService.class),
                new TestTransactions(),Clock.fixed(PricingManualPlanCompilerTest.NOW,ZoneOffset.UTC));
        var request=new Request(PricingManualPlanCompilerTest.SOURCE,ManualKind.AMOUNT,10L,null,
                "PRICE_CORRECTION","Actual concession reason");
        assertThatThrownBy(() -> service.propose(PricingManualPlanCompilerTest.SCOPE,"FORGED",request,"KEY"))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(policies,adjustments,preparations,quotes);
    }
    @Test void missingPolicyDoesNotCreateAnImplicitConcessionGrant() throws Exception {
        var f=new Fixture(); when(f.policies.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> f.service.propose(PricingManualPlanCompilerTest.SCOPE,"STAFF",request(50),"P"))
                .isInstanceOf(BizException.class);
        verify(f.adjustments,never()).insert(any(OrdManualPriceAdjustment.class));
        verifyNoInteractions(f.quotes);
    }
    @Test void employeeRequiresDifferentLiveOwnerAndQuoteHasRealReducedReceivable() throws Exception {
        var f=new Fixture();
        var proposed=f.service.propose(PricingManualPlanCompilerTest.SCOPE,"STAFF",request(101),"P");
        assertThat(proposed.status()).isEqualTo("PENDING");
        assertThatThrownBy(() -> f.service.quote(
                PricingManualPlanCompilerTest.SCOPE,"STAFF",proposed.adjustmentNo(),"Q"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> f.service.approve(PricingManualPlanCompilerTest.SCOPE,"STAFF",
                proposed.adjustmentNo(),0,"A")).isInstanceOf(BizException.class);
        assertThat(f.service.approve(PricingManualPlanCompilerTest.SCOPE,"OWNER",
                proposed.adjustmentNo(),0,"A").status())
                .isEqualTo("APPROVED");
        var quote=f.service.quote(PricingManualPlanCompilerTest.SCOPE,"STAFF",proposed.adjustmentNo(),"Q");
        assertThat(quote.totals().discountMinor()).isEqualTo(101);
        assertThat(quote.totals().receivableMinor()).isEqualTo(899);
        assertThat(quote.totals().remainingDueMinor()).isEqualTo(899);
    }
    @Test void policyChangeRevokesExplicitOwnerApproval() throws Exception {
        var f=new Fixture();
        var proposed=f.service.propose(PricingManualPlanCompilerTest.SCOPE,"STAFF",request(50),"P");
        assertThat(proposed.status()).isEqualTo("PENDING");
        assertThat(proposed.approvedBy()).isNull();
        f.service.approve(PricingManualPlanCompilerTest.SCOPE,"OWNER",proposed.adjustmentNo(),0,"A");
        f.policy.setVersion(1L);
        assertThatThrownBy(() -> f.service.quote(
                PricingManualPlanCompilerTest.SCOPE,"STAFF",proposed.adjustmentNo(),"Q"))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(f.quotes);
    }
    @Test void revokedEmployeeOrChangedSameKeyCannotReuseAnApproval() throws Exception {
        var f=new Fixture();
        var proposed=f.service.propose(PricingManualPlanCompilerTest.SCOPE,"STAFF",request(50),"P");
        f.service.approve(PricingManualPlanCompilerTest.SCOPE,"OWNER",proposed.adjustmentNo(),0,"A");
        assertThatThrownBy(() -> f.service.propose(PricingManualPlanCompilerTest.SCOPE,"STAFF",request(51),"P"))
                .isInstanceOf(BizException.class);
        when(f.identities.resolve("STAFF","A")).thenReturn(BizContext.NONE);
        assertThatThrownBy(() -> f.service.quote(
                PricingManualPlanCompilerTest.SCOPE,"STAFF",proposed.adjustmentNo(),"Q"))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(f.quotes);
    }
    @Test void realProprietorCanOriginateOwnConcessionWithinExplicitAbsolutePolicy() throws Exception {
        var f=new Fixture();
        var proposed=f.service.propose(PricingManualPlanCompilerTest.SCOPE,"OWNER",request(101),"P");
        assertThat(proposed.status()).isEqualTo("APPROVED");
        assertThat(proposed.approvedBy()).isEqualTo("OWNER");
        var quote=f.service.quote(PricingManualPlanCompilerTest.SCOPE,"OWNER",proposed.adjustmentNo(),"Q");
        assertThat(quote.totals().discountMinor()).isEqualTo(101);
        assertThat(quote.totals().receivableMinor()).isEqualTo(899);
        verify(f.adjustments,never()).updateById(any(OrdManualPriceAdjustment.class));
    }
    @Test void positiveEmployeeSelfLimitWithoutCumulativeQuotaFailsClosed() throws Exception {
        var f=new Fixture(); f.policy.setSelfLimitMinor(100L);
        assertThatThrownBy(() -> f.service.propose(PricingManualPlanCompilerTest.SCOPE,"STAFF",request(1),"P"))
                .isInstanceOf(BizException.class);
        verify(f.adjustments,never()).insert(any(OrdManualPriceAdjustment.class));
    }
    @Test void changedSourcePreparationAndRevokedReviewerInvalidateAnApprovedQuote() throws Exception {
        var f=new Fixture();
        var proposed=f.service.propose(PricingManualPlanCompilerTest.SCOPE,"STAFF",request(101),"P");
        f.service.approve(PricingManualPlanCompilerTest.SCOPE,"OWNER",proposed.adjustmentNo(),0,"A");
        when(f.identities.resolve("OWNER","A")).thenReturn(BizContext.NONE);
        assertThatThrownBy(() -> f.service.quote(PricingManualPlanCompilerTest.SCOPE,"STAFF",
                proposed.adjustmentNo(),"Q")).isInstanceOf(BizException.class);
        verifyNoInteractions(f.quotes);
        var second=new Fixture();
        var approved=second.service.propose(PricingManualPlanCompilerTest.SCOPE,"STAFF",request(50),"P");
        second.service.approve(PricingManualPlanCompilerTest.SCOPE,"OWNER",approved.adjustmentNo(),0,"A");
        var old=PricingManualPlanCompilerTest.prepared();
        var changed=new Prepared(old.source(),old.rules(),old.tax(),old.goodsPolicies(),old.expiresAt(),"e".repeat(64));
        when(second.preparations.staff(PricingManualPlanCompilerTest.SCOPE,PricingManualPlanCompilerTest.SOURCE))
                .thenReturn(changed);
        assertThatThrownBy(() -> second.service.quote(PricingManualPlanCompilerTest.SCOPE,"STAFF",
                approved.adjustmentNo(),"Q")).isInstanceOf(BizException.class);
        verifyNoInteractions(second.quotes);
    }
    private static Request request(long amount) {
        return new Request(PricingManualPlanCompilerTest.SOURCE,ManualKind.AMOUNT,amount,null,
                "PRICE_CORRECTION","Actual concession reason");
    }
    private static class Fixture {
        final PolicyMapper policies=mock(PolicyMapper.class);
        final AdjustmentMapper adjustments=mock(AdjustmentMapper.class);
        final BizIdentityResolver identities=mock(BizIdentityResolver.class);
        final PricingPreparationService preparations=mock(PricingPreparationService.class);
        final PricingQuoteService quotes=mock(PricingQuoteService.class);
        final OrdManualPricePolicy policy=new OrdManualPricePolicy();
        final PricingManualAdjustmentServiceImpl service;
        Fixture() throws Exception {
            when(identities.resolve("STAFF","A")).thenReturn(new BizContext("E",Set.of(),Set.of(),Set.of("A"),
                    "A",false,Map.of("A",Set.of(BizPerms.MANAGER))));
            when(identities.resolve("OWNER","A")).thenReturn(
                    new BizContext("E",Set.of(),Set.of(),Set.of("A"),"A",true));
            policy.setPolicyNo("POLICY"); policy.setTenantNo("MAIN"); policy.setEntityNo("E"); policy.setStoreNo("A");
            policy.setCurrency("CNY"); policy.setReasonCode("PRICE_CORRECTION"); policy.setManualKind("AMOUNT");
            policy.setEnabled(true); policy.setSelfLimitMinor(0L); policy.setMaxLimitMinor(500L);
            policy.setMinPayableRateBp(0); policy.setVersion(0L);
            when(policies.selectList(any())).thenReturn(List.of(policy));
            var prepared=PricingManualPlanCompilerTest.prepared();
            when(preparations.staff(PricingManualPlanCompilerTest.SCOPE,PricingManualPlanCompilerTest.SOURCE))
                    .thenReturn(prepared);
            when(preparations.confirmStaff(PricingManualPlanCompilerTest.SCOPE,PricingManualPlanCompilerTest.SOURCE,
                    prepared.preparationHash())).thenReturn(prepared);
            when(adjustments.insert(any(OrdManualPriceAdjustment.class))).thenAnswer(call -> {
                OrdManualPriceAdjustment row=call.getArgument(0);
                when(adjustments.selectList(any())).thenReturn(List.of(row)); return 1;
            });
            when(adjustments.updateById(any(OrdManualPriceAdjustment.class))).thenAnswer(call -> {
                OrdManualPriceAdjustment row=call.getArgument(0); row.setVersion(row.getVersion()+1); return 1;
            });
            var idem=mock(IdempotencyService.class);
            doAnswer(call -> ((Supplier<?>)call.getArgument(5)).get()).when(idem)
                    .execute(anyString(),anyString(),anyString(),anyString(),any(),any());
            var assembler=new PricingQuoteAssembler();
            when(quotes.save(any(),any(),anyString())).thenAnswer(call -> {
                PricingQuoteCommands.Context context=call.getArgument(0);
                PricingQuoteCommands.SaveReq req=call.getArgument(1);
                var evidence=assembler.prepare(context,req,prepared.rules().config().canonicalJson());
                var stored=new PricingQuoteCommands.QuoteVO("QUOTE","READY",
                        PricingManualPlanCompilerTest.NOW.plusSeconds(120).toString(),evidence.factsHash(),
                        evidence.requestHash(),evidence.economicsHash(),evidence.factsSnapshot(),
                        evidence.configSnapshot(),
                        evidence.planSnapshot(),evidence.resultSnapshot(),null);
                when(quotes.get(any(),anyString())).thenReturn(stored); return stored;
            });
            service=new PricingManualAdjustmentServiceImpl(policies,adjustments,identities,preparations,quotes,
                    assembler,idem,new TestTransactions(),
                    Clock.fixed(PricingManualPlanCompilerTest.NOW,ZoneOffset.UTC));
        }
    }
    private static class TestTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction,TransactionDefinition d) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
