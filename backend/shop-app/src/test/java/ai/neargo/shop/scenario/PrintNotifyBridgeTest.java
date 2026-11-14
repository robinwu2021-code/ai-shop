package ai.neargo.shop.scenario;

import ai.neargo.shop.spi.printing.*;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.outbox.OutboxLease;
import ai.neargo.shop.event.outbox.OutboxLeaseLostException;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.notify.PushSender;
import ai.neargo.shop.print.api.FrozenJson;
import ai.neargo.shop.print.api.PrintBatchResolved;
import ai.neargo.shop.print.api.PrintHashes;
import ai.neargo.shop.print.api.PrintIncidentOpened;
import ai.neargo.shop.print.api.PrintIncidentResolved;
import ai.neargo.shop.spi.printing.PrintNotificationRecipientsPort;
import ai.neargo.shop.print.api.PrintScope;
import ai.neargo.shop.printbridge.PrintNotifyBridge;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PrintNotifyBridgeTest {
    final MessageService messages = mock(MessageService.class);
    final PushSender push = mock(PushSender.class);
    final PrintNotificationRecipientsPort recipients = mock(PrintNotificationRecipientsPort.class);
    final JsonMapper json = JsonMapper.builder().build();
    final PrintNotifyBridge bridge = new PrintNotifyBridge(messages, recipients, push, json);
    final PrintScope scope = new PrintScope("T", "E", "S");

    @Test
    void completionReplayUsesTheSameRecipientScopedInboxDedupKey() {
        when(recipients.recipients(scope, Set.of("MANAGER"))).thenReturn(List.of("STAFF1"));
        var row = row(new PrintBatchResolved(scope, "B", "RESOLVED", Instant.EPOCH,
                "INITIAL", policy(true, true, "IN_APP")));
        bridge.consume(row);
        bridge.consume(row);
        verify(messages, times(2)).pushTo("STAFF", "STAFF1", MessageService.SYSTEM,
                "打印已完成", "批次 B 已有明确完成证据，可在打印任务中查看。",
                "/pages/print-center/index?storeNo=S", "PRINT:" + PrintHashes.sha256("EVENT:STAFF1"));
        verifyNoInteractions(push);
    }

    @Test
    void disabledCompletionAndIncidentSwitchesDoNotResolveRecipients() {
        var disabled = policy(false, false, "IN_APP", "PUSH");
        bridge.consume(row(new PrintBatchResolved(scope, "B", "RESOLVED", Instant.EPOCH, "INITIAL", disabled)));
        bridge.consume(row(new PrintIncidentOpened(scope, "I", "UNKNOWN", "J", disabled)));
        bridge.consume(row(new PrintIncidentResolved(scope, "I", disabled)));
        verifyNoInteractions(messages, recipients, push);
        assertFalse(bridge.supports("PRINT_SIMULATION_PASSED"));
        assertFalse(bridge.supports("PRINT_EXPORT_READY"));
    }

    @Test
    void emptyChannelsSendNothingForAllThreeEventTypes() {
        var empty = policy(true, true);
        bridge.consume(row(new PrintBatchResolved(scope, "B", "RESOLVED", Instant.EPOCH, "INITIAL", empty)));
        bridge.consume(row(new PrintIncidentOpened(scope, "I", "UNKNOWN", "J", empty)));
        bridge.consume(row(new PrintIncidentResolved(scope, "I", empty)));
        verifyNoInteractions(messages, recipients, push);
    }

    @Test
    void pushOnlyDoesNotSilentlyCreateInboxMessages() {
        when(recipients.recipients(scope, Set.of("MANAGER"))).thenReturn(List.of("STAFF1"));
        bridge.consume(row(new PrintIncidentOpened(scope, "I", "UNKNOWN", "J", policy(true, true, "PUSH"))));
        verify(push).notify("STAFF", "STAFF1", "打印异常待处理",
                "任务或设备 J 需要核实，请查看打印中心。", "/pages/print-center/index?storeNo=S");
        verifyNoInteractions(messages);
    }

    @Test
    void expiredConsumerCannotCreateInboxOrPushSideEffects() {
        when(recipients.recipients(scope, Set.of("MANAGER"))).thenReturn(List.of("STAFF1"));
        assertThrows(OutboxLeaseLostException.class, () -> OutboxLease.withCheck(() -> false, () -> {
            bridge.consume(row(new PrintBatchResolved(scope, "B", "RESOLVED", Instant.EPOCH,
                    "INITIAL", policy(true, true, "IN_APP", "PUSH"))));
            return null;
        }));
        verifyNoInteractions(messages, push);
    }

    @Test
    void losingLeaseAfterInboxPreventsSubsequentPush() {
        when(recipients.recipients(scope, Set.of("MANAGER"))).thenReturn(List.of("STAFF1"));
        boolean[] owned = {true};
        doAnswer(invocation -> { owned[0] = false; return null; }).when(messages)
                .pushTo(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        assertThrows(OutboxLeaseLostException.class, () -> OutboxLease.withCheck(() -> owned[0], () -> {
            bridge.consume(row(new PrintIncidentResolved(scope, "I", policy(true, true, "IN_APP", "PUSH"))));
            return null;
        }));
        verify(push, never()).notify(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void reprintExtraAndReplacementHaveDistinctCompletionTitles() {
        when(recipients.recipients(eq(scope), any())).thenReturn(List.of("STAFF1"));
        for (String kind : List.of("REPRINT", "EXTRA")) {
            bridge.consume(row(new PrintBatchResolved(scope, "B", "RESOLVED", Instant.EPOCH,
                    kind, policy(true, true, "IN_APP"))));
        }
        bridge.consume(row(new PrintBatchResolved(scope, "B", "REPLACEMENT_RESOLVED", Instant.EPOCH,
                "INITIAL", policy(true, true, "IN_APP"))));
        for (String title : List.of("补打已完成", "加印已完成", "打印问题已由补票解决")) {
            verify(messages).pushTo(eq("STAFF"), eq("STAFF1"), eq(MessageService.SYSTEM),
                    eq(title), anyString(), anyString(), anyString());
        }
    }

    private FrozenJson policy(boolean complete, boolean incident, String... channels) {
        String value = json.writeValueAsString(Map.of("onBatchResolved", complete, "onIncident", incident,
                "channels", List.of(channels), "recipientRoleKeys", List.of("MANAGER")));
        return new FrozenJson(value, PrintHashes.sha256(value));
    }

    private SysOutbox row(DomainEvent event) {
        var row = new SysOutbox();
        row.setEventNo("EVENT");
        row.setEventType(event.eventType());
        row.setPayload(json.writeValueAsString(event));
        return row;
    }
}
