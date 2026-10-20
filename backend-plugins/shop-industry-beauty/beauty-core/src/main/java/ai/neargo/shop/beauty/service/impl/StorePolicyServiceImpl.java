package ai.neargo.shop.beauty.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.beauty.dto.PolicyVersionVO;
import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;
import ai.neargo.shop.beauty.entity.BtyActionLog;
import ai.neargo.shop.beauty.entity.BtyStorePolicy;
import ai.neargo.shop.beauty.entity.BtyStorePolicyVersion;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.service.StorePolicyService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.PlatformWritePrerequisites;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.beauty.BeautyPolicyVersionPublished;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Publishes immutable facts and a head pointer together; zero-row CAS and unique-key races are conflicts. */
public class StorePolicyServiceImpl implements StorePolicyService {
    private final BeautyMappers.StorePolicyMapper policies;
    private final BeautyMappers.StorePolicyVersionMapper versions;
    private final BeautyMappers.ActionLogMapper actions;
    private final IdempotencyService idempotency;
    private final OutboxPublisher publisher;
    private final DataSource source;
    private final ObjectMapper json;

    public StorePolicyServiceImpl(BeautyMappers.StorePolicyMapper policies,
            BeautyMappers.StorePolicyVersionMapper versions, BeautyMappers.ActionLogMapper actions,
            IdempotencyService idempotency, OutboxPublisher publisher, DataSource source, ObjectMapper json) {
        this.policies = policies;
        this.versions = versions;
        this.actions = actions;
        this.idempotency = idempotency;
        this.publisher = publisher;
        this.source = source;
        this.json = json;
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public PolicyVersionVO publishPolicyVersion(String entityNo, String currentStoreNo, String actorNo,
            PublishStorePolicyVersionReq req, String idempotencyKey) {
        String key = IdempotencyScope.requireKey(idempotencyKey);
        if (req == null || actorNo == null || actorNo.isBlank()) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        String endpoint = IdempotencyScope.endpoint("BEAUTY", "PUBLISH_STORE_POLICY", entityNo, currentStoreNo);
        new PlatformWritePrerequisites().require(source);
        var policy = req.policy();
        String snapshot = IdempotencyScope.requestHash(req.validFrom().toString(),
                policy.bookingAheadDays().toString(), policy.cancelRule().freeCancelBeforeMinutes().toString(),
                policy.cancelRule().allowCustomerCancel().toString(), String.join(",", policy.allowedModes()));
        String requestHash = IdempotencyScope.requestHash(req.expectedRevision().toString(), snapshot);
        try {
            // Caller has verified auth/resource facts; every SQL below still includes trusted entity/store or parent.
            return DataScopeContext.executeWithoutScope(() -> idempotency.execute(key, endpoint, actorNo, requestHash,
                    PolicyVersionVO.class, () -> publish(entityNo, currentStoreNo, actorNo, req, key, snapshot)));
        } catch (DuplicateKeyException | ConcurrencyFailureException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private PolicyVersionVO publish(String entity, String store, String actor, PublishStorePolicyVersionReq req,
            String key, String snapshot) {
        BtyStorePolicy head = policies.selectOne(Wrappers.<BtyStorePolicy>lambdaQuery()
                .eq(BtyStorePolicy::getTenantNo, "MAIN").eq(BtyStorePolicy::getEntityNo, entity)
                .eq(BtyStorePolicy::getStoreNo, store));
        String versionNo = BizKey.next("BTPV");
        long revision;
        if (head == null) {
            if (req.expectedRevision() != 0) { throw BizException.of(ErrorCode.CONFLICT); }
            head = new BtyStorePolicy();
            head.setEntityNo(entity);
            head.setPolicyNo(BizKey.next("BTP"));
            head.setStoreNo(store);
            head.setCurrentPolicyVersionNo(versionNo);
            head.setStatus("ACTIVE");
            head.setVersion(1L);
            policies.insert(head); // Stable store unique key arbitrates first creation; no empty locking query.
            revision = 1;
        } else {
            head = policies.selectScopedForUpdate(entity, store, head.getPolicyNo());
            if (head == null || req.expectedRevision() == 0 || !req.expectedRevision().equals(head.getVersion())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            revision = Math.addExact(head.getVersion(), 1L);
        }
        BtyStorePolicyVersion version = new BtyStorePolicyVersion();
        version.setEntityNo(entity);
        version.setPolicyVersionNo(versionNo);
        version.setPolicyNo(head.getPolicyNo());
        version.setRevisionIndex(Math.toIntExact(revision));
        version.setBookingAheadDays(req.policy().bookingAheadDays());
        version.setCancelRule(json.writeValueAsString(req.policy().cancelRule()));
        version.setAllowedModes(json.writeValueAsString(req.policy().allowedModes()));
        version.setEffectiveAt(req.validFrom().toLocalDateTime());
        version.setSchemaVersion(1);
        version.setSnapshotHash(snapshot);
        versions.insert(version);
        if (req.expectedRevision() > 0 && policies.switchCurrentVersion(entity, store, head.getPolicyNo(),
                req.expectedRevision(), versionNo, actor, LocalDateTime.now(ZoneOffset.UTC)) != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        BtyActionLog action = new BtyActionLog();
        action.setEntityNo(entity);
        action.setActionNo(BizKey.next("BTAL"));
        action.setStoreNo(store);
        action.setAggregateType("BEAUTY_STORE_POLICY");
        action.setAggregateNo(head.getPolicyNo());
        action.setAction("POLICY_VERSION_PUBLISHED");
        action.setOperatorRef(actor);
        action.setExpectedRevision(req.expectedRevision());
        action.setResultRevision(revision);
        action.setSafeSnapshot(json.writeValueAsString(Map.of("policyVersionNo", versionNo, "hash", snapshot)));
        action.setRequestRef(IdempotencyScope.requestHash(key));
        actions.insert(action);
        publisher.publish(new BeautyPolicyVersionPublished(head.getPolicyNo(), entity, store, versionNo,
                revision, snapshot),
                new PublishOptions("BEAUTY:POLICY:" + IdempotencyScope.requestHash(entity, store), 1));
        return new PolicyVersionVO(head.getPolicyNo(), versionNo, revision, req.validFrom(), snapshot);
    }
}
