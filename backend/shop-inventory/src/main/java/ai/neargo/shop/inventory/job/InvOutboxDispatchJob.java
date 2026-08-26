package ai.neargo.shop.inventory.job;

import ai.neargo.shop.inventory.entity.InvOutbox;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboxMapper;
import ai.neargo.shop.inventory.service.InventoryEventSink;
import ai.neargo.shop.job.JobSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件投递。**独立库用不了平台的 {@code sys_outbox}**，本域自己带一份。
 *
 * <p><b>没有订阅者时不标已发</b>：事件堆在表里、日志里能看见积压数。
 * 反过来做（当作发过了）是这类表最常见的坏法 —— 等真有人来接的时候，
 * 前面几个月的事件已经没了，而没有任何记录说明它们去哪了。
 */
@Profile("worker")
@Component
public class InvOutboxDispatchJob {

    private static final Logger log = LoggerFactory.getLogger(InvOutboxDispatchJob.class);

    private static final int BATCH = 100;
    /** 退避到多少次就不再自动重试 —— 再往后是人的事，让它在积压里显出来。 */
    private static final int MAX_RETRY = 8;

    private final OutboxMapper outboxMapper;
    private final List<InventoryEventSink> sinks;
    private final JobSupport jobs;

    public InvOutboxDispatchJob(OutboxMapper outboxMapper, List<InventoryEventSink> sinks,
                                JobSupport jobs) {
        this.outboxMapper = outboxMapper;
        this.sinks = sinks;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.job.inv-outbox.cron:*/5 * * * * *}")
    @SchedulerLock(name = "inv-outbox-dispatch", lockAtLeastFor = "PT3S", lockAtMostFor = "PT2M")
    public void run() {
        jobs.run("inv-outbox-dispatch", () -> {
            List<InvOutbox> pending = outboxMapper.selectList(Wrappers.<InvOutbox>lambdaQuery()
                    .eq(InvOutbox::getStatus, "PENDING")
                    .lt(InvOutbox::getRetryCount, MAX_RETRY)
                    .orderByAsc(InvOutbox::getId)
                    .last("LIMIT " + BATCH));
            if (pending.isEmpty()) {
                return "pending=0";
            }
            if (sinks.isEmpty()) {
                // 一个订阅者都没有：**什么都不改**，让积压看得见
                log.warn("进销存事件无订阅者，积压 {} 条（本轮不投递、不标记）", pending.size());
                return "no-sink pending=" + pending.size();
            }
            int ok = 0;
            for (InvOutbox e : pending) {
                boolean delivered = true;
                for (InventoryEventSink sink : sinks) {
                    try {
                        delivered &= sink.deliver(e.getEventNo(), e.getOwnerId(),
                                e.getEventType(), e.getPayload());
                    } catch (RuntimeException ex) {
                        delivered = false;
                        e.setLastError(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    }
                }
                if (delivered) {
                    e.setStatus("SENT");
                    e.setSentAt(LocalDateTime.now());
                    ok++;
                } else {
                    e.setRetryCount(e.getRetryCount() + 1);
                    // 线性退避就够：这不是外部通道，多半是下游短暂不可用
                    e.setNextRetryAt(LocalDateTime.now().plusSeconds(10L * e.getRetryCount()));
                }
                outboxMapper.updateById(e);
            }
            return "sent=" + ok + "/" + pending.size();
        });
    }
}
