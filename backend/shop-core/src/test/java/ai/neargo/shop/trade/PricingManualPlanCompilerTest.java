package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.spi.product.GoodsPricingPolicyFacts;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort;
import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort.ManualKind;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Component;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Kind;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.assembler.PricingQuoteAssembler;
import ai.neargo.shop.trade.dto.PricingPreparation.Prepared;
import ai.neargo.shop.trade.dto.PricingPreparation.RuntimeConfig;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Context;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.LineKind;
import ai.neargo.shop.trade.entity.OrdManualPriceAdjustment;
import ai.neargo.shop.trade.policy.PricingManualPlanCompiler;
import ai.neargo.shop.trade.policy.PricingRuntimeConfigPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Arithmetic stays in shared kernels; persisted approval admission belongs to the service tests. */
class PricingManualPlanCompilerTest {
    static final Instant NOW=Instant.parse("2026-10-24T08:00:00Z");
    static final Scope SCOPE=new Scope("MAIN","E","A");
    static final PricingSourceRef SOURCE=new PricingSourceRef("FOOD","CART","C",4,null,null);

    static Prepared prepared() throws Exception {
        var first=new PricingUnit("L1",null,"G1","S1","R","I",BigDecimal.ZERO,BigDecimal.ONE,
                new MoneyMinor("CNY",300),new MoneyMinor("CNY",300));
        var second=new PricingUnit("L2",null,"G2","S2","R","I",BigDecimal.ZERO,BigDecimal.ONE,
                new MoneyMinor("CNY",700),new MoneyMinor("CNY",700));
        var snapshot=PricingSourceFacts.freeze(SCOPE,"BUYER",SOURCE,"POS","a".repeat(64),
                List.of(new Component(Kind.MAIN,first),new Component(Kind.MAIN,second)));
        var rows=PricingRuntimeFixture.rows();
        var config=PricingRuntimeConfigPolicy.verify(rows.revision(),rows.template(),PricingRuntimeFixture.CAPS);
        var runtime=new RuntimeConfig(SCOPE,"FOOD","CONF","R",1,"TR","PRICING_RULE_SPEC_V5",
                rows.revision().getRuleCatalogVersion(),config,null,"b".repeat(64));
        var policies=GoodsPricingPolicyFacts.freeze(new GoodsPricingPolicyPort.Scope("MAIN","E","A"),
                List.of(new GoodsPricingPolicyPort.Policy("P1","G1","S1",1,true,TaxClass.STANDARD),
                        new GoodsPricingPolicyPort.Policy("P2","G2","S2",1,true,TaxClass.STANDARD)));
        var tax=new MerchantTaxSnapshot("MAIN","E","A",1,MerchantTaxSnapshot.Source.PLATFORM_DEFAULT,
                "PLATFORM",1,1000,PriceTaxMode.INCLUSIVE,
                new TaxSettingDefinition(true,1000,PriceTaxMode.INCLUSIVE)
                        .snapshotHash("PLATFORM_DEFAULT:PLATFORM",1));
        return new Prepared(snapshot,runtime,tax,policies,NOW.plusSeconds(180),"d".repeat(64));
    }
    static OrdManualPriceAdjustment approved() {
        var row=new OrdManualPriceAdjustment(); row.setAdjustmentNo("MANUAL"); row.setActorNo("STAFF");
        row.setManualKind("AMOUNT"); row.setRequestedMinor(101L); row.setReasonCode("PRICE_CORRECTION");
        row.setApprovedBy("OWNER"); row.setStatus("APPROVED"); row.setDiscountMinor(101L);
        row.setEvaluatedAt(LocalDateTime.ofInstant(NOW,ZoneOffset.UTC));
        row.setExpiresAt(LocalDateTime.ofInstant(NOW.plusSeconds(120),ZoneOffset.UTC));
        return row;
    }
    @Test void sharedEngineAndInvoiceTaxConserveApprovedManualDeduction() throws Exception {
        var prepared=prepared(); var row=approved();
        var command=new PricingManualPlanCompiler().compile(prepared,row,NOW);
        var evidence=new PricingQuoteAssembler().prepare(new Context("MAIN","E","A","BUYER","STAFF"),
                command,prepared.rules().config().canonicalJson());
        assertThat(evidence.amounts().manualDiscountMinor()).isEqualTo(101);
        assertThat(evidence.amounts().receivableMinor()).isEqualTo(899);
        assertThat(evidence.amounts().netMinor()+evidence.amounts().taxMinor()).isEqualTo(899);
        assertThat(command.allocations()).extracting(a -> a.amountMinor()).containsExactly(30L,71L);
        assertThat(command.intent().funding().otherPlannedMinor()).isZero();
        assertThat(command.intent().funding().cashTenderMinor()).isNull();
    }
    @Test void percentageAndWaiverUseSameSharedFormulaWithDistinctPersistedKinds() throws Exception {
        var prepared=prepared(); var row=approved();
        row.setManualKind("PERCENT"); row.setRequestedMinor(null);
        row.setPayableRateBp(9000); row.setDiscountMinor(100L);
        var percentage=new PricingManualPlanCompiler().compile(prepared,row,NOW);
        assertThat(percentage.lines().stream().mapToLong(line -> line.amounts().manualDiscountMinor()).sum())
                .isEqualTo(100);
        row.setManualKind("WAIVER"); row.setRequestedMinor(101L);
        row.setPayableRateBp(null); row.setDiscountMinor(101L);
        var waiver=new PricingManualPlanCompiler().compile(prepared,row,NOW);
        assertThat(waiver.lines().stream().mapToLong(line -> line.amounts().waiverMinor()).sum()).isEqualTo(101);
        assertThat(waiver.lines().stream().mapToLong(line -> line.amounts().manualDiscountMinor()).sum()).isZero();
    }
    @Test void additiveSkuRetainsItsSourceAndParentWhileSharingTheAuthorizedReduction() throws Exception {
        var original=prepared(); var units=original.source().prices().units();
        var first=units.getFirst(); var oldAddon=units.getLast();
        var addon=new PricingUnit(first.sourceLineNo(),"f".repeat(64),oldAddon.goodsNo(),oldAddon.skuNo(),
                oldAddon.priceRevision(),oldAddon.taxInvoiceLineRef(),oldAddon.quantityStart(),oldAddon.quantityEnd(),
                oldAddon.unitPrice(),oldAddon.baseAmount());
        var snapshot=PricingSourceFacts.freeze(SCOPE,"BUYER",SOURCE,"POS","a".repeat(64),
                List.of(new Component(Kind.MAIN,first),new Component(Kind.ADDON,addon)));
        var mixed=new Prepared(snapshot,original.rules(),original.tax(),original.goodsPolicies(),
                original.expiresAt(),original.preparationHash());
        var command=new PricingManualPlanCompiler().compile(mixed,approved(),NOW);
        assertThat(command.lines().getLast().kind()).isEqualTo(
                LineKind.ADDON);
        assertThat(command.lines().getLast().parentSourceLineNo()).isEqualTo(first.sourceLineNo());
        assertThat(command.lines().getLast().source().componentRef()).isEqualTo("f".repeat(64));
        var evidence=new PricingQuoteAssembler().prepare(new Context("MAIN","E","A","BUYER","STAFF"),
                command,mixed.rules().config().canonicalJson());
        assertThat(evidence.amounts().receivableMinor()).isEqualTo(899);
    }
    @Test void pendingOrExpiredApprovalCannotCompileAnExecutableQuote() throws Exception {
        var prepared=prepared(); var row=approved(); row.setStatus("PENDING");
        var compiler=new PricingManualPlanCompiler();
        assertThatThrownBy(() -> compiler.compile(prepared,row,NOW)).isInstanceOf(IllegalArgumentException.class);
        row.setStatus("APPROVED");
        assertThatThrownBy(() -> compiler.compile(prepared,row,NOW.plusSeconds(120)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cashRoundingNeverBypassesMandatorySharedConfiguration() throws Exception {
        var prepared=prepared();
        assertThatThrownBy(() -> new PricingManualPlanCompiler().assess(prepared,"STAFF",ManualKind.ROUNDING,
                1L,null,"PRICE_CORRECTION","MANUAL",NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void clientAmountAboveAdmittedSourceCannotBeClampedIntoAnApproval() throws Exception {
        var prepared=prepared();
        assertThatThrownBy(() -> new PricingManualPlanCompiler().assess(prepared,"STAFF",ManualKind.AMOUNT,
                1001L,null,"PRICE_CORRECTION","MANUAL",NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
