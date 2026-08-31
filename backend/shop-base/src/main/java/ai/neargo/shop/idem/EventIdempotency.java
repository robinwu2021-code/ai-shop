package ai.neargo.shop.idem;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 事件级幂等 —— <b>三层保证里的第二层</b>。
 *
 * <p>Outbox 的投递语义是 <b>at-least-once</b>：失败的事件留在队列里重投。
 * 所以「消费者必须自己幂等」不是建议，是这套机制成立的前提
 * （{@code OutboxDispatcher} 的类注释里就写着这句）。这里把那件事做成一行调用：
 *
 * <pre>{@code
 * events.once(event.eventNo(), "settle-bill", () -> settleService.generateForOrder(orderNo));
 * }</pre>
 *
 * <h2>与 {@link IdempotencyService} 的区别 —— 刻意不合并</h2>
 * 那一个是<b>接口级</b>的：键是客户端传来的 {@code Idempotency-Key} + 端点，
 * 服务的是「用户手滑点了两次提交」，并存 {@code result_json} 供重放时原样返回。
 * 这一个是<b>域内事件级</b>的：键是事件号 + 消费者名，不存结果 ——
 * 事件处理没有「返回给谁」这回事。
 *
 * <p>合成一张表的表现是：接口级那 24 小时的过期规则会把事件记录一起清掉，
 * 而 Outbox 的重投可能发生在几天后（消费者一直失败、积压重跑）——
 * 那时它已经忘了自己处理过，<b>于是同一笔钱记两遍</b>。
 *
 * <h2>并发靠唯一索引，不靠先查后插</h2>
 * 两个投递线程同时进来时，先插成功的执行业务，后插的撞
 * {@link DuplicateKeyException} —— 那正是「已经处理过」的信号。
 * 先查后插的话两个都会查到「没处理过」，然后<b>都执行</b>。
 */
@Component
public class EventIdempotency {

    private final SysEventConsumedMapper mapper;

    public EventIdempotency(SysEventConsumedMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 执行一次，且只执行一次。
     *
     * <p><b>标记与业务在同一个事务里</b>（{@code REQUIRED}，跟着调用方的事务走）：
     * 业务失败回滚时标记也要跟着回滚，否则这条事件就<b>永远不会再被处理</b> ——
     * 而它的表现是「消息投过了、业务没做成，重投也没用」，
     * 排查时最容易得出的结论是「消费者没收到」，而事实恰好相反。
     *
     * @param eventNo 事件号
     * @param handler 消费者名。同一个事件被多个消费者各处理一次是正常的
     * @param action  业务动作
     * @return true=这次真的执行了；false=之前已经执行过，本次跳过
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean once(String eventNo, String handler, Runnable action) {
        if (eventNo == null || eventNo.isBlank() || handler == null || handler.isBlank()) {
            /*
             * **宁可拒绝也不要放行。** 缺键时「执行一次」这句话无从保证，
             * 而放行的表现是重投时又执行一遍 —— 那正是这个类要防的事。
             */
            throw new IllegalArgumentException(
                    "事件幂等要求 eventNo 与 handler 都不为空：eventNo=" + eventNo + " handler=" + handler);
        }
        SysEventConsumed row = new SysEventConsumed();
        row.setEventNo(eventNo);
        row.setHandler(handler);
        row.setConsumedAt(LocalDateTime.now());
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            return false;   // 已经处理过
        }
        action.run();
        return true;
    }
}
