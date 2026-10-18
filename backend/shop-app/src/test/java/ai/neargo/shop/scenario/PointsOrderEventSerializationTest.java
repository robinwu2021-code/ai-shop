package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;

import ai.neargo.shop.spi.points.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

class PointsOrderEventSerializationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant at = Instant.parse("2026-10-18T00:00:00Z");

    private PointsOrderEvent event(SourceFactsPort.Fact fact) {
        return new PointsOrderEvent("O", "S", "M", "ST", "U", "T", "CNY", 1, at, fact);
    }

    @Test
    void persists_explicit_schema_and_original_event_identity() {
        var payload =
                new PointsFactPort.LifecycleFact(
                        "E",
                        "HASH",
                        "S",
                        "U",
                        "T",
                        1,
                        PointsFactPort.FactKind.PAID,
                        100,
                        0,
                        List.of(new PointsFactPort.LineSnapshot("I", 1, 100, 0)),
                        at);
        var original = event(new SourceFactsPort.Fact(7, payload, null, null, null, null));
        String body = json.writeValueAsString(original);
        assertThat(json.readTree(body).path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.readValue(body, PointsOrderEvent.class)).isEqualTo(original);
        assertThat(json.readValue(body, PointsOrderEvent.class).eventNo()).isEqualTo("E");
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        body.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                                        PointsOrderEvent.class))
                .hasStackTraceContaining("POINTS_ORDER_EVENT_SCHEMA_UNSUPPORTED");
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        body.replace("\"schemaVersion\":1,", ""),
                                        PointsOrderEvent.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void refund_preparation_reference_and_source_time_roundtrip() {
        var proof = new PointsRefundPort.RefundConfirmation("PREP", "INPUT", "CASH", null, null);
        var payload = new PointsFactPort.RefundConfirmationFact("R", "T", "S", "U", 1, at, proof);
        var original = event(new SourceFactsPort.Fact(8, null, null, null, null, payload));
        var copy = json.readValue(json.writeValueAsString(original), PointsOrderEvent.class);
        assertThat(copy).isEqualTo(original);
        assertThat(copy.fact().refund().confirmation()).isEqualTo(proof);
        assertThat(copy.occurredAt()).isEqualTo(at);
    }
}
