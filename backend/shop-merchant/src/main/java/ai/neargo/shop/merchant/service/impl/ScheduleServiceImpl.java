package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.PlatformWritePrerequisites;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.event.outbox.runtime.PlatformPublicationPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.dto.SaveScheduleRuleReq;
import ai.neargo.shop.merchant.dto.ScheduleQuery;
import ai.neargo.shop.merchant.dto.ScheduleRuleVO;
import ai.neargo.shop.merchant.dto.ScheduleVO;
import ai.neargo.shop.merchant.entity.MchPersonnelStore;
import ai.neargo.shop.merchant.entity.MchResource;
import ai.neargo.shop.merchant.entity.MchScheduleChangeLog;
import ai.neargo.shop.merchant.entity.MchScheduleRule;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.service.ScheduleService;
import ai.neargo.shop.spi.schedule.ScheduleChanged;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
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

/** Short owner transactions lock personnel before resources and validate the full finite rule before commit. */
@Service
public class ScheduleServiceImpl implements ScheduleService {
    private final MerchantMappers.ResourceMapper resources;
    private final MerchantMappers.PersonnelMapper people;
    private final MerchantMappers.PersonnelStoreMapper assignments;
    private final MerchantMappers.ScheduleRuleMapper rules;
    private final MerchantMappers.ScheduleChangeLogMapper changes;
    private final MerchantMappers.BookingOwnerMapper parents;
    private final ScheduleWindowsPort windows;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    private final DataSource source;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    private final OutboxPublisher publisher;

    public ScheduleServiceImpl(MerchantMappers.ResourceMapper resources, MerchantMappers.PersonnelMapper people,
            MerchantMappers.PersonnelStoreMapper assignments, MerchantMappers.ScheduleRuleMapper rules,
            MerchantMappers.ScheduleChangeLogMapper changes, MerchantMappers.BookingOwnerMapper parents,
            ScheduleWindowsPort windows, IdempotencyService idempotency, ObjectMapper json,
            @Qualifier("dataSource") DataSource source,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<UnifiedOutboxFacade> facades, ObjectProvider<OutboxConsumer> consumers,
            @Value("${shop.schedule.publication.archive-only:false}") boolean archiveOnly,
            @Value("${shop.outbox.maintenance:false}") boolean maintenance) {
        this.resources = resources;
        this.people = people;
        this.assignments = assignments;
        this.rules = rules;
        this.changes = changes;
        this.parents = parents;
        this.windows = windows;
        this.idempotency = idempotency;
        this.json = json;
        this.source = source;
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads = new TransactionTemplate(manager);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
        publisher = PlatformPublicationPublisher.create(facades::getIfAvailable, manager, () -> source, json,
                consumers::orderedStream, Set.of("MERCHANT_SCHEDULE_CHANGED"), archiveOnly, maintenance);
    }

    @Override
    public ScheduleVO listSchedules(String entity, String store, String actor, ScheduleQuery query) {
        identity(entity, store, actor);
        if (query == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        return DataScopeContext.executeWithoutScope(() -> reads.execute(status -> {
            var scope = scope(entity, store);
            var revisions = new LinkedHashMap<String, Long>();
            for (String no : query.resourceNos().stream().sorted().toList()) {
                revisions.put(no, resource(scope, no, false, false).getVersion());
            }
            var rows = rules.selectList(Wrappers.<MchScheduleRule>lambdaQuery()
                    .eq(MchScheduleRule::getTenantNo, "MAIN").eq(MchScheduleRule::getEntityNo, entity)
                    .eq(MchScheduleRule::getStoreNo, store).eq(MchScheduleRule::getStatus, "ACTIVE")
                    .in(MchScheduleRule::getResourceNo, query.resourceNos())
                    .le(MchScheduleRule::getValidFrom, query.to().toLocalDate().plusDays(2))
                    .and(q -> q.isNull(MchScheduleRule::getValidTo)
                        .or().ge(MchScheduleRule::getValidTo, query.from().toLocalDate().minusDays(2)))
                    .orderByAsc(MchScheduleRule::getResourceNo, MchScheduleRule::getRuleNo).last("LIMIT 10001"));
            if (rows.size() > 10000) { throw BizException.of(ErrorCode.CONFLICT); }
            return new ScheduleVO(query.from(), query.to(), revisions, rows.stream().map(this::view).toList(),
                    expanded(entity, store, query.resourceNos(), query.from().toInstant(), query.to().toInstant()));
        }));
    }

    @Override
    public ScheduleRuleVO saveScheduleRule(String entity, String store, String actor,
            SaveScheduleRuleReq request, String key) {
        identity(entity, store, actor);
        if (request == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        String replayKey = IdempotencyScope.requireKey(key);
        String endpoint = IdempotencyScope.endpoint("SCHEDULE", "SAVE", entity, store);
        String hash = IdempotencyScope.requestHash(json.writeValueAsString(request));
        try {
            return DataScopeContext.executeWithoutScope(() -> writes.execute(status -> {
                var scope = scope(entity, store);
                new PlatformWritePrerequisites().require(source);
                return idempotency.execute(replayKey, endpoint, actor, hash, ScheduleRuleVO.class, () -> {
                    boolean active = "ACTIVE".equals(request.status());
                    var resource = resource(scope, request.resourceNo(), true, active);
                    if (resource.getVersion().longValue() != request.expectedRevision()) {
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                    fence(entity, resource);
                    MchScheduleRule old = null;
                    if (request.ruleNo() != null) {
                        old = rules.selectOne(Wrappers.<MchScheduleRule>lambdaQuery()
                                .eq(MchScheduleRule::getTenantNo, "MAIN").eq(MchScheduleRule::getEntityNo, entity)
                                .eq(MchScheduleRule::getStoreNo, store)
                                .eq(MchScheduleRule::getResourceNo, resource.getResourceNo())
                                .eq(MchScheduleRule::getRuleNo, request.ruleNo())
                                .eq(MchScheduleRule::getStatus, "ACTIVE"));
                        if (old == null || !request.ruleNo().equals(old.getRuleNo())) {
                            throw BizException.of(ErrorCode.NOT_FOUND);
                        }
                        old.setStatus("INACTIVE");
                        one(rules.updateById(old));
                    }
                    String ruleNo = old == null ? null : old.getRuleNo();
                    if (active) {
                        var row = new MchScheduleRule();
                        row.setTenantNo("MAIN");
                        row.setEntityNo(entity);
                        row.setStoreNo(store);
                        row.setResourceNo(resource.getResourceNo());
                        row.setRuleNo(BizKey.next("MSR"));
                        row.setTimezone(request.timezone());
                        row.setWeekdayMask(request.weekdayMask());
                        row.setStartMinute(request.startMinute());
                        row.setEndMinute(request.endMinute());
                        row.setEndDayOffset(request.endDayOffset());
                        row.setValidFrom(request.validFrom());
                        row.setValidTo(request.validTo());
                        row.setStatus("ACTIVE");
                        row.setCreatedBy(actor);
                        row.setUpdatedBy(actor);
                        one(rules.insert(row));
                        ruleNo = row.getRuleNo();
                        long count = rules.selectCount(Wrappers.<MchScheduleRule>lambdaQuery()
                                .eq(MchScheduleRule::getTenantNo, "MAIN").eq(MchScheduleRule::getEntityNo, entity)
                                .eq(MchScheduleRule::getResourceNo, resource.getResourceNo())
                                .eq(MchScheduleRule::getStatus, "ACTIVE"));
                        if (count > 200) { throw BizException.of(ErrorCode.CONFLICT); }
                        validateEntireRule(entity, store, request, ruleNo);
                    }
                    one(resources.advanceScheduleRevision(entity, resource.getResourceNo(), request.expectedRevision(),
                            actor, now()));
                    var change = new MchScheduleChangeLog();
                    change.setTenantNo("MAIN");
                    change.setEntityNo(entity);
                    change.setChangeNo(BizKey.next("MSC"));
                    change.setResourceNo(resource.getResourceNo());
                    change.setSourceRef(ruleNo);
                    change.setBeforeRevision(request.expectedRevision());
                    change.setAfterRevision(request.expectedRevision() + 1);
                    change.setAffectedRefs("[]");
                    change.setReason(request.reason());
                    change.setCreatedBy(actor);
                    change.setUpdatedBy(actor);
                    one(changes.insert(change));
                    publisher.publish(new ScheduleChanged(resource.getResourceNo(), entity, store, ruleNo,
                            request.expectedRevision() + 1, IdempotencyScope.requestHash(key)),
                            new PublishOptions("RESOURCE:" + resource.getResourceNo(), 1));
                    return new ScheduleRuleVO(ruleNo, resource.getResourceNo(), request.expectedRevision() + 1,
                            request.status());
                });
            }));
        } catch (DuplicateKeyException | ConcurrencyFailureException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private MchResource resource(ResourceCommandScopeRow scope, String no, boolean lock, boolean active) {
        var row = resources.selectScopedResource(scope, no);
        if (row == null || !no.equals(row.getResourceNo())) { throw BizException.of(ErrorCode.NOT_FOUND); }
        if ("STAFF".equals(row.getResourceType())) {
            var person = lock ? people.lockScopedPersonnel(scope, row.getPersonnelNo())
                    : people.selectScopedPersonnel(scope, row.getPersonnelNo());
            if (person == null || !row.getPersonnelNo().equals(person.getPersonnelNo())) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            long assigned = assignments.selectCount(Wrappers.<MchPersonnelStore>lambdaQuery()
                    .eq(MchPersonnelStore::getTenantNo, "MAIN").eq(MchPersonnelStore::getEntityNo, scope.entityNo())
                    .eq(MchPersonnelStore::getPersonnelNo, person.getPersonnelNo())
                    .eq(MchPersonnelStore::getStoreNo, scope.storeNo()));
            if (assigned == 0) { throw BizException.of(ErrorCode.NOT_FOUND); }
            if (active && !"ACTIVE".equals(person.getStatus())) { throw BizException.of(ErrorCode.CONFLICT); }
        }
        if (lock) {
            var fresh = resources.lockScopedResource(scope, no);
            if (fresh == null || !no.equals(fresh.getResourceNo())
                    || !Objects.equals(row.getPersonnelNo(), fresh.getPersonnelNo())
                    || !row.getResourceType().equals(fresh.getResourceType())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            row = fresh;
        }
        if (active && !"ACTIVE".equals(row.getStatus())) { throw BizException.of(ErrorCode.CONFLICT); }
        return row;
    }
    private void fence(String entity, MchResource resource) {
        if (resource.getOpenOccupationNo() != null || people.countPendingMaintenance(entity) > 0
                || people.countResourceWork(entity, resource.getResourceNo()) > 0) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
    private void validateEntireRule(String entity, String store, SaveScheduleRuleReq request, String ruleNo) {
        Instant cursor = request.validFrom().atStartOfDay().toInstant(ZoneOffset.UTC).minus(2, ChronoUnit.DAYS);
        Instant until = request.validTo().plusDays(3).atStartOfDay().toInstant(ZoneOffset.UTC);
        boolean represented = false;
        while (cursor.isBefore(until)) {
            Instant next = cursor.plus(31, ChronoUnit.DAYS);
            if (next.isAfter(until)) { next = until; }
            var batch = expanded(entity, store, Set.of(request.resourceNo()), cursor, next);
            represented |= batch.stream().anyMatch(window -> window.ruleNos().contains(ruleNo));
            cursor = next;
        }
        if (!represented) { throw BizException.of(ErrorCode.CONFLICT); }
    }
    private List<ScheduleWindowsPort.Window> expanded(String entity, String store, Set<String> nos,
            Instant from, Instant to) {
        try { return windows.getBatch(new ScheduleWindowsPort.Query("MAIN", entity, store, nos, from, to)); }
        catch (IllegalStateException invalid) { throw BizException.of(ErrorCode.CONFLICT); }
    }
    private ScheduleVO.Rule view(MchScheduleRule row) {
        return new ScheduleVO.Rule(row.getRuleNo(), row.getResourceNo(), row.getTimezone(), row.getWeekdayMask(),
                row.getStartMinute(), row.getEndMinute(), row.getEndDayOffset(), row.getValidFrom(), row.getValidTo(),
                row.getStatus());
    }
    private ResourceCommandScopeRow scope(String entity, String store) {
        var scope = parents.lockScope("MAIN", entity, store);
        if (scope == null || !entity.equals(scope.entityNo()) || !store.equals(scope.storeNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return scope;
    }
    private void identity(String entity, String store, String actor) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Schedule maintenance must start outside a caller transaction");
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
    private void one(int count) { if (count != 1) { throw BizException.of(ErrorCode.CONFLICT); } }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
