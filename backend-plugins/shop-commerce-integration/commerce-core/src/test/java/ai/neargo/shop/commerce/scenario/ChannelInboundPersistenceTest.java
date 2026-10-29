package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.service.ChannelInboundService;
import ai.neargo.shop.commerce.service.impl.ChannelInboundServiceImpl;
import ai.neargo.shop.spi.commerce.ChannelInboundGateway;
import ai.neargo.shop.spi.commerce.ChannelInboundHandler;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

class ChannelInboundPersistenceTest {
    static final class Gateway implements ChannelInboundGateway {
        boolean rejected;
        String eventRef = "REMOTE_EVENT";

        public String profileNo() {
            return "PP";
        }

        public Set<Capability> capabilities() {
            return Set.of(Capability.ORDER);
        }

        public Verified verify(Endpoint endpoint, RawMessage message) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (rejected) throw new VerificationRejected();
            return new Verified(
                    eventRef,
                    "READY:" + eventRef,
                    "READY",
                    endpoint.externalStoreRef(),
                    "ORDER",
                    "REV1",
                    message.receivedAt(),
                    "AUTH",
                    "TEST_ORIGINAL_SIGNATURE_RECEIPT");
        }

        public Ack acknowledge(Verified event) {
            return new Ack(200, "application/json", "{\"ok\":true}");
        }
    }

    static final class Handler implements ChannelInboundHandler {
        final AtomicInteger calls = new AtomicInteger();
        boolean enabled = true, unavailable;
        Runnable during;

        public String profileNo() {
            return "PP";
        }

        public boolean supports(String type) {
            return enabled && "READY".equals(type);
        }

        public Result handle(Event event) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(new String(event.originalBody(), StandardCharsets.UTF_8))
                    .contains("original");
            calls.incrementAndGet();
            if (during != null) {
                var hook = during;
                during = null;
                hook.run();
            }
            if (unavailable) throw new IllegalStateException("owner unavailable");
            return new Result(Outcome.PROCESSED, "ORIGINAL_OWNER:" + event.eventNo());
        }
    }

    ChannelInboundGateway.RawMessage message(String body) {
        return new ChannelInboundGateway.RawMessage(
                body.getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Type", "application/json"),
                Instant.now());
    }

    void run(Gateway gateway, Handler handler, Consumer<CatalogSynchronizationTest.State> test) {
        new CatalogSynchronizationTest()
                .runWith(
                        runner ->
                                runner.withBean(ChannelInboundGateway.class, () -> gateway)
                                        .withBean(ChannelInboundHandler.class, () -> handler),
                        test);
    }

    @Test
    void commitBeforeAckKeepsExactBytesAndDedupeNeverDropsConflicts() {
        run(
                new Gateway(),
                new Handler(),
                s -> {
                    var service = s.context().getBean(ChannelInboundService.class);
                    String original = "{  \"original\":true, \"amount\": 1 }";
                    assertThat(service.receive("TEST", "CON", message(original)).status())
                            .isEqualTo(200);
                    assertThat(service.receive("TEST", "CON", message(original)).status())
                            .isEqualTo(200);
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_inbound_event",
                                                    Integer.class))
                            .isOne();
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_inbound_receipt",
                                                    Integer.class))
                            .isEqualTo(2);
                    var stored =
                            s.sql()
                                    .queryForObject(
                                            "SELECT payload_base64 FROM cim_inbound_receipt WHERE"
                                                    + " classification='ORIGINAL'",
                                            String.class);
                    assertThat(Base64.getDecoder().decode(stored))
                            .isEqualTo(original.getBytes(StandardCharsets.UTF_8));
                    service.receive("TEST", "CON", message("{\"original\":true,\"amount\":2}"));
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT status FROM cim_inbound_event",
                                                    String.class))
                            .isEqualTo("CONFLICT");
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_inbound_receipt WHERE"
                                                            + " classification='CONFLICT'",
                                                    Integer.class))
                            .isOne();
                });
    }

    @Test
    void invalidSignatureOrReceiptWriteFailureDoesNotAcknowledgeAnUnpersistedEvent() {
        var gateway = new Gateway();
        run(
                gateway,
                new Handler(),
                s -> {
                    var service = s.context().getBean(ChannelInboundService.class);
                    gateway.rejected = true;
                    assertThatThrownBy(() -> service.receive("TEST", "CON", message("original")))
                            .isInstanceOf(ChannelInboundGateway.VerificationRejected.class);
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_inbound_event",
                                                    Integer.class))
                            .isZero();
                    gateway.rejected = false;
                    s.sql()
                            .execute(
                                    "ALTER TABLE cim_inbound_receipt ADD CONSTRAINT refuse_receipt"
                                            + " CHECK (signature_result='REFUSED')");
                    assertThatThrownBy(() -> service.receive("TEST", "CON", message("original")))
                            .isInstanceOf(RuntimeException.class);
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_inbound_event",
                                                    Integer.class))
                            .isZero();
                    s.sql()
                            .execute(
                                    "ALTER TABLE cim_inbound_receipt DROP CONSTRAINT"
                                            + " refuse_receipt");
                    assertThat(service.receive("TEST", "CON", message("original")).status())
                            .isEqualTo(200);
                });
    }

    @Test
    void durableRetryAndMissingOwnerStayReplayableAndCompletedEventIsNotDispatchedTwice() {
        var handler = new Handler();
        handler.enabled = false;
        run(
                new Gateway(),
                handler,
                s -> {
                    var service = s.context().getBean(ChannelInboundServiceImpl.class);
                    service.receive("TEST", "CON", message("original"));
                    // The worker test controls due time; DATETIME(3) may round a just-written
                    // timestamp.
                    s.sql()
                            .execute(
                                    "UPDATE cim_inbound_event SET next_attempt_at='2000-01-01"
                                            + " 00:00:00'");
                    assertThat(service.recoverDue(10)).isZero();
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT status FROM cim_inbound_event",
                                                    String.class))
                            .isEqualTo("WAITING_OWNER");
                    handler.enabled = true;
                    handler.unavailable = true;
                    s.sql()
                            .execute(
                                    "UPDATE cim_inbound_event SET next_attempt_at='2000-01-01"
                                            + " 00:00:00'");
                    assertThat(service.recoverDue(10)).isZero();
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT status FROM cim_inbound_event",
                                                    String.class))
                            .isEqualTo("RETRY");
                    handler.unavailable = false;
                    s.sql()
                            .execute(
                                    "UPDATE cim_inbound_event SET next_attempt_at='2000-01-01"
                                            + " 00:00:00'");
                    assertThat(service.recoverDue(10)).isOne();
                    assertThat(service.recoverDue(10)).isZero();
                    assertThat(handler.calls.get()).isEqualTo(2);
                });
    }

    @Test
    void aDamagedOriginalReceiptDoesNotBlockAnotherDurableEventInTheSameBatch() {
        var gateway = new Gateway();
        var handler = new Handler();
        run(
                gateway,
                handler,
                s -> {
                    var service = s.context().getBean(ChannelInboundServiceImpl.class);
                    service.receive("TEST", "CON", message("original first"));
                    s.sql()
                            .execute(
                                    "UPDATE cim_inbound_receipt SET payload_base64='invalid"
                                            + " base64!'");
                    gateway.eventRef = "REMOTE_EVENT2";
                    service.receive("TEST", "CON", message("original second"));
                    s.sql()
                            .execute(
                                    "UPDATE cim_inbound_event SET next_attempt_at='2000-01-01"
                                            + " 00:00:00'");
                    assertThat(service.recoverDue(10))
                            .as(
                                    "Original event states: %s",
                                    s.sql()
                                            .queryForList(
                                                    "SELECT event_no,status,attempt_no,fence FROM"
                                                            + " cim_inbound_event ORDER BY id"))
                            .isOne();
                    assertThat(handler.calls.get()).isOne();
                    assertThat(
                                    s.sql()
                                            .queryForList(
                                                    "SELECT status FROM cim_inbound_event ORDER BY"
                                                            + " id",
                                                    String.class))
                            .containsExactly("RETRY", "PROCESSED");
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_inbound_event WHERE"
                                                        + " status='RETRY' AND next_attempt_at > ?",
                                                    Integer.class,
                                                    LocalDateTime.now(ZoneOffset.UTC)))
                            .isOne();
                });
    }

    @Test
    void
            callbackRetentionUsesTheExplicitConfigurationAndKeepsRawEvidenceUntilASeparateRetentionAction() {
        var gateway = new Gateway();
        new CatalogSynchronizationTest()
                .runWith(
                        r ->
                                r.withBean(ChannelInboundGateway.class, () -> gateway)
                                        .withPropertyValues(
                                                "shop.commerce.callbacks.raw-retention-days=7"),
                        s -> {
                            var service = s.context().getBean(ChannelInboundServiceImpl.class);
                            service.receive("TEST", "CON", message("original"));
                            var dates =
                                    s.sql()
                                            .queryForMap(
                                                    "SELECT"
                                                        + " received_at,retention_until,payload_base64"
                                                        + " FROM cim_inbound_receipt");
                            var received =
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT received_at FROM cim_inbound_receipt",
                                                    LocalDateTime.class);
                            var deadline =
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT retention_until FROM"
                                                            + " cim_inbound_receipt",
                                                    LocalDateTime.class);
                            assertThat(deadline).isEqualTo(received.plusDays(7));
                            assertThat(dates.get("PAYLOAD_BASE64")).isNotNull();
                        });
    }

    @Test
    void conflictingArrivalDuringOwnerCallFencesTheOldResult() {
        var handler = new Handler();
        run(
                new Gateway(),
                handler,
                s -> {
                    var service = s.context().getBean(ChannelInboundServiceImpl.class);
                    service.receive("TEST", "CON", message("original"));
                    handler.during =
                            () -> service.receive("TEST", "CON", message("conflicting original"));
                    assertThat(service.recoverDue(10)).isZero();
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT status FROM cim_inbound_event",
                                                    String.class))
                            .isEqualTo("CONFLICT");
                });
    }
}
