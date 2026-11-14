package ai.neargo.commerce.api;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class TenantOperationContractTest {
    private void compileAndRun(String name, String source) throws Exception {
        var root = Files.createTempDirectory("commerce-tenant-contract");
        try {
            var file = root.resolve(name + ".java");
            Files.writeString(file, source);
            var errors = new java.io.ByteArrayOutputStream();
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors,
                    "-classpath", System.getProperty("java.class.path"), "-d", root.toString(), file.toString()),
                    errors.toString());
            try (var loader = new java.net.URLClassLoader(new java.net.URL[]{root.toUri().toURL()}, getClass().getClassLoader())) {
                var method = loader.loadClass(name).getDeclaredMethod("verify");
                method.setAccessible(true);
                assertEquals(true, method.invoke(null));
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @Test
    void trustedCallbacksAndRecoveryRouteDuplicateBusinessNumbersByTenant() throws Exception {
        compileAndRun("TenantEndpoints", """
            import ai.neargo.commerce.api.common.*;
            import ai.neargo.commerce.api.fulfillment.*;
            import ai.neargo.commerce.api.reminder.*;
            import ai.neargo.commerce.api.port.*;
            import java.time.*;
            import java.util.*;
            class TenantEndpoints implements FulfillmentFacade, ReminderFacade, CommerceNotificationPort {
                record Key(String tenant, String number) {}
                final Map<Key, Long> orders = Map.of(new Key("A", "order"), 11L, new Key("B", "order"), 22L);
                final Map<Key, NoticeView> notices = new HashMap<>();
                final Map<Key, String> receipts = new HashMap<>();
                TenantEndpoints() {
                    for (String tenant : List.of("A", "B")) {
                        var store = new StoreRef(tenant, "store");
                        notices.put(new Key(tenant, "notice"), new NoticeView("notice", store, "POS", Instant.EPOCH,
                            "D0", "ACCOUNT", "recipient", "PUSH", NoticeStatus.UNKNOWN, 1, Optional.empty(), Optional.empty()));
                    }
                }
                public FulfillmentResult confirmPayment(VerifiedPaymentFact fact) {
                    return new FulfillmentResult(fact.orderNo(), AsyncStatus.SUCCEEDED, AsyncStatus.PENDING,
                        orders.get(new Key(fact.tenantNo(), fact.orderNo())));
                }
                public NoticeView deliver(String tenantNo, String noticeNo) { return notices.get(new Key(tenantNo, noticeNo)); }
                public NoticeView recover(String tenantNo, String noticeNo) { return notices.get(new Key(tenantNo, noticeNo)); }
                public DeliveryOutcome send(NotificationCommand command) {
                    receipts.put(new Key(command.store().tenantNo(), command.deduplicationKey()), command.store().tenantNo() + "-receipt");
                    return new DeliveryOutcome(DeliveryStatus.UNKNOWN, Optional.empty(), Optional.empty());
                }
                public DeliveryOutcome queryReceipt(NotificationReceiptQuery query) {
                    return new DeliveryOutcome(DeliveryStatus.SENT,
                        Optional.ofNullable(receipts.get(new Key(query.store().tenantNo(), query.deduplicationKey()))), Optional.empty());
                }
                public FulfillmentResult fulfill(FulfillmentQuery query) { throw new UnsupportedOperationException(); }
                public FulfillmentResult confirmShipment(HardwareShipmentCommand command) { throw new UnsupportedOperationException(); }
                public ReminderScanResult scan(ReminderScanCommand command) { throw new UnsupportedOperationException(); }
                public NoticePage notices(ActorContext actor, NoticeQuery query) { throw new UnsupportedOperationException(); }
                public NoticeView retry(ActorContext actor, ReminderRetryCommand command) { throw new UnsupportedOperationException(); }
                public List<NotificationRecipient> audience(NotificationAudienceQuery query) { throw new UnsupportedOperationException(); }
                static boolean verify() {
                    var endpoints = new TenantEndpoints();
                    FulfillmentFacade payments = endpoints;
                    ReminderFacade reminders = endpoints;
                    CommerceNotificationPort notifications = endpoints;
                    var recipient = new NotificationRecipient("ACCOUNT", "recipient", "PUSH");
                    for (String tenant : List.of("A", "B")) {
                        // All persisted business numbers intentionally collide; tenant comes from the trusted caller.
                        var fact = new VerifiedPaymentFact(tenant, "event", "order", "payment", "merchant",
                            new Money("CNY", 100L), Instant.EPOCH, "verified");
                        if (payments.confirmPayment(fact).entitlementVersion() != (tenant.equals("A") ? 11L : 22L)) return false;
                        if (!reminders.deliver(tenant, "notice").store().equals(new StoreRef(tenant, "store"))) return false;
                        if (!reminders.recover(tenant, "notice").store().equals(new StoreRef(tenant, "store"))) return false;
                        notifications.send(new NotificationCommand("notice", "dedup", new StoreRef(tenant, "store"),
                            "POS", Instant.EPOCH, "D0", recipient, "template"));
                    }
                    for (String tenant : List.of("B", "A")) {
                        var receipt = notifications.queryReceipt(new NotificationReceiptQuery("notice", "dedup",
                            new StoreRef(tenant, "store"), recipient, Optional.empty()));
                        if (!receipt.providerMessageNo().orElseThrow().equals(tenant + "-receipt")) return false;
                    }
                    return true;
                }
            }
            """);
    }

    @Test
    void accessDecisionsExposeMissingRevokedAndUnavailableAsTypedCommercialOutcomes() throws Exception {
        compileAndRun("AccessConsumer", """
            import ai.neargo.commerce.api.access.*;
            import ai.neargo.commerce.api.common.*;
            import java.time.*;
            import java.util.*;
            class AccessConsumer {
                static String nextAction(AccessDecision decision) {
                    return switch (decision.status()) {
                        case NOT_PURCHASED -> "PURCHASE";
                        case REVOKED -> "CONTACT_SUPPORT";
                        case CHECK_UNAVAILABLE -> "RETRY_CHECK";
                        default -> "OTHER";
                    };
                }
                static boolean verify() {
                    var store = new StoreRef("A", "store");
                    return nextAction(new AccessDecision(AccessStatus.NOT_PURCHASED, store, "POS", 1L,
                            Instant.EPOCH, Optional.empty(), Optional.empty())).equals("PURCHASE")
                        && nextAction(new AccessDecision(AccessStatus.REVOKED, store, "POS", 1L,
                            Instant.EPOCH, Optional.empty(), Optional.empty())).equals("CONTACT_SUPPORT")
                        && nextAction(new AccessDecision(AccessStatus.CHECK_UNAVAILABLE, store, "POS", 1L,
                            Instant.EPOCH, Optional.empty(), Optional.empty())).equals("RETRY_CHECK");
                }
            }
            """);
    }
}
