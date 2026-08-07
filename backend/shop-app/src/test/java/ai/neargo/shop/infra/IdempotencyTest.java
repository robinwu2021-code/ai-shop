package ai.neargo.shop.infra;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.SysOutboxMapper;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.idem.IdempotencyService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等与 Outbox 的行为断言 —— 这两条是 S2 起所有写操作的地基，
 * 在没有业务代码的 S0 阶段先把它们钉死，后面每个域接的时候就不用各自验证一遍。
 */
@SpringBootTest
@ActiveProfiles("test")
class IdempotencyTest {

    @Autowired
    private IdempotencyService idempotency;

    @Autowired
    private OutboxEventBus eventBus;

    @Autowired
    private SysOutboxMapper outboxMapper;

    @Test
    @DisplayName("同一个 Idempotency-Key 只执行一次，第二次返回首次结果")
    void sameKeyExecutesOnce() {
        AtomicInteger executions = new AtomicInteger();
        String key = "idem-test-001";

        String first = idempotency.execute(key, "POST /mp/order", "U1", String.class,
                () -> {
                    executions.incrementAndGet();
                    return "SO-0001";
                });
        String second = idempotency.execute(key, "POST /mp/order", "U1", String.class,
                () -> {
                    executions.incrementAndGet();
                    return "SO-0002";   // 若真被执行，结果会不同 —— 断言据此发现问题
                });

        assertThat(first).isEqualTo("SO-0001");
        assertThat(second).isEqualTo("SO-0001");
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("同一个 key 打不同端点互不影响")
    void sameKeyDifferentEndpointIsIndependent() {
        String key = "idem-test-002";
        String order = idempotency.execute(key, "POST /mp/order", "U1", String.class, () -> "order");
        String pay = idempotency.execute(key, "POST /mp/order/pay", "U1", String.class, () -> "pay");

        assertThat(order).isEqualTo("order");
        assertThat(pay).isEqualTo("pay");
    }

    @Test
    @DisplayName("发事件只写库：拿到 PENDING 行，载荷自带消费方所需字段")
    void publishWritesPendingRow() {
        eventBus.publish(new OrderEvents.OrderPaid("SO-9001", "U1", 12800L, "WECHAT"));

        var rows = outboxMapper.selectList(Wrappers.lambdaQuery(ai.neargo.shop.event.SysOutbox.class)
                .eq(ai.neargo.shop.event.SysOutbox::getAggregateId, "SO-9001"));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(OutboxEventBus.PENDING);
        assertThat(rows.get(0).getEventType()).isEqualTo("ORDER_PAID");
        assertThat(rows.get(0).getPayload()).contains("12800").contains("WECHAT");
    }

    @Test
    @DisplayName("事件业务键唯一：同一事件重复发布不会覆盖历史")
    void eventNoIsUnique() {
        eventBus.publish(new OrderEvents.OrderCreated("SO-9002", "U1", List.of("SUB-1"), 100L));
        eventBus.publish(new OrderEvents.OrderCreated("SO-9002", "U1", List.of("SUB-1"), 100L));

        var rows = outboxMapper.selectList(Wrappers.lambdaQuery(ai.neargo.shop.event.SysOutbox.class)
                .eq(ai.neargo.shop.event.SysOutbox::getAggregateId, "SO-9002"));

        // 两条独立事件（业务上确实发生了两次），但 event_no 不同 —— 去重是消费端的职责
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getEventNo()).isNotEqualTo(rows.get(1).getEventNo());
    }
}
