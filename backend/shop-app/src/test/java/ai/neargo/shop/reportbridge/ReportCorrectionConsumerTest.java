package ai.neargo.shop.reportbridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.report.dto.ReportRows.CursorRow;
import ai.neargo.shop.report.service.ReportStorageService;
import ai.neargo.shop.spi.report.ReportAffectedDaysPort;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportKitchenEventDayPort;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

class ReportCorrectionConsumerTest {
    @Test
    void kitchenCorrectionUsesExactOriginalSourceDayAndKeepsEnvelopeReplayIdentity() {
        var storage = mock(ReportStorageService.class);
        var kitchen = mock(ReportKitchenEventDayPort.class);
        var factory = new StaticListableBeanFactory();
        factory.addBean("kitchen", kitchen);
        var scope = new Scope("MAIN", "E1", "S1");
        var original = LocalDate.of(2020, 1, 1);
        var at = Instant.parse("2020-01-01T12:00:00Z");
        var evidence =
                new DayEvidence(
                        "DAY",
                        "POLICY",
                        0,
                        "UTC",
                        0,
                        Instant.parse("2020-01-01T00:00:00Z"),
                        Instant.parse("2020-01-02T00:00:00Z"));
        var change =
                new ReportKitchenEventDayPort.Change(
                        scope, "FSEV1", "TASK", "READY", new BigDecimal("1.25"));
        when(kitchen.affectedDay(change))
                .thenReturn(
                        new ReportKitchenEventDayPort.AffectedDay(change, original, evidence, at));
        var cursor = mock(CursorRow.class);
        when(cursor.enabled()).thenReturn(true);
        when(cursor.firstDate()).thenReturn(original);
        when(cursor.partition()).thenReturn(new Partition(scope, original, 1));
        when(storage.scopes(scope)).thenReturn(List.of(cursor));
        var consumer =
                new ReportCorrectionConsumer(
                        storage,
                        factory.getBeanProvider(ReportAffectedDaysPort.class),
                        JsonMapper.builder().build(),
                        factory.getBeanProvider(ReportKitchenEventDayPort.class));
        var event = kitchenEvent();
        consumer.consume(event);
        consumer.consume(event);
        verify(kitchen, times(2)).affectedDay(change);
        verify(storage, times(2))
                .markDirty(new Partition(scope, original, 1), "EVT99", "SOURCE_EVENT", at);
        verify(storage, times(2)).scopes(scope);
        verifyNoMoreInteractions(storage);
    }

    @Test
    void kitchenMissingOwnerRetriesAndWrongEnvelopeCannotReadSource() {
        var storage = mock(ReportStorageService.class);
        var factory = new StaticListableBeanFactory();
        var consumer =
                new ReportCorrectionConsumer(
                        storage,
                        factory.getBeanProvider(ReportAffectedDaysPort.class),
                        JsonMapper.builder().build(),
                        factory.getBeanProvider(ReportKitchenEventDayPort.class));
        var event = kitchenEvent();
        assertThatThrownBy(() -> consumer.consume(event))
                .hasMessage("REPORT_KITCHEN_OWNER_NOT_AVAILABLE");
        event.setAggregateId("OTHER");
        assertThatThrownBy(() -> consumer.consume(event))
                .hasMessage("REPORT_KITCHEN_EVENT_SCOPE_MISMATCH");
        verifyNoInteractions(storage);
    }

    private SysOutbox kitchenEvent() {
        var event = new SysOutbox();
        event.setEventNo("EVT99");
        event.setEventType("DINING_SERVICE_EVENT");
        event.setSourceSystem("PLATFORM");
        event.setSchemaVersion(1);
        event.setAggregateType("FOOD_KITCHEN_TASK");
        event.setAggregateId("TASK");
        event.setCreatedAt(LocalDateTime.of(2026, 10, 29, 12, 0));
        event.setPayload(
                "{\"eventNo\":\"FSEV1\",\"entityNo\":\"E1\",\"storeNo\":\"S1\",\"taskNo\":\"TASK\",\"action\":\"READY\",\"qty\":1.25}");
        return event;
    }

    @Test
    void replayMarksOriginalAndOccurrenceDatesWithoutRecountingOrUsingConsumptionDate() {
        var storage = mock(ReportStorageService.class);
        var owner = mock(ReportAffectedDaysPort.class);
        var factory = new StaticListableBeanFactory();
        factory.addBean("owner", owner);
        var scope = new Scope("MAIN", "E1", "S1");
        var original = LocalDate.of(2020, 1, 1);
        var occurred = original.plusDays(20);
        when(owner.affectedDays(any()))
                .thenReturn(
                        List.of(
                                new ReportAffectedDaysPort.AffectedDay(scope, original),
                                new ReportAffectedDaysPort.AffectedDay(scope, occurred)));
        var cursor = mock(CursorRow.class);
        when(cursor.enabled()).thenReturn(true);
        when(cursor.firstDate()).thenReturn(original);
        when(cursor.partition()).thenReturn(new Partition(scope, original, 1));
        when(storage.scopes(scope)).thenReturn(List.of(cursor));
        var consumer =
                new ReportCorrectionConsumer(
                        storage,
                        factory.getBeanProvider(ReportAffectedDaysPort.class),
                        JsonMapper.builder().build());
        var event = new SysOutbox();
        event.setEventNo("EV1");
        event.setEventType("AFTER_SALE_REFUNDED");
        event.setPayload("{\"subOrderNo\":\"SO1\"}");
        event.setCreatedAt(occurred.atTime(1, 0));
        consumer.consume(event);
        consumer.consume(event);
        verify(storage, times(2))
                .markDirty(
                        eq(new Partition(scope, original, 1)),
                        eq("EV1"),
                        eq("SOURCE_EVENT"),
                        any());
        verify(storage, times(2))
                .markDirty(
                        eq(new Partition(scope, occurred, 1)),
                        eq("EV1"),
                        eq("SOURCE_EVENT"),
                        any());
        verify(storage, times(4)).scopes(scope);
        verifyNoMoreInteractions(storage);
    }

    @Test
    void missingOwnerFailsDeliveryForOriginalOutboxRetry() {
        var factory = new StaticListableBeanFactory();
        var storage = mock(ReportStorageService.class);
        var consumer =
                new ReportCorrectionConsumer(
                        storage,
                        factory.getBeanProvider(ReportAffectedDaysPort.class),
                        JsonMapper.builder().build());
        var event = new SysOutbox();
        event.setEventType("ORDER_PAID");
        event.setPayload("{\"orderNo\":\"O1\"}");
        event.setCreatedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
        assertThatThrownBy(() -> consumer.consume(event))
                .hasMessage("REPORT_CHANGE_OWNER_NOT_AVAILABLE");
        verifyNoInteractions(storage);
    }

    @Test
    void actualUnifiedAdapterPreservesOriginalTimeAndRetriesReadOnlyCorrectionFailure() {
        var storage = mock(ReportStorageService.class);
        var owner = mock(ReportAffectedDaysPort.class);
        var factory = new StaticListableBeanFactory();
        factory.addBean("owner", owner);
        when(owner.affectedDays(any())).thenReturn(List.of());
        var consumer =
                new ReportCorrectionConsumer(
                        storage,
                        factory.getBeanProvider(ReportAffectedDaysPort.class),
                        JsonMapper.builder().build());
        var handler =
                new ai.neargo.shop.event.outbox.runtime.TransactionalOutboxHandler(
                        consumer,
                        mock(javax.sql.DataSource.class),
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                mock(javax.sql.DataSource.class)),
                        "sys_event_consumed");
        var at = java.time.Instant.parse("2020-01-01T18:00:00Z");
        var event =
                new ai.neargo.shop.event.outbox.OutboxEnvelope(
                        "E1",
                        "PLATFORM",
                        "E1",
                        "ORDER",
                        "O1",
                        "ORDER_PAID",
                        1,
                        "ORDER:O1",
                        1,
                        "{\"orderNo\":\"O1\"}",
                        at);
        assertThat(handler.handle(event).kind())
                .isEqualTo(ai.neargo.shop.event.outbox.DeliveryResult.Kind.SENT);
        verify(owner).affectedDays(new ReportAffectedDaysPort.Change("MAIN", "O1", null, at));
        when(owner.affectedDays(any()))
                .thenThrow(new IllegalStateException("REPORT_SOURCE_UNAVAILABLE"));
        assertThat(handler.handle(event).kind())
                .isEqualTo(ai.neargo.shop.event.outbox.DeliveryResult.Kind.RETRYABLE);
    }
}
