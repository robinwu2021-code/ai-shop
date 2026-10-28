package ai.neargo.shop.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSources;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Outbox 消费里调支付，来源记成这个事件（开发计划 M2.1；TDD-支付服务-接入守卫模块pay-guard §2.3 A）。
 */
class OutboxCallSourceTest {

    @Test
    @DisplayName("★★★ 消费者里取到的来源：来源端 OUTBOX、上游接口 = 事件类型；投完解绑")
    void consumerSeesOutboxOrigin() {
        List<CallSource> seen = new ArrayList<>();
        OutboxConsumer consumer = new OutboxConsumer() {
            @Override
            public boolean supports(String eventType) {
                return true;
            }

            @Override
            public void consume(SysOutbox event) {
                seen.add(CallSources.current());
            }
        };
        SysOutboxMapper mapper = mock(SysOutboxMapper.class);
        SysOutbox event = new SysOutbox();
        event.setId(1L);
        event.setEventType("ORDER_PAID");
        event.setStatus(SysOutbox.PENDING);
        when(mapper.selectById(1L)).thenReturn(event);

        new OutboxDispatcher(mapper, List.of(consumer), mock(PlatformTransactionManager.class)).dispatchOne(1L);

        assertThat(seen).singleElement().satisfies(s -> {
            assertThat(s.originClient()).isEqualTo(CallSources.OUTBOX);
            assertThat(s.originApi()).isEqualTo("ORDER_PAID");
        });
        assertThat(CallSources.current().originClient()).isEqualTo(CallSources.UNKNOWN);
    }
}
