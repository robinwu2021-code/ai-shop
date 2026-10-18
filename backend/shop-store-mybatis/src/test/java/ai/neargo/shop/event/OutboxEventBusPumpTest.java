package ai.neargo.shop.event;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「publish 之后会不会真的交给投递泵」——
 * 场景测试里泵是关着的（见 {@code application-test.yml} 的理由），
 * 所以这条链路只有这里在守。
 */
@DisplayName("事件发布 → 投递泵")
class OutboxEventBusPumpTest {

    private record Evt(String id) implements DomainEvent {
        @Override
        public String aggregateType() {
            return "SUB_ORDER";
        }

        @Override
        public String aggregateId() {
            return id;
        }

        @Override
        public String eventType() {
            return "SUB_ORDER_PAID";
        }
    }

    @Test
    @DisplayName("★★★ 没有事务时**直接投** —— 没有「提交后」可等")
    void publishWithoutTransactionSubmitsNow() {
        SysOutboxMapper mapper = mock(SysOutboxMapper.class);
        when(mapper.insert(any(SysOutbox.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysOutbox.class).setId(42L);
            return 1;
        });
        OutboxPump pump = mock(OutboxPump.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OutboxPump> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(pump);

        new OutboxEventBus(mapper, new ObjectMapper(), provider).publish(new Evt("SUB-1"));

        // 聚合键要带上 —— 泵靠它选处理线，同一张单才会串行
        verify(pump, times(1)).submit(42L, "SUB-1");
    }

    @Test
    @DisplayName("没有泵的部署照样只写库 —— 队列是那张表，少一边不丢事件")
    void withoutPumpStillWritesRow() {
        SysOutboxMapper mapper = mock(SysOutboxMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OutboxPump> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        new OutboxEventBus(mapper, new ObjectMapper(), provider).publish(new Evt("SUB-2"));

        verify(mapper, times(1)).insert(any(SysOutbox.class));
    }

    @Test
    @DisplayName("pay-owned stable event number safely bridges and rejects changed replay facts")
    void stableEventNumberIsIdempotentAndImmutable() {
        SysOutboxMapper mapper = mock(SysOutboxMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OutboxPump> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        var stored = new java.util.concurrent.ConcurrentHashMap<String, SysOutbox>();
        when(mapper.insertIfAbsent(any(SysOutbox.class))).thenAnswer(invocation -> {
            SysOutbox row = invocation.getArgument(0);
            row.setId(84L);
            return stored.putIfAbsent(row.getEventNo(), row) == null ? 1 : 0;
        });
        when(mapper.findByEventNo("EVT-STABLE")).thenAnswer(invocation -> stored.get("EVT-STABLE"));
        var bus = new OutboxEventBus(mapper, new ObjectMapper(), provider);

        bus.publish("EVT-STABLE", new Evt("SUB-3"));
        bus.publish("EVT-STABLE", new Evt("SUB-3"));

        verify(mapper, times(2)).insertIfAbsent(any(SysOutbox.class));
        verify(mapper, times(2)).findByEventNo("EVT-STABLE");
        assertThat(stored).hasSize(1);
        assertThatThrownBy(() -> bus.publish("EVT-STABLE", new Evt("SUB-4")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("different immutable facts");
    }
}
