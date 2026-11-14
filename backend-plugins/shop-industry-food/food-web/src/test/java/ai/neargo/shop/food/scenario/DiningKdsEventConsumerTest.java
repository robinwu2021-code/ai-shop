package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.food.dto.FoodKdsContracts.EventDraft;
import ai.neargo.shop.food.event.DiningKdsEventConsumer;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.service.FoodPrintService;
import ai.neargo.shop.spi.food.DiningOrderAccepted;
import ai.neargo.shop.spi.food.DiningServiceEvent;
import ai.neargo.shop.spi.printing.PrintPort;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Projection/privacy test for the actual original-event consumer, not a synthesized queue update.
 * Execution deferred.
 */
class DiningKdsEventConsumerTest {
    @Test
    void newCustodyFactStillProjectsToKdsWithoutAccessingPrintOwner() {
        var service = mock(FoodKdsService.class); var json = JsonMapper.builder().build();
        var consumer = new DiningKdsEventConsumer(service, json);
        var source = new DiningServiceEvent("FSEV", "E", "S", "B", "K", "T", "L", null, "ST", "MAKE", 1,
                "CUSTODY_HANDOVER", BigDecimal.ONE, null, "A", Instant.parse("2026-10-30T00:00:00Z"));
        assertThat(source.eventType()).isEqualTo("DINING_CUSTODY_HANDOVER");
        var envelope = new OutboxEnvelope("O", "PLATFORM", "O", source.aggregateType(), source.aggregateId(), source.eventType(), 1,
                "FOOD_KITCHEN_TASK:T", 1, json.writeValueAsString(source), source.occurredAt());
        assertThat(consumer.handle(envelope).kind()).isEqualTo(ai.neargo.shop.event.outbox.DeliveryResult.Kind.SENT);
        var captured = ArgumentCaptor.forClass(EventDraft.class); verify(service).append(captured.capture());
        assertThat(captured.getValue().eventType()).isEqualTo("KITCHEN_ACTION");
        assertThat(json.readTree(captured.getValue().payload()).path("action").asString()).isEqualTo("CUSTODY_HANDOVER");
    }

    @Test
    void historicalStationDeliveryStillProjectsWithoutSelectingNewEventsOrAccessingPrinting() {
        var service = mock(FoodKdsService.class);
        var json = JsonMapper.builder().build();
        var source = new ai.neargo.shop.spi.food.DiningStationRuntimeChanged("E", "S", "ST", "CMD", "PAUSE", 3,
                "PAUSED", "hold", Instant.parse("2026-10-30T00:00:00Z"));
        var consumer = new DiningKdsEventConsumer(service, json);
        assertThat(consumer.supports(source.eventType())).isFalse();
        var envelope = new OutboxEnvelope("OLD", "PLATFORM", "OLD", "FOOD_STATION_RUNTIME", "ST", source.eventType(), 1,
                "FOOD_STATION:ST", 7, json.writeValueAsString(source), source.occurredAt());
        assertThat(consumer.handle(envelope).kind()).isEqualTo(ai.neargo.shop.event.outbox.DeliveryResult.Kind.SENT);
        var captured = ArgumentCaptor.forClass(EventDraft.class);
        verify(service).append(captured.capture());
        assertThat(captured.getValue().eventNo()).isEqualTo("OLD");
        assertThat(captured.getValue().sourceNo()).isEqualTo("CMD");
        assertThat(captured.getValue().sourceVersion()).isEqualTo(3);
    }

    @Test
    void projectOriginalKitchenIdentityAndQuantityWithoutInventingCompletion() {
        var service = mock(FoodKdsService.class);
        var printing = mock(PrintPort.class);
        var documents = mock(FoodPrintService.class);
        var json = JsonMapper.builder().build();
        when(printing.configuration(any()))
                .thenReturn(new PrintPort.Configuration(List.of(), List.of(), List.of()));
        var consumer = new DiningKdsEventConsumer(service, json, () -> printing, () -> documents);
        var source =
                new DiningServiceEvent(
                        "FSEV",
                        "E",
                        "S",
                        "BATCH",
                        "TICKET",
                        "TASK",
                        "LINE",
                        "COMP",
                        "ST",
                        "MAKE",
                        1,
                        "READY",
                        new BigDecimal("0.5"),
                        "ready",
                        "ACTOR",
                        Instant.parse("2026-10-24T00:00:00Z"));
        var envelope =
                new OutboxEnvelope(
                        "EV",
                        "PLATFORM",
                        "EV",
                        "FOOD_KITCHEN_TASK",
                        "TASK",
                        "DINING_SERVICE_EVENT",
                        1,
                        "TASK:TASK",
                        2,
                        json.writeValueAsString(source),
                        source.occurredAt());
        consumer.handle(envelope);
        var capture = ArgumentCaptor.forClass(EventDraft.class);
        verify(service).append(capture.capture());
        var event = capture.getValue();
        assertThat(event.eventNo()).isEqualTo("EV");
        assertThat(event.stationNo()).isEqualTo("ST");
        var body = json.readTree(event.payload());
        assertThat(body.path("action").asString()).isEqualTo("READY");
        assertThat(body.path("qty").toString()).isEqualTo("0.5");
        assertThat(event.payload()).doesNotContain("PLAYED", "refund", "leaseToken", "buyerPhone");
        verifyNoInteractions(documents);
        // READY still needs the real print owner for LABEL; only no-print events bypass it.
        assertThatThrownBy(() -> new DiningKdsEventConsumer(service, json).handle(envelope))
                .isInstanceOf(IllegalStateException.class).hasMessage("KDS print owner unavailable");
    }

    @Test
    void rejectEnvelopeWhoseAggregateDoesNotMatchOriginalBusinessFact() {
        var json = JsonMapper.builder().build();
        var consumer = new DiningKdsEventConsumer(mock(FoodKdsService.class), json);
        var source =
                new DiningOrderAccepted(
                        "BATCH",
                        "E",
                        "S",
                        "CHECK",
                        "ORDER",
                        "SUB",
                        LocalDate.of(2026, 10, 24),
                        "PICKUP",
                        Instant.parse("2026-10-24T00:00:00Z"));
        var envelope =
                new OutboxEnvelope(
                        "EV",
                        "PLATFORM",
                        "EV",
                        "FOOD_BATCH",
                        "OTHER",
                        "DINING_ORDER_ACCEPTED",
                        1,
                        "BATCH:BATCH",
                        1,
                        json.writeValueAsString(source),
                        source.acceptedAt());
        assertThatThrownBy(() -> consumer.handle(envelope))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
