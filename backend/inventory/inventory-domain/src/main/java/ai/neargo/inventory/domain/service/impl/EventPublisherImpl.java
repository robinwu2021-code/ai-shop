package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvEventPending;
import ai.neargo.inventory.domain.mapper.InventoryMappers.EventPendingMapper;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.EventPublisher;
import ai.neargo.inventory.domain.support.InvKeys;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

@ConditionalOnInventory
@Service
public class EventPublisherImpl implements EventPublisher {

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final EventPendingMapper pendingMapper;

    public EventPublisherImpl(EventPendingMapper pendingMapper) {
        this.pendingMapper = pendingMapper;
    }

    /** 必须在业务事务里：业务回滚，事件也不该存在。 */
    @Override
    @Transactional(transactionManager = "invTransactionManager", propagation = Propagation.MANDATORY)
    public void publish(Caller caller, String eventType, String resourceType, String resourceId, int resourceVersion,
                        Object payload) {
        InvEventPending row = new InvEventPending();
        row.setEventId(InvKeys.next(InvKeys.EVENT));
        row.setOwnerId(caller.ownerId());
        row.setEventType(eventType);
        row.setResourceType(resourceType);
        row.setResourceId(resourceId);
        row.setResourceVersion(resourceVersion);
        row.setPayload(payload == null ? null : JSON.writeValueAsString(payload));
        row.setActor(caller.actor());
        row.setTraceId(caller.traceId());
        row.setOccurredAt(LocalDateTime.now());
        pendingMapper.insert(row);
    }
}
