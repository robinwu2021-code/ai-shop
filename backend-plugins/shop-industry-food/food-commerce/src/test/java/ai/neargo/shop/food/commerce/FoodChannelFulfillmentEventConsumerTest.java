package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.RetryableOutboxException;
import ai.neargo.shop.food.commerce.event.FoodChannelFulfillmentEventConsumer;
import ai.neargo.shop.food.service.DiningChannelFulfillmentSignalPort;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerPort;
import ai.neargo.shop.spi.food.DiningServiceEvent;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

class FoodChannelFulfillmentEventConsumerTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final DiningServiceEvent event =
            new DiningServiceEvent(
                    "ORIGINAL_SERVICE_EVENT",
                    "E",
                    "S",
                    "BATCH",
                    "TICKET",
                    "TASK",
                    "LINE",
                    "COMPONENT",
                    "STATION",
                    "MAKE",
                    1,
                    "CUSTODY_HANDOVER",
                    new BigDecimal("0.5"),
                    "Actual handover",
                    "STAFF",
                    now.minusSeconds(1));

    OutboxEnvelope envelope() {
        return new OutboxEnvelope(
                "OUTBOX_ID",
                "PLATFORM",
                "OUTBOX_ID",
                "FOOD_KITCHEN_TASK",
                "TASK",
                "DINING_SERVICE_EVENT",
                1,
                "FOOD_KITCHEN_TASK:TASK",
                0,
                json.writeValueAsString(event),
                now);
    }

    DiningChannelFulfillmentSignalPort.Signal signal(
            DiningChannelFulfillmentSignalPort.Status status) {
        String imported =
                status == DiningChannelFulfillmentSignalPort.Status.READY ? "IMPORT" : null;
        String batch = imported == null ? null : "BATCH",
                core = imported == null ? null : "CORE",
                handover = imported == null ? null : "HANDOVER",
                revision = imported == null ? null : "CUSTODY_V1:" + "a".repeat(64);
        String receipt =
                "FOOD:FULFILLMENT_SIGNAL:"
                        + PricingSnapshotHash.of(
                                List.of(
                                        status,
                                        event,
                                        Objects.toString(imported, ""),
                                        Objects.toString(batch, ""),
                                        Objects.toString(core, ""),
                                        Objects.toString(handover, ""),
                                        Objects.toString(revision, "")));
        return new DiningChannelFulfillmentSignalPort.Signal(
                status,
                event,
                imported,
                batch,
                core,
                handover,
                revision,
                now,
                now.plusSeconds(30),
                receipt);
    }

    @Test
    void originalImportPendingRetriesAndOnlyADurableIntentReceiptAcknowledgesTheEvent() {
        var calls = new AtomicInteger();
        ChannelFulfillmentTriggerPort trigger =
                request -> {
                    calls.incrementAndGet();
                    assertThat(request.originalEventNo()).isEqualTo("ORIGINAL_SERVICE_EVENT");
                    return calls.get() == 1
                            ? new ChannelFulfillmentTriggerPort.Result(
                                    request,
                                    ChannelFulfillmentTriggerPort.Status.WAITING_IMPORT,
                                    null,
                                    null)
                            : new ChannelFulfillmentTriggerPort.Result(
                                    request,
                                    ChannelFulfillmentTriggerPort.Status.RECORDED,
                                    "OPERATION",
                                    "QUEUED");
                };
        var handler =
                new FoodChannelFulfillmentEventConsumer(
                        e -> signal(DiningChannelFulfillmentSignalPort.Status.READY),
                        trigger,
                        json,
                        clock);
        assertThat(handler.externalWorkflow()).isTrue();
        assertThat(handler.handle(envelope()).kind()).isEqualTo(DeliveryResult.Kind.RETRYABLE);
        assertThat(handler.handle(envelope()).kind()).isEqualTo(DeliveryResult.Kind.SENT);
    }

    @Test
    void actualOrdinaryBusinessSkipsCimAndTransportFailureRemainsSafelyRetryable() {
        var calls = new AtomicInteger();
        ChannelFulfillmentTriggerPort trigger =
                request -> {
                    calls.incrementAndGet();
                    throw new AssertionError("Ordinary business cannot publish");
                };
        var ordinary =
                new FoodChannelFulfillmentEventConsumer(
                        e -> signal(DiningChannelFulfillmentSignalPort.Status.NOT_CHANNEL),
                        trigger,
                        json,
                        clock);
        assertThat(ordinary.handle(envelope()).kind()).isEqualTo(DeliveryResult.Kind.SENT);
        assertThat(calls).hasValue(0);
        var unavailable =
                new FoodChannelFulfillmentEventConsumer(
                        e -> {
                            throw new IllegalStateException("Original FOOD unavailable");
                        },
                        trigger,
                        json,
                        clock);
        assertThatThrownBy(() -> unavailable.handle(envelope()))
                .isInstanceOf(RetryableOutboxException.class);
    }
}
