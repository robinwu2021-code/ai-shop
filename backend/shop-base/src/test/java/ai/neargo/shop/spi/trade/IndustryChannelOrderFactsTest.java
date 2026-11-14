package ai.neargo.shop.spi.trade;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.SourceQuantityUnits;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Channel;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Collection;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.CollectionFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Component;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Data;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Facts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Fulfillment;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.InvoiceLine;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Party;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.PlatformAcceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Totals;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class IndustryChannelOrderFactsTest {
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final Scope scope = new Scope("MAIN", "ENTITY", "STORE");

    Component main(BigDecimal quantity) {
        return new Component(
                "LINE",
                null,
                "MAIN",
                "NATIVE_LINE",
                "MAIN_COMPONENT",
                "MAIN",
                "GOODS",
                "MAIN_SKU",
                "Original dish",
                null,
                null,
                quantity,
                "KG",
                800,
                80,
                36,
                756,
                "ORIGINAL_PARENT_ALLOCATION");
    }

    Component topping() {
        return new Component(
                "TOPPING",
                "LINE",
                "OPTION",
                "NATIVE_LINE",
                "TOPPING_COMPONENT",
                "ADDON",
                "ADDON_GOODS",
                "ADDON_SKU",
                "Original topping",
                null,
                null,
                new BigDecimal("2.5"),
                "EA",
                200,
                20,
                9,
                189,
                "ORIGINAL_PARENT_ALLOCATION");
    }

    Data data(List<Component> components, Totals totals) {
        return new Data(
                scope,
                "FOOD",
                "NATIVE_CHANNEL_ORDER",
                1,
                "IMPORT",
                new Channel(
                        "MOCK",
                        "MOCK_PROFILE",
                        "a".repeat(64),
                        "EXTERNAL_ACCOUNT",
                        "EXTERNAL_STORE",
                        "AE"),
                "EXTERNAL_ORDER",
                "b".repeat(64),
                "ORIGINAL_PLATFORM_READ",
                "c".repeat(64),
                "AED",
                Collection.PLATFORM_COLLECTED,
                "ORIGINAL_PLATFORM_COLLECTION",
                Fulfillment.CHANNEL_DELIVERY,
                new Party("NATIVE_SOURCE_PARTY", "EXTERNAL_CUSTOMER", "Guest"),
                null,
                now.minusSeconds(60),
                List.of(new InvoiceLine("LINE", new BigDecimal("1.25"), "KG", 1000, 100, 45, 945)),
                components,
                totals);
    }

    Totals totals() {
        return new Totals(1000, 100, 45, 0, 945, 100, 20, 50, 915);
    }

    Reference reference(Data data) {
        return new Reference(
                scope, "FOOD", "NATIVE_CHANNEL_ORDER", 1, PricingSnapshotHash.of(data), "IMPORT");
    }

    Facts facts(Data data) {
        return new Facts(reference(data), data, now, now.plusSeconds(25), "ORIGINAL_OWNER_READ");
    }

    @Test
    void
            separatesPlatformCollectionMerchantReceivableAndCustomerPayableWhileConservingEveryComponent() {
        var data = data(List.of(main(new BigDecimal("1.25")), topping()), totals());
        assertDoesNotThrow(
                () -> IndustryChannelOrderFacts.validate(reference(data), facts(data), clock));
    }

    @Test
    void aSelfConsistentHashCannotHideAMoneyOrPhysicalQuantityMismatch() {
        var wrongTotal =
                data(
                        List.of(main(new BigDecimal("1.25")), topping()),
                        new Totals(1000, 100, 45, 0, 946, 100, 20, 50, 916));
        assertThrows(
                IllegalStateException.class,
                () ->
                        IndustryChannelOrderFacts.validate(
                                reference(wrongTotal), facts(wrongTotal), clock));
        var wrongQuantity = data(List.of(main(new BigDecimal("1.24")), topping()), totals());
        assertThrows(
                IllegalStateException.class,
                () ->
                        IndustryChannelOrderFacts.validate(
                                reference(wrongQuantity), facts(wrongQuantity), clock));
        var missingComponent = data(List.of(main(new BigDecimal("1.25"))), totals());
        assertThrows(
                IllegalStateException.class,
                () ->
                        IndustryChannelOrderFacts.validate(
                                reference(missingComponent), facts(missingComponent), clock));
    }

    @Test
    void anotherSourceOrStoreCannotBorrowAValidCaptureReceipt() {
        var data = data(List.of(main(new BigDecimal("1.25")), topping()), totals());
        var reference = reference(data);
        var wrongStore =
                new Reference(
                        new Scope("MAIN", "ENTITY", "OTHER"),
                        reference.sourceSystem(),
                        reference.sourceNo(),
                        reference.sourceVersion(),
                        reference.sourceHash(),
                        reference.importRef());
        assertThrows(
                IllegalStateException.class,
                () -> IndustryChannelOrderFacts.validate(wrongStore, facts(data), clock));
        var wrongHash =
                new Reference(
                        scope,
                        reference.sourceSystem(),
                        reference.sourceNo(),
                        reference.sourceVersion(),
                        "c".repeat(64),
                        reference.importRef());
        assertThrows(
                IllegalStateException.class,
                () -> IndustryChannelOrderFacts.validate(wrongHash, facts(data), clock));
    }

    @Test
    void expiredFutureAndUnboundedOwnerReadReceiptsCannotAuthorizePreparation() {
        var data = data(List.of(main(new BigDecimal("1.25")), topping()), totals());
        var reference = reference(data);
        for (var facts :
                List.of(
                        new Facts(reference, data, now.minusSeconds(30), now, "OWNER"),
                        new Facts(
                                reference, data, now.plusSeconds(1), now.plusSeconds(25), "OWNER"),
                        new Facts(reference, data, now, now.plusSeconds(31), "OWNER"))) {
            assertThrows(
                    IllegalStateException.class,
                    () -> IndustryChannelOrderFacts.validate(reference, facts, clock));
        }
    }

    @Test
    void paymentOnlyRevisionMayChangeDocumentButNotCompositionMoneyOrCollectedAmount() {
        var data = data(List.of(main(new BigDecimal("1.25")), topping()), totals());
        var ref = reference(data);
        var paid =
                new CollectionFacts(
                        ref,
                        data.externalOrderRef(),
                        "d".repeat(64),
                        data.externalFinancialHash(),
                        Collection.PLATFORM_COLLECTED,
                        915L,
                        "AED",
                        "ORIGINAL_NEW_COLLECTION",
                        now,
                        now.plusSeconds(25),
                        "READ");
        assertDoesNotThrow(() -> IndustryChannelOrderFacts.collection(ref, data, paid, clock));
        var wrongMoney =
                new CollectionFacts(
                        ref,
                        data.externalOrderRef(),
                        paid.externalDocumentHash(),
                        data.externalFinancialHash(),
                        Collection.PLATFORM_COLLECTED,
                        945L,
                        "AED",
                        "ORIGINAL_NEW_COLLECTION",
                        now,
                        now.plusSeconds(25),
                        "READ");
        assertThrows(
                IllegalStateException.class,
                () -> IndustryChannelOrderFacts.collection(ref, data, wrongMoney, clock));
        var changedBasket =
                new CollectionFacts(
                        ref,
                        data.externalOrderRef(),
                        paid.externalDocumentHash(),
                        "e".repeat(64),
                        Collection.PLATFORM_COLLECTED,
                        915L,
                        "AED",
                        "ORIGINAL_NEW_COLLECTION",
                        now,
                        now.plusSeconds(25),
                        "READ");
        assertThrows(
                IllegalStateException.class,
                () -> IndustryChannelOrderFacts.collection(ref, data, changedBasket, clock));
        var fakeZero =
                new CollectionFacts(
                        ref,
                        data.externalOrderRef(),
                        paid.externalDocumentHash(),
                        data.externalFinancialHash(),
                        Collection.UNPAID,
                        0L,
                        "AED",
                        null,
                        now,
                        now.plusSeconds(25),
                        "READ");
        assertThrows(
                IllegalStateException.class,
                () -> IndustryChannelOrderFacts.collection(ref, data, fakeZero, clock));
    }

    @Test
    void platformAcceptanceNeedsTheSameFinancialFingerprintAndOriginalReceipt() {
        var data = data(List.of(main(new BigDecimal("1.25")), topping()), totals());
        var ref = reference(data);
        var accepted =
                new PlatformAcceptance(
                        ref,
                        data.externalOrderRef(),
                        "d".repeat(64),
                        data.externalFinancialHash(),
                        "PLATFORM_ACCEPTED",
                        now,
                        now,
                        now.plusSeconds(25),
                        "OWNER_READ");
        assertDoesNotThrow(() -> IndustryChannelOrderFacts.acceptance(ref, data, accepted, clock));
        for (var proof :
                List.of(
                        new PlatformAcceptance(
                                ref,
                                "OTHER",
                                accepted.externalDocumentHash(),
                                data.externalFinancialHash(),
                                "PLATFORM_ACCEPTED",
                                now,
                                now,
                                now.plusSeconds(25),
                                "OWNER_READ"),
                        new PlatformAcceptance(
                                ref,
                                data.externalOrderRef(),
                                accepted.externalDocumentHash(),
                                "e".repeat(64),
                                "PLATFORM_ACCEPTED",
                                now,
                                now,
                                now.plusSeconds(25),
                                "OWNER_READ"),
                        new PlatformAcceptance(
                                ref,
                                data.externalOrderRef(),
                                accepted.externalDocumentHash(),
                                data.externalFinancialHash(),
                                "",
                                now,
                                now,
                                now.plusSeconds(25),
                                "OWNER_READ"))) {
            assertThrows(
                    IllegalStateException.class,
                    () -> IndustryChannelOrderFacts.acceptance(ref, data, proof, clock));
        }
    }

    @Test
    void anObservedAcceptedStateDoesNotInventAnUnavailableOriginalAcceptanceEventTime() {
        var data = data(List.of(main(new BigDecimal("1.25")), topping()), totals());
        var proof =
                new PlatformAcceptance(
                        reference(data),
                        data.externalOrderRef(),
                        "d".repeat(64),
                        data.externalFinancialHash(),
                        "ORIGINAL_ACCEPTED_ORDER_READ",
                        null,
                        now,
                        now.plusSeconds(25),
                        "OWNER_READ");
        assertDoesNotThrow(
                () -> IndustryChannelOrderFacts.acceptance(reference(data), data, proof, clock));
    }

    @Test
    void internalReferencesRejectCoercedVersionsNestedScopeFieldsAndPaidFlags() {
        var json = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
        var data = data(List.of(main(new BigDecimal("1.25")), topping()), totals());
        String valid =
                json.writeValueAsString(
                        new IndustryChannelOrderInternalApi.Request(reference(data)));
        assertDoesNotThrow(
                () -> json.readValue(valid, IndustryChannelOrderInternalApi.Request.class));
        for (var invalid :
                List.of(
                        valid.replace("\"sourceVersion\":1", "\"sourceVersion\":\"1\""),
                        valid.replace("\"sourceVersion\":1", "\"sourceVersion\":1.0"),
                        valid.replace("\"sourceVersion\":1", "\"sourceVersion\":null"),
                        valid.replace("\"sourceVersion\":1,", ""),
                        valid.replace(
                                "\"tenantNo\":\"MAIN\"", "\"tenantNo\":\"MAIN\",\"paid\":true"),
                        valid.replace(
                                "\"sourceSystem\":\"FOOD\"",
                                "\"sourceSystem\":\"FOOD\",\"paid\":true"),
                        valid.substring(0, valid.length() - 1) + ",\"paid\":true}")) {
            assertThrows(
                    RuntimeException.class,
                    () -> json.readValue(invalid, IndustryChannelOrderInternalApi.Request.class));
        }
    }

    @Test
    void countUnitAliasesDoNotIntroduceWeightOrVolumeConversions() {
        assertTrue(SourceQuantityUnits.same("EA", "PIECE"));
        assertFalse(SourceQuantityUnits.same("EA", "KG"));
        assertFalse(SourceQuantityUnits.same("G", "KG"));
        assertFalse(SourceQuantityUnits.same(null, null));
    }
}
