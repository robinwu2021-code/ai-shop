package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvAudit;
import ai.neargo.inventory.domain.mapper.InventoryMappers.AuditMapper;
import ai.neargo.inventory.domain.service.AuditService;
import ai.neargo.inventory.domain.service.Caller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

@ConditionalOnInventory
@Service
public class AuditServiceImpl implements AuditService {

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final AuditMapper auditMapper;

    public AuditServiceImpl(AuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    /** 必须在业务事务里：审计记的是「发生了的事」，业务回滚它也该没有。 */
    @Override
    @Transactional(transactionManager = "invTransactionManager", propagation = Propagation.MANDATORY)
    public void record(Caller caller, String action, String resourceType, String resourceId,
                       Object before, Object after, String reason) {
        InvAudit row = new InvAudit();
        row.setSystemCode(caller.systemCode());
        row.setOwnerId(caller.ownerId());
        row.setActor(caller.actor());
        row.setAction(action);
        row.setResourceType(resourceType);
        row.setResourceId(resourceId);
        row.setBeforeBody(before == null ? null : JSON.writeValueAsString(before));
        row.setAfterBody(after == null ? null : JSON.writeValueAsString(after));
        row.setReason(reason);
        row.setRequestId(caller.requestId());
        row.setTraceId(caller.traceId());
        auditMapper.insert(row);
    }
}
