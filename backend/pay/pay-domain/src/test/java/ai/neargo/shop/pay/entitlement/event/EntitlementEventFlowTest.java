package ai.neargo.shop.pay.entitlement.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.pay.entitlement.entity.PayEntInbox;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.pay.entitlement.entity.PayEntOutbox;
import ai.neargo.shop.pay.mapper.entitlement.PayEntInboxMapper;
import ai.neargo.shop.pay.mapper.entitlement.PayEntOutboxMapper;
import ai.neargo.shop.spi.entitlement.EntitlementEvents;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventCodec;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventBridgePort;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class EntitlementEventFlowTest {

    @Test
    void payEventRemainsDurableAndOnlyEnqueuesAfterCommit() {
        PayEntOutboxMapper mapper = mock(PayEntOutboxMapper.class);
        PayEntitlementOutboxRelay relay = mock(PayEntitlementOutboxRelay.class);
        ObjectProvider<PayEntitlementOutboxRelay> relayProvider = provider(relay);
        Map<String, PayEntOutbox> stored = new ConcurrentHashMap<>();
        when(mapper.insertIfAbsent(any())).thenAnswer(invocation -> {
            PayEntOutbox row = invocation.getArgument(0);
            row.setId(1L);
            stored.putIfAbsent(row.getEventNo(), row);
            return 1;
        });
        when(mapper.findByEventNo(eq("MAIN"), any())).thenAnswer(invocation -> stored.get(invocation.getArgument(1)));

        TransactionSynchronizationManager.initSynchronization();
        try {
            var publisher = new PayEntitlementEventPublisher(mapper, new ObjectMapper(), relayProvider);
            publisher.publish(new EntitlementEvents.EntitlementGranted("EVT-1", "G-1", "U-1", "E-1",
                    "ETV-1", "PAID_ORDER", "SO-1", 1_797_000_000_000L));

            assertThat(stored).containsKey("EVT-1");
            verify(relay, org.mockito.Mockito.never()).submit("EVT-1");
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(relay).submit("EVT-1");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void sameEventNumberWithChangedFactsIsRejected() {
        PayEntOutboxMapper mapper = mock(PayEntOutboxMapper.class);
        var original = new PayEntOutbox();
        original.setId(3L);
        original.setEventNo("EVT-1");
        original.setEventType("ENTITLEMENT_GRANTED");
        original.setSchemaVersion(1);
        original.setAggregateNo("G-2");
        original.setPayload("original");
        original.setPayloadHash("original");
        original.setOccurredAt(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(1_797_000_000_000L), java.time.ZoneOffset.UTC));
        when(mapper.insertIfAbsent(any())).thenReturn(0);
        when(mapper.findByEventNo("MAIN", "EVT-1")).thenReturn(original);
        ObjectProvider<PayEntitlementOutboxRelay> relayProvider = provider(null);
        var publisher = new PayEntitlementEventPublisher(mapper, new ObjectMapper(), relayProvider);

        assertThatThrownBy(() -> publisher.publish(new EntitlementEvents.EntitlementGranted("EVT-1", "G-2", "U-1",
                "E-1", "ETV-1", "PAID_ORDER", "SO-1", 1_797_000_000_000L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("immutable facts");
    }

    @Test
    void payloadContainsOnlyTheTypedEntitlementEventFields() {
        var event = new EntitlementEvents.EntitlementGranted("EVT-2", "G-2", "U-2", "E-2",
                "ETV-2", "ACTIVITY", "ACT-2", 1_797_000_000_000L);
        String payload = new ObjectMapper().writeValueAsString(event);

        assertThat(payload).contains("grantNo", "userNo", "entityNo", "templateVersionNo", "sourceType", "sourceNo");
        assertThat(payload).doesNotContain("code", "token", "credential", "principal", "bonus");
    }

    @Test
    void inboxReplayCallsTheBusinessActionOnlyOnce() {
        PayEntInboxMapper mapper = mock(PayEntInboxMapper.class);
        PayEntitlementInboxProcessor processor = new PayEntitlementInboxProcessor(mapper, new ObjectMapper());
        Map<String, PayEntInbox> stored = new ConcurrentHashMap<>();
        when(mapper.insertIfAbsent(any())).thenAnswer(invocation -> {
            PayEntInbox row = invocation.getArgument(0);
            return stored.putIfAbsent(row.getConsumer() + ":" + row.getEventNo(), row) == null ? 1 : 0;
        });
        when(mapper.lock("MAIN", "CORE_ACTIVITY", "EVT-3")).thenAnswer(invocation ->
                stored.get("CORE_ACTIVITY:EVT-3"));
        var calls = new java.util.concurrent.atomic.AtomicInteger();

        assertThat(processor.process("MAIN", "CORE_ACTIVITY", "EVT-3", "activity:3",
                () -> calls.incrementAndGet())).isTrue();
        assertThat(processor.process("MAIN", "CORE_ACTIVITY", "EVT-3", "activity:3",
                () -> calls.incrementAndGet())).isFalse();
        assertThat(calls).hasValue(1);
        assertThatThrownBy(() -> processor.process("MAIN", "CORE_ACTIVITY", "EVT-3", "activity:changed",
                () -> calls.incrementAndGet()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("payload conflict");
    }

    @Test
    void reversalFactsCanCrossTheAllowlistedBridgeWithoutBecomingASecondGrant() {
        var json = new ObjectMapper();
        var event = new EntitlementEvents.EntitlementUseReversed("EVT-R", "RV-1", "EU-1", "U-1", "E-1", "S-1",
                "I-1", 1, 100, "CNY", 1_797_000_000_000L);
        var row = new PayEntOutbox(); row.setTenantNo("MAIN"); row.setVersion(0L); row.setId(1L); row.setStatus("PENDING"); row.setAttempts(0);
        row.setEventNo(event.eventNo()); row.setEventType(event.eventType()); row.setSchemaVersion(1);
        row.setAggregateNo(event.aggregateId()); row.setPayload(json.writeValueAsString(event));
        row.setPayloadHash(PayEntitlementEventPublisher.hash(event, row.getPayload()));
        PayEntOutboxMapper mapper = mock(PayEntOutboxMapper.class);
        when(mapper.findByEventNo("MAIN", event.eventNo())).thenReturn(row);
        var bus = mock(OutboxEventBus.class);
        var relay = new PayEntitlementOutboxRelay(mapper, bus, json);
        try {
            relay.relay(event.eventNo());
            verify(bus).publish(event.eventNo(), event);
            verify(mapper).markForwarded(eq(1L), eq("MAIN"), any());
        } finally { relay.shutdown(); }
    }

    @Test
    void aLostCoreAcknowledgementRetriesTheSameEventWithoutMarkingItForwarded() {
        var json = new ObjectMapper();
        var event = new EntitlementEvents.EntitlementGranted("EVT-LOST", "G1", "U1", "M1", "V1", "TARGETED",
                "B1", 1_797_000_000_000L);
        var row = new PayEntOutbox(); row.setTenantNo("MAIN"); row.setVersion(0L); row.setId(9L); row.setStatus("PENDING"); row.setAttempts(0);
        row.setEventNo(event.eventNo()); row.setEventType(event.eventType()); row.setSchemaVersion(1);
        row.setAggregateNo(event.aggregateId()); row.setPayload(json.writeValueAsString(event));
        row.setPayloadHash(PayEntitlementEventPublisher.hash(event, row.getPayload()));
        var mapper = mock(PayEntOutboxMapper.class);
        when(mapper.findByEventNo("MAIN", event.eventNo())).thenReturn(row);
        var bridge = mock(PayEntitlementEventBridgePort.class);
        doThrow(new IllegalStateException("ack lost")).doNothing().when(bridge).publish(event.eventNo(), event);
        var relay = new PayEntitlementOutboxRelay(mapper, bridge, json);
        try {
            relay.relay(event.eventNo());
            verify(mapper, never()).markForwarded(eq(9L), eq("MAIN"), any());
            relay.relay(event.eventNo());
            verify(bridge, times(2)).publish(event.eventNo(), event);
            verify(mapper).markForwarded(eq(9L), eq("MAIN"), any());
        } finally { relay.shutdown(); }
    }

    private static ObjectProvider<PayEntitlementOutboxRelay> provider(PayEntitlementOutboxRelay relay) {
        @SuppressWarnings("unchecked")
        ObjectProvider<PayEntitlementOutboxRelay> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(relay);
        return provider;
    }
    @Test void exceptionEventUsesSharedWhitelistAndRejectsUnknownFailureKind() {
        var json=new ObjectMapper();
        var event=new EntitlementEvents.ExceptionOpened("EE1","EX1","M1","GRANT_DELIVERY","G1",1_797_000_000_000L);
        var encoded=PayEntitlementEventCodec.encode(event,json);
        assertThat(PayEntitlementEventCodec.decode(encoded,json)).isEqualTo(event);
        assertThat(encoded.payload()).doesNotContain("paidFactRef","commandSnapshot");
        assertThatThrownBy(() -> PayEntitlementEventCodec.encode(new EntitlementEvents.ExceptionOpened(
                "EE1","EX1","M1","UNKNOWN","G1",event.occurredAt()),json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void exceptionEventIsForwardedFromTheDurablePayOutbox() {
        var json=new ObjectMapper();
        var event=new EntitlementEvents.ExceptionOpened("EE1","EX1","M1","GRANT_DELIVERY","G1",1_797_000_000_000L);
        var row=new PayEntOutbox(); row.setTenantNo("MAIN"); row.setVersion(0L); row.setId(31L); row.setStatus("PENDING"); row.setAttempts(0);
        row.setEventNo(event.eventNo()); row.setEventType(event.eventType()); row.setSchemaVersion(1);
        row.setAggregateNo(event.aggregateId()); row.setPayload(json.writeValueAsString(event));
        row.setPayloadHash(PayEntitlementEventPublisher.hash(event,row.getPayload()));
        var mapper=mock(PayEntOutboxMapper.class); var bus=mock(OutboxEventBus.class);
        when(mapper.findByEventNo("MAIN",event.eventNo())).thenReturn(row);
        var relay=new PayEntitlementOutboxRelay(mapper,bus,json);
        try {
            relay.relay(event.eventNo());
            verify(bus).publish(event.eventNo(),event);
            verify(mapper).markForwarded(eq(31L),eq("MAIN"),any());
        } finally { relay.shutdown(); }
    }
}
