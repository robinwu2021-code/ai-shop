package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.notify.StoredValueNotificationService;
import ai.neargo.shop.payclient.StoredValueConsumerQueryAppService;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.EntitlementUsed;
import ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.NotificationFact;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoredValueNotificationSubscriberTest {
    private final ObjectMapper json = new ObjectMapper();
    private final MessageService messages = mock(MessageService.class);
    private final StoredValueConsumerQueryAppService facts = mock(StoredValueConsumerQueryAppService.class);
    private final StoredValueNotificationService tasks = mock(StoredValueNotificationService.class);
    private final EntitlementNotificationSubscriber consumer = new EntitlementNotificationSubscriber(messages, json);

    private SysOutbox row(String kind) {
        var event = new EntitlementUsed("EV1", "US1", "U1", "M1", "S1", kind, "A1", "STORED_VALUE".equals(kind) ? 999L : null,
                "STORED_VALUE".equals(kind) ? null : 1, 1000);
        var row = new SysOutbox();
        row.setEventNo(event.eventNo());
        row.setEventType(event.eventType());
        row.setAggregateId(event.aggregateId());
        row.setAggregateType(event.aggregateType());
        row.setPayload(json.writeValueAsString(event));
        consumer.storedValueNotifications(facts, tasks);
        return row;
    }

    @Test
    void storedValueRoutesOnlyOnceUsingAuthoritativeCurrencyAndTime() {
        when(facts.notificationFact(any(), eq("US1"))).thenReturn(new NotificationFact(
                "US1", "U1", "S1", "门店", "洗车", "CNY", 125, Instant.ofEpochMilli(2000), "DC1"));
        consumer.consume(row("STORED_VALUE"));
        verify(tasks).enqueue("MAIN", "EV1", "US1", "U1", "S1", "DC1",
                Map.of("store", "门店", "title", "洗车", "amount", "1.25 CNY", "time", "2000"));
        verify(messages, never()).push(any(), any(), any(), any(), any(), any());
        verify(messages, never()).pushTo(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void otherAssetKindsKeepTheirExistingInboxRoute() {
        consumer.consume(row("PAID_CARD"));
        verify(facts, never()).notificationFact(any(), any());
        verify(tasks, never()).enqueue(any(), any(), any(), any(), any(), any(), any());
        // Same C-side trade inbox route, now through the explicit transactional writer.
        // Fixed EV1:USER:U1 digest pins both event identity and recipient into replay deduplication.
        verify(messages).pushTo("USER", "U1", "TRADE", "核销成功",
                "本次消费已核销，可在消费记录中查看。", null,
                "EN4c8d49d8c17a5280979e5ce023cbcfc0d61f0b8e11b710e6ba5e1ee8aa0c793b");
        verify(messages, never()).push(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mismatchedPayOwnerCannotCreateTask() {
        when(facts.notificationFact(any(), eq("US1"))).thenReturn(new NotificationFact(
                "US1", "OTHER", "S1", "门店", "洗车", "CNY", 125, Instant.EPOCH, "DC1"));
        assertThatThrownBy(() -> consumer.consume(row("STORED_VALUE")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(tasks, never()).enqueue(any(), any(), any(), any(), any(), any(), any());
    }
}
