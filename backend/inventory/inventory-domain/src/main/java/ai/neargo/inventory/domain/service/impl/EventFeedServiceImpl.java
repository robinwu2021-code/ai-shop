package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvEventLog;
import ai.neargo.inventory.domain.entity.InvEventPending;
import ai.neargo.inventory.domain.entity.InvEventSubscription;
import ai.neargo.inventory.domain.mapper.InventoryMappers.EventLogMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.EventPendingMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.EventSubscriptionMapper;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.EventFeedService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@ConditionalOnInventory
@Service
public class EventFeedServiceImpl implements EventFeedService {

    private final EventPendingMapper pendingMapper;
    private final EventLogMapper logMapper;
    private final EventSubscriptionMapper subscriptionMapper;

    public EventFeedServiceImpl(EventPendingMapper pendingMapper, EventLogMapper logMapper,
                                EventSubscriptionMapper subscriptionMapper) {
        this.pendingMapper = pendingMapper;
        this.logMapper = logMapper;
        this.subscriptionMapper = subscriptionMapper;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public Page pull(Caller caller, Long afterSeq, int limit) {
        InvEventSubscription sub = lockSubscription(caller.ownerId(), caller.systemCode());
        sequence(caller.ownerId());
        long from = afterSeq == null ? sub.getAckedSeq() : afterSeq;
        if (from < sub.getPrunedSeq()) {
            // 游标早于已清理的位置：中间的事件已经没了，继续拉会静默漏掉 —— 让宿主从确认点重来
            throw InventoryException.of(InventoryError.EVENTS_CURSOR_EXPIRED, sub.getAckedSeq());
        }
        int page = Math.max(1, Math.min(limit, MAX_PAGE));
        List<Event> events = logMapper.selectList(Wrappers.<InvEventLog>lambdaQuery()
                        .eq(InvEventLog::getOwnerId, caller.ownerId())
                        .gt(InvEventLog::getSeq, from)
                        .orderByAsc(InvEventLog::getSeq)
                        .last("LIMIT " + page))
                .stream()
                .map(e -> new Event(e.getSeq(), e.getEventId(), e.getEventType(), e.getOwnerId(), e.getResourceType(),
                        e.getResourceId(), e.getResourceVersion(), e.getOccurredAt(), e.getActor(), e.getTraceId(),
                        e.getPayload()))
                .toList();
        long last = events.isEmpty() ? from : events.get(events.size() - 1).seq();
        return new Page(events, last, sub.getAckedSeq());
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void ack(Caller caller, long seq) {
        InvEventSubscription sub = lockSubscription(caller.ownerId(), caller.systemCode());
        if (seq > sub.getAckedSeq()) {
            sub.setAckedSeq(seq);
            subscriptionMapper.updateById(sub);
        }
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public int prune(String ownerId, String systemCode) {
        InvEventSubscription sub = lockSubscription(ownerId, systemCode);
        int removed = logMapper.delete(Wrappers.<InvEventLog>lambdaQuery()
                .eq(InvEventLog::getOwnerId, ownerId)
                .le(InvEventLog::getSeq, sub.getAckedSeq()));
        if (sub.getAckedSeq() > sub.getPrunedSeq()) {
            sub.setPrunedSeq(sub.getAckedSeq());
            subscriptionMapper.updateById(sub);
        }
        return removed;
    }

    /**
     * 排序器：把已提交的待排序事件按 id 顺序搬进日志、分配序号。持着订阅行的锁做，
     * 同一业主同一时刻只有一个排序器 —— 序号对这个业主单调。晚提交的小 id 下一轮才看得见，
     * 拿到的是更大的序号，不会被游标越过。
     */
    private void sequence(String ownerId) {
        List<InvEventPending> pending = pendingMapper.selectList(Wrappers.<InvEventPending>lambdaQuery()
                .eq(InvEventPending::getOwnerId, ownerId)
                .orderByAsc(InvEventPending::getId)
                .last("LIMIT 1000"));
        for (InvEventPending p : pending) {
            InvEventLog row = new InvEventLog();
            row.setEventId(p.getEventId());
            row.setOwnerId(p.getOwnerId());
            row.setEventType(p.getEventType());
            row.setResourceType(p.getResourceType());
            row.setResourceId(p.getResourceId());
            row.setResourceVersion(p.getResourceVersion());
            row.setPayload(p.getPayload());
            row.setActor(p.getActor());
            row.setTraceId(p.getTraceId());
            row.setOccurredAt(p.getOccurredAt());
            logMapper.insert(row);
            pendingMapper.deleteById(p.getId());
        }
    }

    private InvEventSubscription lockSubscription(String ownerId, String systemCode) {
        InvEventSubscription sub = select(ownerId, systemCode);
        if (sub == null) {
            InvEventSubscription created = new InvEventSubscription();
            created.setOwnerId(ownerId);
            created.setSystemCode(systemCode);
            created.setAckedSeq(0L);
            created.setPrunedSeq(0L);
            try {
                subscriptionMapper.insert(created);
            } catch (DuplicateKeyException race) {
                // 并发首拉：另一边先建了，照常加锁读
            }
            sub = select(ownerId, systemCode);
        }
        return sub;
    }

    private InvEventSubscription select(String ownerId, String systemCode) {
        return subscriptionMapper.selectOne(Wrappers.<InvEventSubscription>lambdaQuery()
                .eq(InvEventSubscription::getOwnerId, ownerId)
                .eq(InvEventSubscription::getSystemCode, systemCode)
                .last("FOR UPDATE"));
    }
}
