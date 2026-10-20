package ai.neargo.shop.paybridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.ExpiryReminder;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.ReminderPage;
import ai.neargo.shop.payclient.EntitlementLifecycleAppService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class EntitlementExpiryReminderServiceTest {
    @Test void failedMessageDoesNotStopOtherRecipientOrInvokeAnyFinancialMutation() {
        var pay = mock(EntitlementLifecycleAppService.class);
        var messages = mock(MessageService.class);
        var until = Instant.parse("2026-10-20T00:00:00Z");
        when(pay.expiringSoon(any(), anyLong(), anyInt())).thenReturn(new ReminderPage(List.of(
                new ExpiryReminder("I1", "U1", until), new ExpiryReminder("I2", "U2", until)), 0));
        var failed = new AtomicBoolean();
        doAnswer(call -> {
            if ("U1".equals(call.getArgument(0)) && !failed.getAndSet(true)) {
                throw new IllegalStateException("Transient inbox failure");
            }
            return null;
        }).when(messages).push(anyString(), anyString(), anyString(), anyString(), isNull(), anyString());
        var service = new EntitlementExpiryReminderService(pay, messages);
        assertThat(service.run(0, 20)).isEqualTo(new EntitlementExpiryReminderService.Result(2, 1, 0));
        assertThat(service.run(0, 20)).isEqualTo(new EntitlementExpiryReminderService.Result(2, 0, 0));
        verify(pay, times(2)).expiringSoon(any(), anyLong(), anyInt());
        verifyNoMoreInteractions(pay);
        verify(messages, times(4)).push(anyString(), anyString(), anyString(), anyString(), isNull(), anyString());
    }
}
