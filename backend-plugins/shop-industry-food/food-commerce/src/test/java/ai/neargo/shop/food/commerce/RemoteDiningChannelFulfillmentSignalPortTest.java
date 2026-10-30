package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.commerce.port.RemoteDiningChannelFulfillmentSignalPort;
import ai.neargo.shop.food.service.DiningChannelFulfillmentSignalPort;
import ai.neargo.shop.spi.food.DiningServiceEvent;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class RemoteDiningChannelFulfillmentSignalPortTest {
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final DiningServiceEvent event =
            new DiningServiceEvent(
                    "EVENT",
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
                    "Handover",
                    "A",
                    now.minusSeconds(1));

    DiningChannelFulfillmentSignalPort.Signal original(Instant validUntil) {
        String revision = "CUSTODY_V1:" + "a".repeat(64);
        String receipt =
                "FOOD:FULFILLMENT_SIGNAL:"
                        + PricingSnapshotHash.of(
                                List.of(
                                        DiningChannelFulfillmentSignalPort.Status.READY,
                                        event,
                                        "IMPORT",
                                        "BATCH",
                                        "CORE",
                                        "HANDOVER",
                                        revision));
        return new DiningChannelFulfillmentSignalPort.Signal(
                DiningChannelFulfillmentSignalPort.Status.READY,
                event,
                "IMPORT",
                "BATCH",
                "CORE",
                "HANDOVER",
                revision,
                now,
                validUntil,
                receipt);
    }

    @Test
    void preservesExactOriginalSignalAndRejectsExpiredOrChangedReceiptWithoutFallback() {
        var original = original(now.plusSeconds(30));
        var remote =
                new RemoteDiningChannelFulfillmentSignalPort(
                        () -> request -> original, Clock.fixed(now, ZoneOffset.UTC));
        assertThat(remote.resolve(event)).isEqualTo(original);
        var expired =
                new RemoteDiningChannelFulfillmentSignalPort(
                        () -> request -> original(now), Clock.fixed(now, ZoneOffset.UTC));
        assertThatThrownBy(() -> expired.resolve(event)).isInstanceOf(RuntimeException.class);
        var forged =
                new DiningChannelFulfillmentSignalPort.Signal(
                        original.status(),
                        original.originalEvent(),
                        "OTHER_IMPORT",
                        original.batchNo(),
                        original.coreOrderNo(),
                        original.custodyOperationNo(),
                        original.sourceRevision(),
                        original.observedAt(),
                        original.validUntil(),
                        original.originalReceiptRef());
        assertThatThrownBy(
                        () ->
                                new RemoteDiningChannelFulfillmentSignalPort(
                                                () -> request -> forged,
                                                Clock.fixed(now, ZoneOffset.UTC))
                                        .resolve(event))
                .isInstanceOf(RuntimeException.class);
    }
}
