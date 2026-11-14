package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.Stage;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourceInternalApi;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort.Data;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort.Facts;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort.Line;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort.Query;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class RemoteFoodChannelFulfillmentSourcePortTest {
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final Query query =
            new Query("E", "S", "BATCH", "CORE", "HANDOVER", "CUSTODY_V1:" + "a".repeat(64));

    Facts facts(Query q, Stage stage, Instant until) {
        var data =
                new Data(
                        q,
                        stage,
                        null,
                        null,
                        null,
                        List.of(new Line("SOURCE", "COMPONENT", "OPAQUE", "1.25", "KG")),
                        now.minusSeconds(1),
                        "FOOD:CUSTODY:" + q.sourceFulfillmentRef() + ":" + q.sourceRevision());
        return new Facts(data, now, until, "FOOD:FULFILLMENT:" + PricingSnapshotHash.of(data));
    }

    RemoteFoodChannelFulfillmentSourcePort remote(ChannelFulfillmentSourceInternalApi api) {
        return new RemoteFoodChannelFulfillmentSourcePort(
                () -> api, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void returnsTheExactOriginalCommittedReceiptWithoutApproximatingCoreDelivery() {
        var original = facts(query, Stage.HANDED_OVER, now.plusSeconds(30));
        assertThat(remote(q -> original).capture(query)).isEqualTo(original);
        assertThat(original.data().stage()).isEqualTo(Stage.HANDED_OVER);
    }

    @Test
    void wrongScopeExpiredProofOrUncommittedReadyStateCannotClaimHandover() {
        var other = new Query("E", "OTHER", "BATCH", "CORE", "HANDOVER", query.sourceRevision());
        assertThatThrownBy(
                        () ->
                                remote(q -> facts(other, Stage.HANDED_OVER, now.plusSeconds(30)))
                                        .capture(query))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                remote(q -> facts(query, Stage.READY, now.plusSeconds(30)))
                                        .capture(query))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> remote(q -> facts(query, Stage.HANDED_OVER, now)).capture(query))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void aMissingOrAlteredOriginalReceiptDoesNotFallBackToSuccess() {
        assertThatThrownBy(() -> remote(q -> null).capture(query))
                .isInstanceOf(RuntimeException.class);
        var original = facts(query, Stage.HANDED_OVER, now.plusSeconds(30));
        var altered =
                new Facts(original.data(), original.observedAt(), original.validUntil(), "FORGED");
        assertThatThrownBy(() -> remote(q -> altered).capture(query))
                .isInstanceOf(RuntimeException.class);
    }
}
