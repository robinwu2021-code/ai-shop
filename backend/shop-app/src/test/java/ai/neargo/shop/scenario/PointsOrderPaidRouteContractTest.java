package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;

import ai.neargo.shop.spi.points.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

class PointsOrderPaidRouteContractTest {
    final ObjectMapper json = new ObjectMapper();

    PointsFactPort.LifecycleFact raw(PointsRefundPort.PaymentSnapshot payment) {
        return new PointsFactPort.LifecycleFact(
                "PAID:S",
                null,
                "S",
                "U",
                "TYPE",
                1,
                PointsFactPort.FactKind.PAID,
                100,
                0,
                List.of(new PointsFactPort.LineSnapshot("I", 1, 100, 0)),
                Instant.parse("2026-10-18T00:00:00Z"),
                payment);
    }

    @Test
    void actual_provider_route_survives_json_and_is_part_of_the_digest() {
        var payment =
                new PointsRefundPort.PaymentSnapshot(
                        "DIRECT",
                        "TEST",
                        "M",
                        "ST",
                        "V1",
                        "SM",
                        "PROVIDER_TX",
                        "MERCHANT_PAY_NO",
                        90);
        var signed = PointsLifecycleFacts.sign(raw(payment), json::writeValueAsString);
        assertThat(
                        json.readValue(
                                json.writeValueAsString(signed),
                                PointsFactPort.LifecycleFact.class))
                .isEqualTo(signed);
        assertThat(signed.payloadHash())
                .isNotEqualTo(PointsLifecycleFacts.digest(raw(null), json::writeValueAsString));
        assertThat(signed.payment().tradeNo()).isEqualTo("PROVIDER_TX");
    }

    @Test
    void an_unverified_route_is_not_invented_by_signing() {
        assertThat(PointsLifecycleFacts.sign(raw(null), json::writeValueAsString).payment())
                .isNull();
    }

    @Test
    void missing_payment_preserves_the_previously_published_digest() throws Exception {
        var f = raw(null);
        var legacy =
                new Legacy(
                        f.eventNo(),
                        null,
                        f.subOrderNo(),
                        f.userNo(),
                        f.typeCode(),
                        f.sourceVersion(),
                        f.kind(),
                        f.finalGoodsMinor(),
                        f.refundedGoodsMinor(),
                        f.lines(),
                        f.occurredAt());
        String expected =
                HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                json.writeValueAsString(legacy)
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)));
        assertThat(PointsLifecycleFacts.digest(f, json::writeValueAsString)).isEqualTo(expected);
    }

    record Legacy(
            String eventNo,
            String payloadHash,
            String subOrderNo,
            String userNo,
            String typeCode,
            long sourceVersion,
            PointsFactPort.FactKind kind,
            long finalGoodsMinor,
            long refundedGoodsMinor,
            List<PointsFactPort.LineSnapshot> lines,
            Instant occurredAt) {}
}
