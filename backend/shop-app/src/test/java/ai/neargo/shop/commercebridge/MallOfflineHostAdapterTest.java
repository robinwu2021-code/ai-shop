package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.offline.OfflineCollectionTerms;
import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.pay.service.PlatformCollectionService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.*;

/** Host offline rail: frozen terms are the only route evidence; dispositions come from the ledger. */
class MallOfflineHostAdapterTest {
    static final OfflineCollectionTerms TERMS =
            new OfflineCollectionTerms(
                    "profile", 3, "platform-mch", "TWD", "account-key", "w1", 86400000,
                    Map.of("bankCode", "TESTBANK"));

    static PaymentIntentCommand intent(String merchant, OfflineCollectionTerms terms) {
        return new PaymentIntentCommand(
                "MAIN",
                "order",
                merchant,
                new Money("TWD", 69900),
                Instant.ofEpochMilli(90_000_000),
                "attempt",
                "store-buyer",
                "OFFLINE",
                "BANK_TRANSFER",
                null,
                Instant.ofEpochMilli(1000),
                null,
                terms);
    }

    @Test
    void offlineRouteNeedsNoPayerBindingAndCarriesFrozenTermsToTheLedger() {
        var collection = mock(PlatformCollectionService.class);
        var key = new PlatformCollectionService.Key("MAIN", "order", "attempt");
        when(collection.create(any()))
                .thenAnswer(
                        a ->
                                new PlatformCollectionService.Receipt(
                                        key, "pay-1", a.getArgument(0), "PENDING", null, null,
                                        Map.of("bankCode", "TESTBANK"), null, null));
        var adapter =
                new ShopMallPlatformPaymentAdapter(collection, null, ShopMallPayerBindings.UNAVAILABLE);
        var outcome = adapter.create(intent("platform-mch", TERMS));
        assertEquals(AsyncStatus.PENDING, outcome.status());
        var sent = ArgumentCaptor.forClass(PlatformCollectionService.Request.class);
        verify(collection).create(sent.capture());
        assertNull(sent.getValue().payer());
        assertEquals(3, sent.getValue().offline().version());
        assertEquals("account-key", sent.getValue().offline().receivingAccountKey());
    }

    @Test
    void offlineIntentWhoseTermsNameAnotherMerchantNeverReachesTheLedger() {
        var collection = mock(PlatformCollectionService.class);
        var adapter =
                new ShopMallPlatformPaymentAdapter(collection, null, ShopMallPayerBindings.UNAVAILABLE);
        var outcome = adapter.create(intent("other-mch", TERMS));
        assertEquals(AsyncStatus.UNKNOWN, outcome.status());
        assertEquals(Optional.of("OFFLINE_INTENT_INVALID"), outcome.errorCode());
        verifyNoInteractions(collection);
    }

    @Test
    void manualConfirmationTranslatesUnitsAndReturnsTheLedgerDisposition() {
        var collection = mock(PlatformCollectionService.class);
        var key = new PlatformCollectionService.Key("MAIN", "order", "attempt");
        when(collection.confirmOffline(any()))
                .thenReturn(
                        new PlatformCollectionService.ManualReceipt(
                                "UNMATCHED",
                                new PlatformCollectionService.Receipt(
                                        key, "unmatched-row", null, "SUCCESS", null, 5000L, Map.of(),
                                        null, null)));
        var port = new ShopMallOfflineCollectionAdapter(collection);
        var result =
                port.confirm(
                        new OfflineCollectionPort.ManualPayment(
                                intent("platform-mch", TERMS),
                                "ops",
                                new Money("TWD", 69800),
                                Instant.ofEpochMilli(5000),
                                "platform-mch",
                                "bank-ref",
                                "evidence",
                                true,
                                Instant.ofEpochMilli(9000)));
        assertEquals("UNMATCHED", result.disposition());
        assertEquals("unmatched-row", result.paymentNo());
        var proof = ArgumentCaptor.forClass(PlatformCollectionService.OfflineProof.class);
        verify(collection).confirmOffline(proof.capture());
        assertEquals(69800, proof.getValue().actualMinor());
        assertEquals("TWD", proof.getValue().currency());
        assertEquals(5000, proof.getValue().actualPaidAt());
        assertEquals("HISTORICAL", proof.getValue().mode());
        assertEquals(key, proof.getValue().key());
    }

    @Test
    void manualRailRefusesNonOfflineOriginals() {
        var port = new ShopMallOfflineCollectionAdapter(mock(PlatformCollectionService.class));
        var online =
                new PaymentIntentCommand(
                        "MAIN", "order", "platform-mch", new Money("TWD", 1), Instant.ofEpochMilli(2),
                        "attempt", "buyer", "WECHAT", "JSAPI", null, Instant.ofEpochMilli(1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        port.reconcile(
                                new OfflineCollectionPort.Reconciliation(
                                        online, "ops", "ref", "reason", "evidence",
                                        Instant.ofEpochMilli(5), Map.of())));
    }

    @Test
    void termsAreReadFromThePublishedProfileOnly() {
        var collection = mock(PlatformCollectionService.class);
        when(collection.offlineInstructions("MAIN", "profile", "TWD"))
                .thenReturn(
                        Optional.of(
                                new PlatformCollectionService.OfflineInstructions(
                                        "profile", 3, "platform-mch", "TWD", "account-key", "w1",
                                        86400000, Map.of("bankCode", "TESTBANK"))));
        var port = new ShopMallOfflineCollectionAdapter(collection);
        assertEquals(Optional.of(TERMS), port.terms("MAIN", "profile", "TWD"));
        assertEquals(Optional.empty(), port.terms("MAIN", "profile", "CNY"));
    }

    @Test
    void offlineReadinessRequiresRouteTermsAndPositiveAmount() {
        var stock = new ShopMallHardwareInventoryAdapter(null, Map.of());
        var store =
                new StoreSnapshot(
                        new StoreRef("MAIN", "S"), "merchant", "RETAIL", StoreStatus.ACTIVE,
                        CommerceModel.STORE_V1);
        var off =
                new ShopMallCheckoutReadiness(
                        true, stock, List::of, ShopMallPayerBindings.UNAVAILABLE, false,
                        "platform-mch");
        assertEquals(
                List.of("OFFLINE_UNAVAILABLE"),
                off.evaluateOffline(store, new Money("TWD", 69900), List.of(), TERMS).reasons());
        var on =
                new ShopMallCheckoutReadiness(
                        true, stock, List::of, ShopMallPayerBindings.UNAVAILABLE, true,
                        "platform-mch");
        var ready = on.evaluateOffline(store, new Money("TWD", 69900), List.of(), TERMS);
        assertTrue(ready.available());
        assertEquals("OFFLINE", ready.paymentOptions().getFirst().channel());
        assertEquals(
                List.of("OFFLINE_NOT_REQUIRED"),
                on.evaluateOffline(store, new Money("TWD", 0), List.of(), TERMS).reasons());
        assertEquals(
                List.of("OFFLINE_PROFILE_MISMATCH"),
                on.evaluateOffline(store, new Money("CNY", 100), List.of(), TERMS).reasons());
        var otherRecipient =
                new ShopMallCheckoutReadiness(
                        true, stock, List::of, ShopMallPayerBindings.UNAVAILABLE, true, "other");
        assertFalse(
                otherRecipient
                        .evaluateOffline(store, new Money("TWD", 69900), List.of(), TERMS)
                        .available());
    }
}
