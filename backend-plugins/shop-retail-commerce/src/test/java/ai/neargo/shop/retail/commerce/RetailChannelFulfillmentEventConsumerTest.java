package ai.neargo.shop.retail.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.RetryableOutboxException;
import ai.neargo.shop.retail.commerce.event.RetailChannelFulfillmentEventConsumer;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerPort;
import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourcePort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;
import ai.neargo.shop.spi.trade.RetailChannelFulfillmentSealed;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

class RetailChannelFulfillmentEventConsumerTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final IndustryChannelFulfillmentSourcePort.Query query =
            new IndustryChannelFulfillmentSourcePort.Query(
                    new Scope("MAIN", "E", "S"), "SOURCE", "ORDER", "FULFILLMENT", "1");
    final IndustryChannelFulfillmentSourcePort.Data data =
            new IndustryChannelFulfillmentSourcePort.Data(
                    query,
                    "HANDED_OVER",
                    List.of(
                            new IndustryChannelFulfillmentSourcePort.Line(
                                    "LINE", "MAIN", "ITEM", "0.75", "KG")),
                    now.minusSeconds(1),
                    "RECEIPT",
                    "a".repeat(64),
                    "b".repeat(64),
                    "c".repeat(64),
                    "d".repeat(64),
                    "IMPORT");
    final IndustryChannelFulfillmentSourcePort.Facts facts =
            new IndustryChannelFulfillmentSourcePort.Facts(
                    data,
                    PricingSnapshotHash.of(data),
                    now,
                    now.plusSeconds(30),
                    "ORIGINAL_OWNER_READ");

    RetailChannelFulfillmentSealed event(String importRef, String stage) {
        return new RetailChannelFulfillmentSealed(
                "MAIN",
                "E",
                "S",
                importRef,
                "SOURCE",
                "ORDER",
                "FULFILLMENT",
                "1",
                stage,
                "RECEIPT",
                facts.contentHash(),
                data.occurredAt());
    }

    OutboxEnvelope envelope(RetailChannelFulfillmentSealed event, String source, String stream) {
        return new OutboxEnvelope(
                "DELIVERY",
                source,
                "ORIGINAL_EVT",
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                1,
                stream,
                1,
                json.writeValueAsString(event),
                now);
    }

    @Test
    void delayedImportAndReplayKeepOriginalReferencesUntilDurablePublicationExists() {
        var source = mock(IndustryChannelFulfillmentSourcePort.class);
        when(source.read(query)).thenReturn(facts);
        var requests = new ArrayList<ChannelFulfillmentTriggerPort.Request>();
        ChannelFulfillmentTriggerPort trigger =
                request -> {
                    requests.add(request);
                    return requests.size() == 1
                            ? new ChannelFulfillmentTriggerPort.Result(
                                    request,
                                    ChannelFulfillmentTriggerPort.Status.WAITING_IMPORT,
                                    null,
                                    null)
                            : new ChannelFulfillmentTriggerPort.Result(
                                    request,
                                    ChannelFulfillmentTriggerPort.Status.RECORDED,
                                    "OP",
                                    "QUEUED");
                };
        var consumer = new RetailChannelFulfillmentEventConsumer(source, trigger, json, clock);
        var original = envelope(event("IMPORT", "HANDED_OVER"), "PLATFORM", "ORDER:ORDER");
        assertThat(consumer.handle(original).kind()).isEqualTo(DeliveryResult.Kind.RETRYABLE);
        assertThat(consumer.handle(original).kind()).isEqualTo(DeliveryResult.Kind.SENT);
        assertThat(consumer.handle(original).kind()).isEqualTo(DeliveryResult.Kind.SENT);
        assertThat(requests)
                .hasSize(3)
                .allSatisfy(
                        request -> {
                            assertThat(request).isEqualTo(requests.getFirst());
                            assertThat(request.originalEventNo()).isEqualTo("ORIGINAL_EVT");
                            assertThat(request.importNo()).isEqualTo("IMPORT");
                            assertThat(request.sourceFulfillmentRef()).isEqualTo("FULFILLMENT");
                        });
    }

    @Test
    void wrongSourceOrOrderStreamAndExpressNeverReadOriginalSource() {
        var source = mock(IndustryChannelFulfillmentSourcePort.class);
        var trigger = mock(ChannelFulfillmentTriggerPort.class);
        var consumer = new RetailChannelFulfillmentEventConsumer(source, trigger, json, clock);
        for (var envelope :
                List.of(
                        envelope(event("IMPORT", "HANDED_OVER"), "ORDER", "ORDER:ORDER"),
                        envelope(event("IMPORT", "HANDED_OVER"), "PLATFORM", "ORDER:FOREIGN"),
                        envelope(event("IMPORT", "SHIPPED"), "PLATFORM", "ORDER:ORDER")))
            assertThat(consumer.handle(envelope).kind()).isEqualTo(DeliveryResult.Kind.PERMANENT);
        verifyNoInteractions(source, trigger);
    }

    @Test
    void forgedImportOrReceiptCannotTriggerCimAndMissingProofRetries() {
        var source = mock(IndustryChannelFulfillmentSourcePort.class);
        var trigger = mock(ChannelFulfillmentTriggerPort.class);
        when(source.read(query)).thenReturn(facts);
        var consumer = new RetailChannelFulfillmentEventConsumer(source, trigger, json, clock);
        assertThat(
                        consumer.handle(
                                        envelope(
                                                event("FOREIGN", "HANDED_OVER"),
                                                "PLATFORM",
                                                "ORDER:ORDER"))
                                .kind())
                .isEqualTo(DeliveryResult.Kind.PERMANENT);
        verifyNoInteractions(trigger);
        doThrow(BizException.of(ErrorCode.NOT_FOUND)).when(source).read(query);
        assertThat(
                        consumer.handle(
                                        envelope(
                                                event("IMPORT", "HANDED_OVER"),
                                                "PLATFORM",
                                                "ORDER:ORDER"))
                                .kind())
                .isEqualTo(DeliveryResult.Kind.RETRYABLE);
        doThrow(new IllegalStateException("Original owner unavailable")).when(source).read(query);
        assertThatThrownBy(
                        () ->
                                consumer.handle(
                                        envelope(
                                                event("IMPORT", "HANDED_OVER"),
                                                "PLATFORM",
                                                "ORDER:ORDER")))
                .isInstanceOf(RetryableOutboxException.class);
        verifyNoInteractions(trigger);
    }

    @Test
    void sourceScopeConflictRemainsPermanent() {
        var source = mock(IndustryChannelFulfillmentSourcePort.class);
        var trigger = mock(ChannelFulfillmentTriggerPort.class);
        when(source.read(any())).thenThrow(BizException.of(ErrorCode.CONFLICT));
        var consumer = new RetailChannelFulfillmentEventConsumer(source, trigger, json, clock);
        assertThat(
                        consumer.handle(
                                        envelope(
                                                event("IMPORT", "HANDED_OVER"),
                                                "PLATFORM",
                                                "ORDER:ORDER"))
                                .kind())
                .isEqualTo(DeliveryResult.Kind.PERMANENT);
        verifyNoInteractions(trigger);
    }
}
