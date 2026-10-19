package ai.neargo.shop.message.notify;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxLease;
import ai.neargo.shop.message.NotificationConsumer;
import ai.neargo.shop.message.entity.MsgPushToken;
import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.mapper.MessageMappers;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxNotificationOutcomeTest {
    @Test
    void bestEffortFailureIsRecordedWithoutRetryingSuccessfulChannels() {
        var mapper = mock(MessageMappers.NotifyLogMapper.class);
        when(mapper.insert(any(SysNotifyLog.class))).thenReturn(1);
        var writer = new NotifyLogWriter(mapper);
        var consumer = new NotificationConsumer(null,null,null,null,null,null,null,null,null,null,null,null,null,writer,null) {
            @Override public void consume(SysOutbox event) {
                writer.write("PUSH","TRADE_NOTIFY","device",null,null,"FAILED","rejected",null,null);
            }
        };
        var result = consumer.deliver(new SysOutbox());
        assertThat(result.kind().name()).isEqualTo("WARNING");
        assertThat(result.detail()).contains("PUSH", "failed=1", "refs=");
        verify(mapper, times(1)).insert(any(SysNotifyLog.class));
    }

    @Test
    void lostLeaseStopsSecondDeviceInsteadOfBeingSwallowed() {
        var tokens = mock(MessageMappers.PushTokenMapper.class);
        var router = mock(PushRouter.class);
        MsgPushToken a=new MsgPushToken(); a.setClientId("a");
        MsgPushToken b=new MsgPushToken(); b.setClientId("b");
        when(tokens.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(a,b));
        var valid = new AtomicBoolean(true);
        var actions = new AtomicInteger();
        when(router.push(any(),any(),any(),any(),any(),any())).thenAnswer(call -> {
            actions.incrementAndGet(); valid.set(false); return ai.neargo.shop.spi.notify.SendResult.none();
        });
        var sender = new PushSender(router,tokens);
        assertThatThrownBy(() -> OutboxLease.withCheck(valid::get, () -> sender.notify("USER","U1","title","body","/")))
                .isInstanceOf(IllegalStateException.class).hasMessage("OUTBOX_LEASE_LOST");
        assertThat(actions.get()).isEqualTo(1);
    }
}
