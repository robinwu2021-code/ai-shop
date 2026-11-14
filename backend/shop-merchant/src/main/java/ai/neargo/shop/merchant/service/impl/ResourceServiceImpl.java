package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.merchant.policy.ResourceOccupationGuard;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.config.PlatformWritePrerequisites;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.PlatformPublicationPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.merchant.dto.ResourceQuery;
import ai.neargo.shop.merchant.dto.ResourceVO;
import ai.neargo.shop.merchant.dto.SaveResourceReq;
import ai.neargo.shop.merchant.entity.MchResource;
import ai.neargo.shop.merchant.entity.MchStaffLog;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.service.ResourceService;
import ai.neargo.shop.spi.resource.ResourceChanged;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Physical maintenance shares admission's resource root; canonical STAFF remains owned by personnel maintenance. */
@Service
public class ResourceServiceImpl implements ResourceService {
    private final ResourceOccupationGuard occupations;
    private final MerchantMappers.ResourceMapper resources;
    private final MerchantMappers.PersonnelMapper people;
    private final MerchantMappers.BookingOwnerMapper parents;
    private final MerchantMappers.MchStaffLogMapper audit;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    private final DataSource source;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    private final OutboxPublisher publisher;

    public ResourceServiceImpl(ResourceOccupationGuard occupations, MerchantMappers.ResourceMapper resources, MerchantMappers.PersonnelMapper people,
            MerchantMappers.BookingOwnerMapper parents, MerchantMappers.MchStaffLogMapper audit,
            IdempotencyService idempotency, ObjectMapper json, @Qualifier("dataSource") DataSource source,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<UnifiedOutboxFacade> facades, ObjectProvider<OutboxConsumer> consumers,
            @Value("${shop.resource.publication.archive-only:false}") boolean archiveOnly,
            @Value("${shop.outbox.maintenance:false}") boolean maintenance) {
        this.occupations = occupations;
        this.resources = resources;
        this.people = people;
        this.parents = parents;
        this.audit = audit;
        this.idempotency = idempotency;
        this.json = json;
        this.source = source;
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads = new TransactionTemplate(manager);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
        publisher = PlatformPublicationPublisher.create(facades::getIfAvailable, manager, () -> source, json,
                consumers::orderedStream, Set.of("MERCHANT_RESOURCE_CHANGED"), archiveOnly, maintenance);
    }

    @Override
    public PageData<ResourceVO> listResources(String entity, String store, String actor, ResourceQuery query) {
        identity(entity, store, actor);
        if (query == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        requireStore(store, query.storeNo());
        return DataScopeContext.executeWithoutScope(() -> reads.execute(status -> {
            scope(entity, store);
            var filter = Wrappers.<MchResource>lambdaQuery().eq(MchResource::getTenantNo, "MAIN")
                    .eq(MchResource::getEntityNo, entity)
                    .eq(query.type() != null, MchResource::getResourceType, query.type())
                    .eq(query.status() != null, MchResource::getStatus, query.status())
                    .like(query.keyword() != null, MchResource::getName, query.keyword())
                    .and(q -> q.eq(MchResource::getStoreNo, store)
                        .in(MchResource::getResourceType, Set.of("ROOM", "SEAT", "DEVICE"))
                        .or(staff -> staff.eq(MchResource::getResourceType, "STAFF")
                            .exists("SELECT 1 FROM mch_personnel_store a JOIN mch_personnel p "
                                + "ON p.tenant_no=a.tenant_no AND p.entity_no=a.entity_no "
                                + "AND p.personnel_no=a.personnel_no AND p.deleted=0 "
                                + "WHERE a.tenant_no=mch_resource.tenant_no AND a.entity_no=mch_resource.entity_no "
                                + "AND a.personnel_no=mch_resource.personnel_no AND a.store_no={0} "
                                + "AND a.deleted=0 AND a.status='ACTIVE' "
                                + "AND (a.valid_to IS NULL OR a.valid_to>{1})", store, now())))
                    .orderByDesc(MchResource::getId);
            var page = resources.selectPage(new Page<>(query.page(), query.size()), filter);
            return PageData.of(page.getRecords().stream().map(this::view).toList(), page.getTotal(),
                    query.page(), query.size());
        }));
    }

    @Override
    public ResourceVO saveResource(String entity, String store, String actor, SaveResourceReq request, String key) {
        identity(entity, store, actor);
        if (request == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        requireStore(store, request.storeNo());
        String replayKey = IdempotencyScope.requireKey(key);
        String endpoint = IdempotencyScope.endpoint("RESOURCE", "SAVE", entity, store);
        String hash = IdempotencyScope.requestHash(json.writeValueAsString(request));
        try {
            return DataScopeContext.executeWithoutScope(() -> writes.execute(status -> {
                scope(entity, store);
                new PlatformWritePrerequisites().require(source);
                return idempotency.execute(replayKey, endpoint, actor, hash, ResourceVO.class, () -> {
                    MchResource row;
                    if (request.resourceNo() == null) {
                        row = new MchResource();
                        row.setTenantNo("MAIN");
                        row.setEntityNo(entity);
                        row.setResourceNo(BizKey.next("MRS"));
                        row.setStoreNo(store);
                        row.setResourceType(request.type());
                        row.setName(request.name());
                        row.setCapacity(request.capacity());
                        row.setStatus(request.status());
                        row.setVersion(1L);
                        row.setCreatedBy(actor);
                        row.setUpdatedBy(actor);
                        one(resources.insert(row));
                    } else {
                        var trusted = parents.lockScope("MAIN", entity, store);
                        row = resources.lockScopedResource(trusted, request.resourceNo());
                        if (row == null || !request.resourceNo().equals(row.getResourceNo())) {
                            throw BizException.of(ErrorCode.NOT_FOUND);
                        }
                        if ("STAFF".equals(row.getResourceType()) || !request.type().equals(row.getResourceType())
                                || row.getPersonnelNo() != null || !store.equals(row.getStoreNo())) {
                            throw BizException.of(ErrorCode.BAD_REQUEST);
                        }
                        if (row.getVersion().longValue() != request.expectedRevision()) {
                            throw BizException.of(ErrorCode.CONFLICT);
                        }
                        fence(trusted, row, request);
                        one(resources.saveMaintenance(entity, store, row.getResourceNo(), request.expectedRevision(),
                                request.name(), request.capacity(), request.status(), actor, now()));
                        row.setName(request.name());
                        row.setCapacity(request.capacity());
                        row.setStatus(request.status());
                        row.setVersion(row.getVersion() + 1);
                    }
                    evidence(row, actor, key);
                    return view(row);
                });
            }));
        } catch (DuplicateKeyException | ConcurrencyFailureException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private void fence(ResourceCommandScopeRow scope, MchResource row, SaveResourceReq request) {
        String entity = scope.entityNo();
        occupations.requireNoWork(scope, row.getResourceNo());
        if (row.getOpenOccupationNo() != null || people.countPendingMaintenance(entity) > 0
                || people.countResourceWork(entity, row.getResourceNo()) > 0
                || !row.getCapacity().equals(request.capacity())
                    && resources.countMaintenanceBuckets(entity, row.getResourceNo()) > 0) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
    private void evidence(MchResource row, String actor, String key) {
        String ref = IdempotencyScope.requestHash(key);
        var log = new MchStaffLog();
        log.setTenantNo("MAIN");
        log.setEntityNo(row.getEntityNo());
        log.setStoreNo(row.getStoreNo());
        log.setLogNo(BizKey.next("MSL"));
        log.setActorAccountNo(actor);
        log.setAction("RESOURCE_SAVED");
        log.setDetail(json.writeValueAsString(Map.of("resourceNo", row.getResourceNo(), "revision", row.getVersion(),
                "capacity", row.getCapacity(), "status", row.getStatus(), "requestRef", ref)));
        one(audit.insert(log));
        publisher.publish(new ResourceChanged(row.getResourceNo(), row.getEntityNo(), row.getStoreNo(),
                "RESOURCE_SAVED", row.getVersion(), ref), new PublishOptions("RESOURCE:" + row.getResourceNo(), 1));
    }
    private ResourceVO view(MchResource row) {
        return new ResourceVO(row.getResourceNo(), row.getResourceType(), row.getStoreNo(), row.getPersonnelNo(),
                row.getName(), row.getCapacity(), row.getStatus(), row.getVersion(), row.getOpenOccupationNo() != null);
    }
    private void scope(String entity, String store) {
        var trusted = parents.lockScope("MAIN", entity, store);
        if (trusted == null || !entity.equals(trusted.entityNo()) || !store.equals(trusted.storeNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }
    private void identity(String entity, String store, String actor) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Resource maintenance must start outside a caller transaction");
        }
        reference(entity, 32);
        reference(store, 32);
        reference(actor, 64);
    }
    private void reference(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.trim())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    private void requireStore(String store, String requested) {
        if (requested != null && !store.equals(requested)) { throw BizException.of(ErrorCode.FORBIDDEN); }
    }
    private void one(int count) { if (count != 1) { throw BizException.of(ErrorCode.CONFLICT); } }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
