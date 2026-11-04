package ai.neargo.shop.pay.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.shop.pay.service.PlatformCollectionService;
import ai.neargo.shop.pay.service.PlatformRefundService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

class PlatformOfflineCollectionMysqlTest {
    @Test
    void offlineEntryPointsRejectMissingInputWithoutWritingMoney() throws Exception {
        try(var f=new PlatformCollectionMysqlTest.Fixture()) {
            assertThrows(IllegalArgumentException.class,()->f.service.configureOffline(null));
            assertThrows(IllegalArgumentException.class,()->f.service.confirmOffline(null));
            assertThrows(IllegalArgumentException.class,()->f.service.reconcileOffline(null));
            assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment",Integer.class));
        }
    }

    @Test
    void issuedInstructionsNeverAutoCloseAndActualBankReferenceCannotFundTwoOrders() throws Exception {
        try (var f = new Fixture()) {
            var terms = f.profile(1, "TWD");
            var a = f.request("A", terms);
            assertEquals("PENDING", f.base.service.create(a).state());
            assertEquals(0, f.base.sends.get());
            assertEquals("UNKNOWN", f.base.service.close(a.key(), a).state());
            var proof = f.proof(a, "bank-1", 10000, "TWD", "NORMAL");
            var receipt = f.base.service.confirmOffline(proof);
            assertEquals("MATCHED", receipt.disposition());
            assertEquals("SUCCESS", receipt.receipt().state());
            assertEquals(1000L, receipt.receipt().paidAtPrecisionMs());
            assertEquals(receipt, f.base.service.confirmOffline(proof));
            var b = f.request("B", terms);
            f.base.service.create(b);
            assertThrows(IllegalArgumentException.class, () -> f.base.service.confirmOffline(f.proof(b, "bank-1", 10000, "TWD", "NORMAL")));
            assertEquals(1, f.base.jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE status='SUCCESS' AND direction='PAY'", Integer.class));
        }
    }

    @Test
    void offlineRefundWaitsForRecordedPayoutAndNeverCallsAGateway() throws Exception {
        try (var f = new Fixture()) {
            var a = f.request("A", f.profile(1, "TWD"));
            f.base.service.create(a);
            var paid = f.base.service.confirmOffline(f.proof(a, "in-1", 10000, "TWD", "NORMAL")).receipt();
            var refunds = f.refunds();
            var key = new PlatformRefundService.Key("MAIN", "A", "refund-1");
            var request = new PlatformRefundService.Request(key, "S1", "A", paid.paymentNo(), "platform-mch", 10000, 4000, "TWD", "partial");
            assertEquals("PENDING", refunds.refund(request).state());
            assertEquals("OFFLINE_PAYOUT_PENDING", refunds.query(key).errorCode());
            org.mockito.Mockito.verify(f.base.gateway, org.mockito.Mockito.never()).refund(org.mockito.Mockito.any(), org.mockito.Mockito.anyLong(), org.mockito.Mockito.any(), org.mockito.Mockito.any());
            org.mockito.Mockito.verify(f.base.gateway, org.mockito.Mockito.never()).queryRefundOutcome(org.mockito.Mockito.any());
            assertThrows(IllegalArgumentException.class, () -> refunds.confirmOfflinePayout(f.payout(key, 3900, "out-1")));
            var done = refunds.confirmOfflinePayout(f.payout(key, 4000, "out-1"));
            assertEquals("SUCCESS", done.state());
            assertEquals(done, refunds.confirmOfflinePayout(f.payout(key, 4000, "out-1")));
            assertThrows(IllegalArgumentException.class, () -> refunds.confirmOfflinePayout(f.payout(key, 4000, "out-2")));
            assertEquals("PENDING", f.base.jdbc.queryForObject("SELECT platform_handoff_state FROM stl_payment WHERE payment_no=?", String.class, done.paymentNo()));
            var key2 = new PlatformRefundService.Key("MAIN", "A", "refund-2");
            refunds.refund(new PlatformRefundService.Request(key2, "S1", "A", paid.paymentNo(), "platform-mch", 10000, 6000, "TWD", "rest"));
            assertThrows(IllegalArgumentException.class, () -> refunds.confirmOfflinePayout(f.payout(key2, 6000, "out-1")));
            assertThrows(IllegalArgumentException.class, () -> refunds.refund(new PlatformRefundService.Request(
                    new PlatformRefundService.Key("MAIN", "A", "refund-3"), "S1", "A", paid.paymentNo(), "platform-mch", 10000, 1, "TWD", "over")));
        }
    }

    @Test
    void unmatchedCashIsReturnedByPayoutWithoutCommerceHandoff() throws Exception {
        try (var f = new Fixture()) {
            var a = f.request("A", f.profile(1, "TWD"));
            f.base.service.create(a);
            var stray = f.base.service.confirmOffline(f.proof(a, "stray", 9900, "TWD", "NORMAL")).receipt();
            var refunds = f.refunds();
            var key = new PlatformRefundService.Key("MAIN", "A", "return-stray");
            refunds.refund(new PlatformRefundService.Request(key, "S1", stray.key().attemptNo(), stray.paymentNo(), "platform-mch", 9900, 9900, "TWD", "return"));
            var done = refunds.confirmOfflinePayout(f.payout(key, 9900, "back-1"));
            assertEquals("SUCCESS", done.state());
            assertNull(f.base.jdbc.queryForObject("SELECT platform_handoff_state FROM stl_payment WHERE payment_no=?", String.class, done.paymentNo()));
            assertEquals("PENDING", f.base.service.query(a.key()).state());
        }
    }

    @Test
    void placedOrderIssuesItsFrozenProfileVersionAfterReconfiguration() throws Exception {
        try (var f = new Fixture()) {
            var v1 = f.profile(1, "TWD");
            var v2 = f.profile(2, "TWD");
            assertEquals(2, v2.version());
            var a = f.request("A", v1);
            assertEquals("PENDING", f.base.service.create(a).state());
            assertEquals(v1, f.base.service.query(a.key()).request().offline());
            var forged = new PlatformCollectionService.OfflineInstructions(v1.profileNo(), 9, v1.merchantNo(), v1.currency(),
                    v1.receivingAccountKey(), v1.paymentWindowVersion(), v1.paymentWindowMs(), v1.instructions());
            assertThrows(IllegalArgumentException.class, () -> f.base.service.create(f.request("B", forged)));
        }
    }

    @Test
    void secondRealDepositAfterPaymentIsRecordedAsUnmatchedNotLost() throws Exception {
        try (var f = new Fixture()) {
            var a = f.request("A", f.profile(1, "TWD"));
            f.base.service.create(a);
            assertEquals("MATCHED", f.base.service.confirmOffline(f.proof(a, "first", 10000, "TWD", "NORMAL")).disposition());
            var again = f.base.service.confirmOffline(f.proof(a, "second", 10000, "TWD", "NORMAL"));
            assertEquals("UNMATCHED", again.disposition());
            assertEquals("MALL_UNMATCHED", f.base.jdbc.queryForObject("SELECT pay_scene FROM stl_payment WHERE payment_no=?", String.class, again.receipt().paymentNo()));
            assertEquals(2, f.base.jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE status='SUCCESS' AND direction='PAY'", Integer.class));
            assertEquals("SUCCESS", f.base.service.query(a.key()).state());
            assertEquals(again, f.base.service.confirmOffline(f.proof(a, "second", 10000, "TWD", "NORMAL")));
        }
    }

    @Test
    void voidFenceSurvivesLateDiscoveryAndMismatchRemainsActualUnallocatedCash() throws Exception {
        try (var f = new Fixture()) {
            var a = f.request("A", f.profile(1, "TWD"));
            f.base.service.create(a);
            var close = new PlatformCollectionService.OfflineVoid(a.key(), "ops", "recon-1", "bank checked", "private-proof", 20000, Map.of());
            assertEquals("CLOSED", f.base.service.reconcileOffline(close).state());
            var late = f.base.service.confirmOffline(f.proof(a, "late-bank", 10000, "TWD", "NORMAL"));
            assertEquals("LATE", late.disposition());
            assertNotNull(late.receipt().closureReference());
            var b = f.request("B", a.offline());
            f.base.service.create(b);
            var mismatch = f.base.service.confirmOffline(f.proof(b, "difference", 9900, "TWD", "NORMAL"));
            assertEquals("UNMATCHED", mismatch.disposition());
            assertEquals(9900, mismatch.receipt().request().amountMinor());
            assertEquals("PENDING", f.base.service.query(b.key()).state());
            assertEquals("MALL_UNMATCHED", f.base.jdbc.queryForObject("SELECT pay_scene FROM stl_payment WHERE payment_no=?", String.class, mismatch.receipt().paymentNo()));
            assertThrows(IllegalArgumentException.class, () -> f.base.service.reconcileOffline(new PlatformCollectionService.OfflineVoid(b.key(), "ops", "r", "checked", "evidence", 20000, Map.of())));
        }
    }

    @Test
    void receivingAccountReferenceCannotBeReusedAcrossProfileVersionsOrCurrencies() throws Exception {
        try (var f = new Fixture()) {
            var a = f.request("A", f.profile(1, "TWD"));
            f.base.service.create(a);
            f.base.service.confirmOffline(f.proof(a, "same-funds", 10000, "TWD", "NORMAL"));
            var b = f.request("B", f.profile(2, "CNY"));
            f.base.service.create(b);
            assertThrows(IllegalArgumentException.class, () -> f.base.service.confirmOffline(f.proof(b, "same-funds", 10000, "CNY", "NORMAL")));
            assertEquals(a.offline().receivingAccountKey(), b.offline().receivingAccountKey());
            assertThrows(IllegalArgumentException.class, () -> f.base.service.confirmOffline(f.proof(b, "historical", 10000, "CNY", "NORMAL", 0)));
            assertEquals("MATCHED", f.base.service.confirmOffline(f.proof(b, "historical", 10000, "CNY", "HISTORICAL", 0)).disposition());
        }
    }

    static class Fixture implements AutoCloseable {
        final PlatformCollectionMysqlTest.Fixture base = new PlatformCollectionMysqlTest.Fixture();
        Fixture() throws Exception {
            String ddl = new ClassPathResource("schema-test.sql").getContentAsString(StandardCharsets.UTF_8);
            for (String table : List.of("pay_platform_collection_head", "pay_platform_collection_profile")) {
                var m = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS " + table + "\\s*\\(.*?\\n\\);").matcher(ddl);
                assertTrue(m.find()); base.jdbc.execute(m.group());
            }
        }
        PlatformCollectionService.OfflineInstructions profile(long version, String currency) {
            return base.service.configureOffline(new PlatformCollectionService.OfflineProfile("MAIN", "profile", version-1,
                    "platform-mch", currency, "TESTBANK", "TEST-ACCOUNT", "test-only", "w1", 86400000, true, "ops", "configured from test fixture"));
        }
        PlatformCollectionService.Request request(String order, PlatformCollectionService.OfflineInstructions terms) {
            return new PlatformCollectionService.Request(new PlatformCollectionService.Key("MAIN", order, order), "buyer", "platform-mch",
                    "OFFLINE", "BANK_TRANSFER", 10000, terms.currency(), 86401000, 1000, null, terms);
        }
        PlatformCollectionService.OfflineProof proof(PlatformCollectionService.Request r, String ref, long amount, String currency, String mode) {
            return proof(r, ref, amount, currency, mode, 5000);
        }
        PlatformCollectionService.OfflineProof proof(PlatformCollectionService.Request r, String ref, long amount, String currency, String mode, long at) {
            return new PlatformCollectionService.OfflineProof(r.key(), "ops", "platform-mch", amount, currency, at, ref, "private-proof", mode, 10000);
        }
        PlatformRefundServiceImpl refunds() {
            return new PlatformRefundServiceImpl(base.ds, new org.springframework.jdbc.datasource.DataSourceTransactionManager(base.ds), base.service, base.router);
        }
        PlatformRefundService.OfflinePayout payout(PlatformRefundService.Key key, long amount, String ref) {
            return new PlatformRefundService.OfflinePayout(key, "ops", amount, "TWD", 20000, "buyer-account", ref, "payout-proof", 30000);
        }
        public void close() { base.close(); }
    }
}
