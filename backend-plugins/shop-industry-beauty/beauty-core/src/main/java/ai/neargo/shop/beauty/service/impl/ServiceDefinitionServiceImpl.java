package ai.neargo.shop.beauty.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.ServiceDefinitionQuery;
import ai.neargo.shop.beauty.dto.ServiceDefinitionVO;
import ai.neargo.shop.beauty.dto.ServiceDefinitionVersionVO;
import ai.neargo.shop.beauty.entity.BtyActionLog;
import ai.neargo.shop.beauty.entity.BtyServiceDefinition;
import ai.neargo.shop.beauty.entity.BtyServiceDefinitionVersion;
import ai.neargo.shop.beauty.entity.BtyServiceRequirement;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.service.ServiceDefinitionService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.MybatisPages;
import ai.neargo.shop.common.config.PlatformWritePrerequisites;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.beauty.BeautyDefinitionVersionPublished;
import ai.neargo.shop.spi.product.ServiceProductFactsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.Map;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Resolves external product facts before a short primary transaction; immutable versions own no sale price. */
public class ServiceDefinitionServiceImpl implements ServiceDefinitionService {
    private final BeautyMappers.ServiceDefinitionMapper definitions;
    private final BeautyMappers.ServiceDefinitionVersionMapper versions;
    private final BeautyMappers.ServiceRequirementMapper requirements;
    private final BeautyMappers.ActionLogMapper actions;
    private final IdempotencyService idempotency;
    private final OutboxPublisher publisher;
    private final ObjectProvider<ServiceProductFactsPort> products;
    private final TransactionTemplate transactions;
    private final DataSource source;
    private final ObjectMapper json;

    public ServiceDefinitionServiceImpl(BeautyMappers.ServiceDefinitionMapper definitions,
            BeautyMappers.ServiceDefinitionVersionMapper versions, BeautyMappers.ServiceRequirementMapper requirements,
            BeautyMappers.ActionLogMapper actions, IdempotencyService idempotency, OutboxPublisher publisher,
            ObjectProvider<ServiceProductFactsPort> products, PlatformTransactionManager manager,
            DataSource source, ObjectMapper json) {
        this.definitions = definitions;
        this.versions = versions;
        this.requirements = requirements;
        this.actions = actions;
        this.idempotency = idempotency;
        this.publisher = publisher;
        this.products = products;
        this.transactions = new TransactionTemplate(manager);
        this.source = source;
        this.json = json;
    }

    @Override
    public ServiceDefinitionVersionVO publishDefinitionVersion(String entityNo, String currentStoreNo, String actorNo,
            PublishServiceDefinitionVersionReq req, String idempotencyKey) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Definition publication must start outside a caller transaction");
        }
        String endpoint = identity("PUBLISH_DEFINITION", entityNo, currentStoreNo, actorNo);
        String key = IdempotencyScope.requireKey(idempotencyKey);
        if (req == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        String originalSnapshot = IdempotencyScope.requestHash(req.skuNo(), json.writeValueAsString(req.duration()),
                json.writeValueAsString(req.requirements()));
        String snapshot = req.notes().isEmpty() ? originalSnapshot
                : IdempotencyScope.requestHash("BEAUTY_DEFINITION_NOTES_V1", originalSnapshot,
                        json.writeValueAsString(req.notes()));
        String hash = IdempotencyScope.requestHash(req.expectedRevision().toString(), snapshot);
        // Transport failure must not hide an already committed idempotent response.
        ServiceProductFactsPort.Fact fact = null;
        RuntimeException failure = null;
        try { fact = products.getObject().get(entityNo, currentStoreNo, req.skuNo()); }
        catch (RuntimeException ex) { failure = ex; }
        ServiceProductFactsPort.Fact product = fact;
        RuntimeException productFailure = failure;
        try {
            return DataScopeContext.executeWithoutScope(() -> transactions.execute(status -> {
                new PlatformWritePrerequisites().require(source);
                return idempotency.execute(key, endpoint, actorNo, hash, ServiceDefinitionVersionVO.class, () -> {
                    if (productFailure != null) { throw productFailure; }
                    validateProduct(entityNo, currentStoreNo, req, product);
                    return publish(entityNo, currentStoreNo, actorNo, req, key, snapshot);
                });
            }));
        } catch (DuplicateKeyException | ConcurrencyFailureException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private void validateProduct(String entity, String store, PublishServiceDefinitionVersionReq req,
            ServiceProductFactsPort.Fact product) {
        if (product == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        if (!entity.equals(product.entityNo()) || !store.equals(product.storeNo())
                || !req.skuNo().equals(product.skuNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
        if (product.durationMin() != req.duration().minutes()
                || req.requirements().stream().anyMatch(row -> row.skillGoodsNo() != null
                && !row.skillGoodsNo().equals(product.goodsNo()))) { throw BizException.of(ErrorCode.BAD_REQUEST); }
    }

    private ServiceDefinitionVersionVO publish(String entity, String store, String actor,
            PublishServiceDefinitionVersionReq req, String key, String snapshot) {
        var head = definitions.selectOne(Wrappers.<BtyServiceDefinition>lambdaQuery()
                .eq(BtyServiceDefinition::getTenantNo, "MAIN").eq(BtyServiceDefinition::getEntityNo, entity)
                .eq(BtyServiceDefinition::getStoreNo, store).eq(BtyServiceDefinition::getSkuNo, req.skuNo()));
        String versionNo = BizKey.next("BTDV");
        long revision;
        if (head == null) {
            if (req.expectedRevision() != 0) { throw BizException.of(ErrorCode.CONFLICT); }
            head = new BtyServiceDefinition();
            head.setEntityNo(entity);
            head.setStoreNo(store);
            head.setSkuNo(req.skuNo());
            head.setDefinitionNo(BizKey.next("BTD"));
            head.setCurrentDefinitionVersionNo(versionNo);
            head.setStatus("ACTIVE");
            head.setVersion(1L);
            definitions.insert(head);
            revision = 1;
        } else {
            head = definitions.selectScopedForUpdate(entity, store, head.getDefinitionNo());
            if (head == null || req.expectedRevision() == 0 || !req.expectedRevision().equals(head.getVersion())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            revision = Math.addExact(head.getVersion(), 1L);
        }
        var version = new BtyServiceDefinitionVersion();
        version.setEntityNo(entity);
        version.setDefinitionNo(head.getDefinitionNo());
        version.setDefinitionVersionNo(versionNo);
        version.setRevisionIndex(Math.toIntExact(revision));
        version.setDurationMinSnapshot(req.duration().minutes());
        version.setBufferBeforeMin(req.duration().bufferBeforeMin());
        version.setBufferAfterMin(req.duration().bufferAfterMin());
        version.setStepsTemplate("[{\"stepCode\":\"SERVICE\",\"sequenceNo\":0}]");
        version.setNotes(json.writeValueAsString(req.notes()));
        version.setSchemaVersion(1);
        version.setSnapshotHash(snapshot);
        versions.insert(version);
        for (var input : req.requirements()) {
            var row = new BtyServiceRequirement();
            row.setEntityNo(entity);
            row.setRequirementNo(BizKey.next("BTR"));
            row.setDefinitionVersionNo(versionNo);
            row.setRoleCode(input.roleCode());
            row.setResourceType(input.resourceType());
            row.setSkillGoodsNo(input.skillGoodsNo());
            row.setQty(input.qty());
            row.setSequenceNo(input.sequenceNo());
            row.setOffsetMin(input.offsetMin());
            row.setDurationMin(input.durationMin());
            row.setSelectionRule(input.selectionRule());
            requirements.insert(row);
        }
        if (req.expectedRevision() > 0 && definitions.switchCurrentVersion(entity, store, head.getDefinitionNo(),
                req.expectedRevision(), versionNo, actor, LocalDateTime.now(ZoneOffset.UTC)) != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var action = new BtyActionLog();
        action.setEntityNo(entity);
        action.setActionNo(BizKey.next("BTAL"));
        action.setStoreNo(store);
        action.setAggregateType("BEAUTY_SERVICE_DEFINITION");
        action.setAggregateNo(head.getDefinitionNo());
        action.setAction("DEFINITION_VERSION_PUBLISHED");
        action.setOperatorRef(actor);
        action.setExpectedRevision(req.expectedRevision());
        action.setResultRevision(revision);
        action.setSafeSnapshot(json.writeValueAsString(Map.of("definitionVersionNo", versionNo, "hash", snapshot)));
        action.setRequestRef(IdempotencyScope.requestHash(key));
        actions.insert(action);
        publisher.publish(new BeautyDefinitionVersionPublished(head.getDefinitionNo(), entity, store, versionNo,
                revision, snapshot),
                new PublishOptions("BEAUTY:DEFINITION:" + IdempotencyScope.requestHash(entity, store, req.skuNo()), 1));
        return new ServiceDefinitionVersionVO(head.getDefinitionNo(), versionNo, revision, snapshot);
    }

    @Override
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public PageData<ServiceDefinitionVO> listDefinitions(String entityNo, String currentStoreNo, String actorNo,
            ServiceDefinitionQuery query) {
        identity("LIST_DEFINITIONS", entityNo, currentStoreNo, actorNo);
        if (query == null || query.page() < 1 || query.size() < 1 || query.size() > 100
                || query.skuNo() != null && (query.skuNo().isBlank() || query.skuNo().length() > 32)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (query.storeNo() != null && !currentStoreNo.equals(query.storeNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        LocalDateTime at = query.at() == null ? LocalDateTime.now()
                : query.at().atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        return DataScopeContext.executeWithoutScope(() -> {
            var page = definitions.selectPage(new Page<>(query.page(), query.size()),
                    Wrappers.<BtyServiceDefinition>lambdaQuery().eq(BtyServiceDefinition::getTenantNo, "MAIN")
                            .eq(BtyServiceDefinition::getEntityNo, entityNo)
                            .eq(BtyServiceDefinition::getStoreNo, currentStoreNo)
                            .eq(BtyServiceDefinition::getStatus, "ACTIVE")
                            .eq(query.skuNo() != null, BtyServiceDefinition::getSkuNo, query.skuNo())
                            .le(BtyServiceDefinition::getCreatedAt, at)
                            .exists("SELECT 1 FROM bty_service_definition_version v "
                                    + "WHERE v.tenant_no=bty_service_definition.tenant_no "
                                    + "AND v.entity_no=bty_service_definition.entity_no "
                                    + "AND v.definition_no=bty_service_definition.definition_no "
                                    + "AND v.deleted=0 AND v.created_at<={0}", at)
                            .orderByAsc(BtyServiceDefinition::getDefinitionNo));
            return MybatisPages.of(page.convert(head -> view(entityNo, head, at)));
        });
    }

    private ServiceDefinitionVO view(String entity, BtyServiceDefinition head, LocalDateTime at) {
        var version = versions.selectOne(Wrappers.<BtyServiceDefinitionVersion>lambdaQuery()
                .eq(BtyServiceDefinitionVersion::getTenantNo, "MAIN")
                .eq(BtyServiceDefinitionVersion::getEntityNo, entity)
                .eq(BtyServiceDefinitionVersion::getDefinitionNo, head.getDefinitionNo())
                .le(BtyServiceDefinitionVersion::getCreatedAt, at)
                .orderByDesc(BtyServiceDefinitionVersion::getCreatedAt, BtyServiceDefinitionVersion::getRevisionIndex)
                .last("LIMIT 1"));
        if (version == null) { throw new IllegalStateException("Published definition has no scoped version"); }
        var children = requirements.selectList(Wrappers.<BtyServiceRequirement>lambdaQuery()
                .eq(BtyServiceRequirement::getTenantNo, "MAIN").eq(BtyServiceRequirement::getEntityNo, entity)
                .eq(BtyServiceRequirement::getDefinitionVersionNo, version.getDefinitionVersionNo())
                .orderByAsc(BtyServiceRequirement::getSequenceNo, BtyServiceRequirement::getRoleCode,
                        BtyServiceRequirement::getResourceType));
        var result = children.stream().map(row -> new PublishServiceDefinitionVersionReq.Requirement(row.getRoleCode(),
                row.getResourceType(), row.getSkillGoodsNo(), row.getQty(), row.getSequenceNo(), row.getOffsetMin(),
                row.getDurationMin(), row.getSelectionRule())).toList();
        var publishedAt = version.getCreatedAt().atZone(ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime();
        return new ServiceDefinitionVO(head.getDefinitionNo(), head.getStoreNo(), head.getSkuNo(), head.getVersion(),
                version.getDefinitionVersionNo(), version.getRevisionIndex(), publishedAt,
                new PublishServiceDefinitionVersionReq.Duration(version.getDurationMinSnapshot(),
                        version.getBufferBeforeMin(), version.getBufferAfterMin()), result, version.getSnapshotHash(),
                version.getNotes() == null ? List.of()
                        : List.of(json.readValue(version.getNotes(), String[].class)));
    }

    private String identity(String operation, String entity, String store, String actor) {
        if (actor == null || actor.isBlank()) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        return IdempotencyScope.endpoint("BEAUTY", operation, entity, store);
    }
}
