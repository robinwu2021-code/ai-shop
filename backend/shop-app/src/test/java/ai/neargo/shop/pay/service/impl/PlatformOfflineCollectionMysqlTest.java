package ai.neargo.shop.pay.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.shop.pay.service.PlatformCollectionService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

class PlatformOfflineCollectionMysqlTest {
    @Test
    void unfinishedOfflineEntryPointsAreDisabledByDefault() throws Exception {
        try(var f=new PlatformCollectionMysqlTest.Fixture()) {
            assertThrows(IllegalStateException.class,()->f.service.configureOffline(null));
            assertThrows(IllegalStateException.class,()->f.service.confirmOffline(null));
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
        public void close() { base.close(); }
    }
}
