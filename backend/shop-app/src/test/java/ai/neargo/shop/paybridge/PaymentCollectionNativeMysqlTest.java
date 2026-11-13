package ai.neargo.shop.paybridge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PaymentCollectionNativeMysqlTest {
    @Test
    void signedContractRejectsMutationAndWrongEnvironment() {
        var p = new PaymentCollectionProperties();
        p.setSecret("test-secret-32-characters-at-least");
        p.setCursorSecret("cursor-secret-32-characters-at-least");
        var auth =
                new PaymentCollectionSecurity(
                        p,
                        java.time.Clock.fixed(
                                java.time.Instant.ofEpochSecond(1000), java.time.ZoneOffset.UTC));
        String nonce = java.util.UUID.randomUUID().toString();
        String signature = auth.signature("1000", nonce, "SANDBOX", "{}");
        auth.verify("1000", nonce, "SANDBOX", signature, "{}");
        assertThrows(
                RuntimeException.class,
                () -> auth.verify("1000", nonce, "SANDBOX", signature, "{ }"));
        assertThrows(
                RuntimeException.class,
                () -> auth.verify("1000", nonce, "PRODUCTION", signature, "{}"));
        assertThrows(
                RuntimeException.class,
                () -> auth.verify("699", nonce, "SANDBOX", signature, "{}"));
        String cursor = auth.cursor("SANDBOX|MAIN|USR|u|e|s", 4);
        assertEquals(4, auth.sequence(cursor, "SANDBOX|MAIN|USR|u|e|s"));
        assertThrows(
                RuntimeException.class, () -> auth.sequence(cursor, "SANDBOX|MAIN|USR|u|e|other"));
        assertThrows(
                RuntimeException.class,
                () -> auth.sequence(cursor.substring(1), "SANDBOX|MAIN|USR|u|e|s"));
    }

    @Test
    void enablingReceiverWithLegacyOutboxFailsClosedAtConfigurationBoundary() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(
                        ai.neargo.shop.event.outbox.runtime.OutboxProperties.class,
                        ai.neargo.shop.event.outbox.runtime.OutboxProperties::new)
                .withPropertyValues(
                        "shop.payment-collections.enabled=true",
                        "shop.payment-collections.secret=sandbox-test-secret-at-least-32-characters",
                        "shop.payment-collections.cursor-secret=sandbox-test-cursor-at-least-32-characters")
                .withUserConfiguration(PaymentCollectionConfiguration.class)
                .run(
                        context -> {
                            assertNotNull(context.getStartupFailure());
                            assertTrue(
                                    org.springframework.core.NestedExceptionUtils
                                            .getMostSpecificCause(context.getStartupFailure())
                                            .getMessage()
                                            .contains("shop.outbox.sources.platform.mode=unified"));
                        });
    }

    @Test
    void sendQueueMustHaveSpaceForBusinessAndControlFrames() {
        var p = new PaymentCollectionProperties();
        p.setSendQueueCapacity(1);
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertTrue(
                    factory.getValidator().validate(p).stream()
                            .anyMatch(
                                    v ->
                                            v.getPropertyPath()
                                                    .toString()
                                                    .equals("sendQueueCapacity")));
            p.setSendQueueCapacity(2);
            assertFalse(
                    factory.getValidator().validate(p).stream()
                            .anyMatch(
                                    v ->
                                            v.getPropertyPath()
                                                    .toString()
                                                    .equals("sendQueueCapacity")));
        }
    }
}
