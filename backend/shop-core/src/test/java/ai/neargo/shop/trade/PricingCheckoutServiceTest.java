package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.spi.product.GoodsPricingPolicyFacts;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.Selection;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Component;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Kind;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.assembler.PricingQuoteAssembler;
import ai.neargo.shop.trade.dto.PricingPreparation.Prepared;
import ai.neargo.shop.trade.dto.PricingPreparation.RuntimeConfig;
import ai.neargo.shop.trade.dto.PricingQuoteCommands;
import ai.neargo.shop.trade.policy.PricingRuntimeConfigPolicy;
import ai.neargo.shop.trade.service.PricingPreparationService;
import ai.neargo.shop.trade.service.PricingQuoteService;
import ai.neargo.shop.trade.service.PricingManualAdjustmentService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import ai.neargo.shop.trade.service.impl.PricingCheckoutServiceImpl;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class PricingCheckoutServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-23T08:00:00Z");
    private final Scope scope = new Scope("MAIN", "E", "A");
    private final PricingSourceRef source = new PricingSourceRef("FOOD", "CART", "C", 4, null, null);
    private final PricingPreparationService preparations = mock(PricingPreparationService.class);
    private final PricingQuoteService quotes = mock(PricingQuoteService.class);
    private final PricingQuoteAssembler assembler = new PricingQuoteAssembler();
    private final PricingCheckoutServiceImpl service = new PricingCheckoutServiceImpl(preparations, quotes,
            assembler, Clock.fixed(NOW, ZoneOffset.UTC));

    private Prepared prepared(String hash, PriceTaxMode mode) throws Exception {
        var main = new PricingUnit("L", "f".repeat(64), "G", "S", "V", "I", BigDecimal.ZERO,
                new BigDecimal("1.5"), new MoneyMinor("CNY", 2), new MoneyMinor("CNY", 3));
        var addon = new PricingUnit("L", "e".repeat(64), "G2", "S2", "V", "I", BigDecimal.ZERO,
                new BigDecimal("0.5"), new MoneyMinor("CNY", 6), new MoneyMinor("CNY", 3));
        var snapshot = PricingSourceFacts.freeze(scope, null, source, "POS", "a".repeat(64),
                List.of(new Component(Kind.MAIN, main), new Component(Kind.ADDON, addon)));
        var rows = PricingRuntimeFixture.rows();
        var config = PricingRuntimeConfigPolicy.verify(rows.revision(), rows.template(), PricingRuntimeFixture.CAPS);
        var runtime = new RuntimeConfig(scope, "FOOD", "CONF", "R", 1, "TR", "PRICING_RULE_SPEC_V5",
                rows.revision().getRuleCatalogVersion(), config, null, "b".repeat(64));
        var policies = GoodsPricingPolicyFacts.freeze(new GoodsPricingPolicyPort.Scope("MAIN", "E", "A"),
                List.of(new GoodsPricingPolicyPort.Policy("P", "G", "S", 1, false, TaxClass.STANDARD),
                        new GoodsPricingPolicyPort.Policy("P2", "G2", "S2", 1, true, TaxClass.STANDARD)));
        var tax = new MerchantTaxSnapshot("MAIN", "E", "A", 1, MerchantTaxSnapshot.Source.PLATFORM_DEFAULT,
                "PLATFORM", 1, 1000, mode,
                new TaxSettingDefinition(true, 1000, mode).snapshotHash("PLATFORM_DEFAULT:PLATFORM", 1));
        return new Prepared(snapshot, runtime, tax, policies, NOW.plusSeconds(180), hash);
    }
    private void store(Prepared value) {
        when(quotes.save(any(), any(), eq("KEY"))).thenAnswer(invocation -> {
            PricingQuoteCommands.Context context = invocation.getArgument(0);
            PricingQuoteCommands.SaveReq req = invocation.getArgument(1);
            var evidence = assembler.prepare(context, req, value.rules().config().canonicalJson());
            var quote = new PricingQuoteCommands.QuoteVO("PQ", "READY", NOW.plusSeconds(120).toString(),
                    evidence.factsHash(), evidence.requestHash(), evidence.economicsHash(), evidence.factsSnapshot(),
                    evidence.configSnapshot(), evidence.planSnapshot(), evidence.resultSnapshot(), null);
            when(quotes.get(any(), eq("PQ"))).thenReturn(quote);
            return quote;
        });
    }
    @Test void ownerSelectionFailsExplicitlyBeforeAnyQuoteIsSaved() {
        assertThatThrownBy(() -> service.quoteStaff(scope, "ACTOR", source,
                new Selection(List.of("COUPON"), null, null), "KEY"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PRICING_OWNER_UNSUPPORTED));
        verifyNoInteractions(preparations, quotes);
    }
    @Test void sharedInvoiceTaxRoundsOnceAndPreservesExactComponentQuantities() throws Exception {
        var prepared = prepared("d".repeat(64), PriceTaxMode.EXCLUSIVE);
        when(preparations.staff(scope, source)).thenReturn(prepared);
        when(preparations.confirmStaff(scope, source, prepared.preparationHash())).thenReturn(prepared);
        store(prepared);
        var quote = service.quoteStaff(scope, "ACTOR", source, Selection.none(), "KEY");
        assertThat(quote.totals().taxMinor()).isEqualTo(1);
        assertThat(quote.totals().receivableMinor()).isEqualTo(7);
        assertThat(quote.lines()).hasSize(2);
        assertThat(quote.lines().getFirst().quantityEnd()).isEqualByComparingTo("1.5");
        assertThat(quote.lines().get(1).quantityEnd()).isEqualByComparingTo("0.5");
        assertThat(quote.lines()).extracting(line -> line.invoiceLineRef()).containsOnly("I");
        assertThat(quote.lines().stream().mapToLong(line -> line.taxMinor()).sum()).isEqualTo(1);
        assertThat(service.revalidateStaff(scope, "ACTOR", source, "PQ", quote.confirmationDigest()))
                .isEqualTo(quote);
    }
    @Test void finalRevalidationHonorsFreshManualAuthorityVeto() throws Exception {
        var prepared = prepared("d".repeat(64), PriceTaxMode.EXCLUSIVE);
        when(preparations.staff(scope, source)).thenReturn(prepared);
        when(preparations.confirmStaff(scope, source, prepared.preparationHash())).thenReturn(prepared);
        store(prepared);
        var quote = service.quoteStaff(scope, "ACTOR", source, Selection.none(), "KEY");
        var authority = mock(PricingManualAdjustmentService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PricingManualAdjustmentService> providers = mock(ObjectProvider.class);
        when(providers.getIfUnique()).thenReturn(authority);
        when(authority.handles(any())).thenReturn(true);
        doThrow(BizException.of(ErrorCode.FORBIDDEN)).when(authority).verify(any(), any(), any());
        ReflectionTestUtils.setField(service, "manualAdjustments", providers);
        assertThatThrownBy(() -> service.revalidateStaff(scope, "ACTOR", source, "PQ", quote.confirmationDigest()))
                .isInstanceOfSatisfying(BizException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
    @Test void inclusiveTaxAndSafeOutputAreDerivedFromStoredEvidence() throws Exception {
        var prepared = prepared("d".repeat(64), PriceTaxMode.INCLUSIVE);
        when(preparations.staff(scope, source)).thenReturn(prepared);
        when(preparations.confirmStaff(scope, source, prepared.preparationHash())).thenReturn(prepared);
        store(prepared);
        var quote = service.quoteStaff(scope, "ACTOR", source, Selection.none(), "KEY");
        assertThat(quote.totals().netMinor()).isEqualTo(5);
        assertThat(quote.totals().taxMinor()).isEqualTo(1);
        assertThat(quote.totals().receivableMinor()).isEqualTo(6);
        assertThat(assembler.canonical(quote)).doesNotContain("configSnapshot", "ownerQuote", "orchestrationHash");
    }
    @Test void unchangedAmountDoesNotHidePreparationVersionChange() throws Exception {
        var original = prepared("d".repeat(64), PriceTaxMode.EXCLUSIVE);
        when(preparations.staff(scope, source)).thenReturn(original);
        when(preparations.confirmStaff(scope, source, original.preparationHash())).thenReturn(original);
        store(original);
        var quote = service.quoteStaff(scope, "ACTOR", source, Selection.none(), "KEY");
        var changed = prepared("e".repeat(64), PriceTaxMode.EXCLUSIVE);
        when(preparations.staff(scope, source)).thenReturn(changed);
        assertThatThrownBy(() -> service.revalidateStaff(scope, "ACTOR", source, "PQ", quote.confirmationDigest()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test void wrongConfirmationDigestAndExpiredQuoteNeverValidate() throws Exception {
        var prepared = prepared("d".repeat(64), PriceTaxMode.EXCLUSIVE);
        when(preparations.staff(scope, source)).thenReturn(prepared);
        when(preparations.confirmStaff(scope, source, prepared.preparationHash())).thenReturn(prepared);
        store(prepared);
        var quote = service.quoteStaff(scope, "ACTOR", source, Selection.none(), "KEY");
        assertThatThrownBy(() -> service.revalidateStaff(scope, "ACTOR", source, "PQ", "f".repeat(64)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        var later = new PricingCheckoutServiceImpl(preparations, quotes, assembler,
                Clock.fixed(NOW.plusSeconds(121), ZoneOffset.UTC));
        assertThat(later.getStaff(scope, "ACTOR", source, "PQ").status()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> later.revalidateStaff(scope, "ACTOR", source, "PQ", quote.confirmationDigest()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void customerKeepsSessionSubjectAndAuditsBuyerOrAnonymousSubjectConsistently(boolean loggedIn) throws Exception {
        String actor = loggedIn ? "BUYER" : "H5_SESSION";
        var original = prepared("d".repeat(64), PriceTaxMode.EXCLUSIVE);
        var snapshot = PricingSourceFacts.freeze(scope, "H5_SESSION", source, "POS", "a".repeat(64),
                original.source().composition());
        var prepared = new Prepared(snapshot, original.rules(), original.tax(), original.goodsPolicies(),
                original.expiresAt(), original.preparationHash());
        var credentials = new Credentials("H5_SESSION", "secret", loggedIn ? "BUYER" : null);
        when(preparations.customer(source, credentials)).thenReturn(prepared);
        when(preparations.confirmCustomer(source, credentials, prepared.preparationHash())).thenReturn(prepared);
        store(prepared);
        var quote = service.quoteCustomer(source, credentials, Selection.none(), "KEY");
        service.getCustomer(source, credentials, "PQ");
        service.revalidateCustomer(source, credentials, "PQ", quote.confirmationDigest());
        org.mockito.Mockito.verify(quotes).save(org.mockito.ArgumentMatchers.argThat(context ->
                "H5_SESSION".equals(context.subjectNo()) && actor.equals(context.actorNo())), any(), eq("KEY"));
        org.mockito.Mockito.verify(quotes, org.mockito.Mockito.atLeastOnce()).get(
                org.mockito.ArgumentMatchers.argThat(context -> "H5_SESSION".equals(context.subjectNo())
                        && actor.equals(context.actorNo())), eq("PQ"));
    }

    @Test void customerReadAlwaysReauthorizesOriginalSourceCredentials() {
        var credentials = new Credentials("SESSION", "secret", null);
        when(preparations.customer(source, credentials)).thenThrow(BizException.of(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.getCustomer(source, credentials, "PQ"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(quotes);
    }
    @Test void anotherSourceInSameStoreCannotReadAnExistingQuote() throws Exception {
        var prepared = prepared("d".repeat(64), PriceTaxMode.EXCLUSIVE);
        when(preparations.staff(scope, source)).thenReturn(prepared);
        when(preparations.confirmStaff(scope, source, prepared.preparationHash())).thenReturn(prepared);
        store(prepared);
        service.quoteStaff(scope, "ACTOR", source, Selection.none(), "KEY");
        var other = new PricingSourceRef("FOOD", "CART", "OTHER", 4, null, null);
        var otherSnapshot = PricingSourceFacts.freeze(scope, null, other, "POS", "a".repeat(64),
                prepared.source().composition());
        var otherPrepared = new Prepared(otherSnapshot, prepared.rules(), prepared.tax(), prepared.goodsPolicies(),
                prepared.expiresAt(), "e".repeat(64));
        when(preparations.staff(scope, other)).thenReturn(otherPrepared);
        assertThatThrownBy(() -> service.getStaff(scope, "ACTOR", other, "PQ"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
}
