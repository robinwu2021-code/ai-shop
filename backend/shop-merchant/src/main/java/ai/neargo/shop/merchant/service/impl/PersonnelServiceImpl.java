package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.merchant.booking.policy.ResourceOccupationGuard;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.config.PlatformWritePrerequisites;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.idempotency.RequestIdempotencyPort;
import ai.neargo.shop.merchant.dto.AssignPersonnelReq;
import ai.neargo.shop.merchant.dto.ChangePersonnelStatusReq;
import ai.neargo.shop.merchant.dto.CreatePersonnelReq;
import ai.neargo.shop.merchant.dto.LinkPersonnelAccountReq;
import ai.neargo.shop.merchant.dto.PersonnelAccountLinkVO;
import ai.neargo.shop.merchant.dto.PersonnelAssignmentVO;
import ai.neargo.shop.merchant.dto.PersonnelChangeVO;
import ai.neargo.shop.merchant.dto.PersonnelQuery;
import ai.neargo.shop.merchant.dto.PersonnelVO;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.entity.MchPersonnel;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.entity.MchPersonnelPosition;
import ai.neargo.shop.merchant.booking.entity.MchPersonnelSkill;
import ai.neargo.shop.merchant.entity.MchPersonnelStore;
import ai.neargo.shop.merchant.booking.entity.MchResource;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.service.MerchantStaffService;
import ai.neargo.shop.merchant.service.PersonnelService;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Shared maintenance takes the same personnel/resource roots as admission; it never performs automatic handover. */
@Service
public class PersonnelServiceImpl implements PersonnelService {
    private final ResourceOccupationGuard occupations;
    private final MerchantMappers.PersonnelMapper people;
    private final MerchantMappers.PersonnelStoreMapper assignments;
    private final MerchantMappers.PersonnelPositionMapper positions;
    private final ai.neargo.shop.merchant.booking.mapper.BookingMappers.PersonnelSkillMapper skills;
    private final ai.neargo.shop.merchant.booking.mapper.BookingMappers.ResourceMapper resources;
    private final ai.neargo.shop.merchant.booking.mapper.BookingMappers.BookingOwnerMapper parents;
    private final MerchantMappers.MchAccountMapper accounts;
    private final StaffAuditLogger audit;
    private final MerchantStaffService staff;
    private final GoodsQueryPort products;
    private final RequestIdempotencyPort idempotency;
    private final DataSource source;
    private final ObjectMapper json;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    public PersonnelServiceImpl(ResourceOccupationGuard occupations, MerchantMappers.PersonnelMapper people,
            MerchantMappers.PersonnelStoreMapper assignments, MerchantMappers.PersonnelPositionMapper positions,
            ai.neargo.shop.merchant.booking.mapper.BookingMappers.PersonnelSkillMapper skills,
            ai.neargo.shop.merchant.booking.mapper.BookingMappers.ResourceMapper resources, ai.neargo.shop.merchant.booking.mapper.BookingMappers.BookingOwnerMapper parents,
            MerchantMappers.MchAccountMapper accounts, StaffAuditLogger audit,
            MerchantStaffService staff, GoodsQueryPort products, RequestIdempotencyPort idempotency,
            @Qualifier(ModuleDataSources.PLATFORM_DATA_SOURCE) DataSource source, ObjectMapper json,
            @Qualifier(ModuleDataSources.PLATFORM_TX_MANAGER) PlatformTransactionManager manager) {
        this.occupations = occupations;
        this.people = people;
        this.assignments = assignments;
        this.positions = positions;
        this.skills = skills;
        this.resources = resources;
        this.parents = parents;
        this.accounts = accounts;
        this.audit = audit;
        this.staff = staff;
        this.products = products;
        this.idempotency = idempotency;
        this.source = source;
        this.json = json;
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads = new TransactionTemplate(manager);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
    }

    @Override
    public PageData<PersonnelVO> listPersonnel(String entity, String store, String actor, PersonnelQuery query) {
        identity(entity, store, actor);
        if (query == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        if (query.storeNo() != null && !store.equals(query.storeNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
        return DataScopeContext.executeWithoutScope(() -> reads.execute(status -> {
            scope(entity, store);
            var filter = Wrappers.<MchPersonnel>lambdaQuery().eq(MchPersonnel::getTenantNo, "MAIN")
                    .eq(MchPersonnel::getEntityNo, entity).eq(query.status() != null,
                            MchPersonnel::getStatus, query.status())
                    .like(query.keyword() != null, MchPersonnel::getName, query.keyword())
                    .exists("SELECT 1 FROM mch_personnel_store a WHERE a.tenant_no=mch_personnel.tenant_no "
                            + "AND a.entity_no=mch_personnel.entity_no AND a.personnel_no=mch_personnel.personnel_no "
                            + "AND a.deleted=0 AND a.store_no={0}", store)
                    .exists(query.skill() != null,
                            "SELECT 1 FROM mch_personnel_skill k WHERE k.tenant_no=mch_personnel.tenant_no "
                            + "AND k.entity_no=mch_personnel.entity_no AND k.personnel_no=mch_personnel.personnel_no "
                            + "AND k.deleted=0 AND k.store_no={0} AND k.goods_no={1} AND k.status='VERIFIED' "
                            + "AND k.valid_from<={2} AND (k.valid_to IS NULL OR k.valid_to>{2})",
                            store, query.skill(), now())
                    .orderByDesc(MchPersonnel::getId);
            var page = people.selectPage(new Page<>(query.page(), query.size()), filter);
            return PageData.of(page.getRecords().stream().map(person -> view(person, store)).toList(),
                    page.getTotal(), query.page(), query.size());
        }));
    }

    @Override
    public PersonnelVO createPersonnel(String entity, String store, String actor, CreatePersonnelReq request,
            String key) {
        identity(entity, store, actor);
        if (request == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        RuntimeException productFailure = productFailure(entity, store, request.assignment());
        return command(entity, store, actor, "CREATE", request, key, PersonnelVO.class, trusted -> {
            if (productFailure != null) { throw productFailure; }
            var person = new MchPersonnel();
            person.setTenantNo("MAIN");
            person.setEntityNo(entity);
            person.setPersonnelNo(BizKey.next("MPN"));
            person.setName(request.displayName());
            person.setAvatarRef(request.avatarRef());
            person.setStatus("ACTIVE");
            person.setVersion(1L);
            people.insert(person);
            var resource = new MchResource();
            resource.setTenantNo("MAIN");
            resource.setEntityNo(entity);
            resource.setResourceNo(BizKey.next("MRS"));
            resource.setResourceType("STAFF");
            resource.setPersonnelNo(person.getPersonnelNo());
            resource.setName(person.getName());
            resource.setCapacity(1);
            resource.setStatus("ACTIVE");
            resources.insert(resource);
            employment(person, store, actor, request.assignment());
            evidence(person, store, actor, "PERSONNEL_CREATED", key);
            return view(person, store);
        });
    }

    @Override
    public PersonnelAssignmentVO assignPersonnel(String entity, String store, String actor, String personnelNo,
            AssignPersonnelReq request, String key) {
        identity(entity, store, actor);
        reference(personnelNo);
        if (request == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        RuntimeException productFailure = productFailure(entity, store, request);
        return command(entity, store, actor, "ASSIGN:" + personnelNo, request, key,
                PersonnelAssignmentVO.class, trusted -> {
                    var person = lock(trusted, personnelNo, request.expectedRevision());
                    requireActive(person);
                    var resource = fence(trusted, person);
                    if (resources.countStoreScheduleRules(entity, store, resource.getResourceNo()) > 0) {
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                    if (productFailure != null) { throw productFailure; }
                    String assignment = employment(person, store, actor, request);
                    advance(person, actor, person.getStatus());
                    evidence(person, store, actor, "PERSONNEL_ASSIGNED", key);
                    return new PersonnelAssignmentVO(personnelNo, person.getVersion(), assignment);
                });
    }

    @Override
    public PersonnelAccountLinkVO linkPersonnelAccount(String entity, String store, String actor, String personnelNo,
            LinkPersonnelAccountReq request, String key) {
        identity(entity, store, actor);
        reference(personnelNo);
        if (request == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        return command(entity, store, actor, "ACCOUNT:" + personnelNo, request, key,
                PersonnelAccountLinkVO.class, trusted -> {
                    var person = lock(trusted, personnelNo, request.expectedRevision());
                    requireActive(person);
                    fence(trusted, person);
                    if (request.accountNo() != null) {
                        var account = lockAccount(entity, request.accountNo());
                        if (account == null || !request.accountNo().equals(account.getMchAccountNo())
                                || !"ACTIVE".equals(account.getStatus()) || Boolean.TRUE.equals(account.getIsOwner())) {
                            throw BizException.of(ErrorCode.BAD_REQUEST);
                        }
                    }
                    String previousAccount = person.getMchAccountNo();
                    if (request.accountNo() != null && people.countAccountLinkedElsewhere(entity, request.accountNo(),
                            personnelNo) > 0) {
                        // 一个账号只关联一个人员（原先靠 mch_personnel_account 的唯一键挡）
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                    java.time.LocalDateTime linkedAt = request.accountNo() == null ? null : now();
                    one(people.linkAccount(entity, personnelNo, person.getVersion(), request.accountNo(), linkedAt,
                            actor, now()));
                    person.setVersion(person.getVersion() + 1);
                    person.setMchAccountNo(request.accountNo());
                    person.setAccountLinkedAt(linkedAt);
                    var change = new LinkedHashMap<String, Object>();
                    change.put("previousAccountNo", previousAccount);
                    change.put("accountNo", request.accountNo());
                    evidence(person, store, actor, "PERSONNEL_ACCOUNT_LINKED", key, change);
                    return new PersonnelAccountLinkVO(personnelNo, person.getVersion(), request.accountNo());
                });
    }

    @Override
    public PersonnelChangeVO changePersonnelStatus(String entity, String store, String actor, String personnelNo,
            ChangePersonnelStatusReq request, String key) {
        identity(entity, store, actor);
        reference(personnelNo);
        if (request == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        return command(entity, store, actor, "STATUS:" + personnelNo, request, key,
                PersonnelChangeVO.class, trusted -> {
            var person = lock(trusted, personnelNo, request.expectedRevision());
            if ("LEFT".equals(person.getStatus()) || person.getStatus().equals(request.status())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            var resource = fence(trusted, person);
            // 账号关联在人员行上（Q4）
            boolean disable = !"ACTIVE".equals(request.status()) && person.getMchAccountNo() != null;
            if (disable) {
                var account = lockAccount(entity, person.getMchAccountNo());
                if (Boolean.TRUE.equals(account.getIsOwner())) { throw BizException.of(ErrorCode.BAD_REQUEST); }
                staff.setStatus(entity, account.getMchAccountNo(), false);
                if (!"DISABLED".equals(lockAccount(entity, account.getMchAccountNo()).getStatus())) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
            }
            resource.setStatus("ACTIVE".equals(request.status()) ? "ACTIVE" : "SUSPENDED");
            one(resources.updateById(resource));
            advance(person, actor, request.status());
            String reasonRef = statusReason(person, store, actor, request.reason());
            evidence(person, store, actor, "PERSONNEL_STATUS_CHANGED", key, Map.of("reasonRef", reasonRef));
            return new PersonnelChangeVO(personnelNo, person.getVersion(), person.getStatus(), disable);
        });
    }

    private String employment(MchPersonnel person, String store, String actor, AssignPersonnelReq request) {
        if (!store.equals(request.storeNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
        MchPersonnelStore old = null;
        if (request.assignmentNo() != null) {
            old = assignments.selectOne(Wrappers.<MchPersonnelStore>lambdaQuery()
                    .eq(MchPersonnelStore::getTenantNo, "MAIN").eq(MchPersonnelStore::getEntityNo, person.getEntityNo())
                    .eq(MchPersonnelStore::getPersonnelNo, person.getPersonnelNo())
                    .eq(MchPersonnelStore::getStoreNo, store)
                    .eq(MchPersonnelStore::getAssignmentNo, request.assignmentNo())
                    .eq(MchPersonnelStore::getStatus, "ACTIVE"));
            if (old == null || !request.assignmentNo().equals(old.getAssignmentNo())) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
        }
        var overlap = Wrappers.<MchPersonnelStore>lambdaQuery().eq(MchPersonnelStore::getTenantNo, "MAIN")
                .eq(MchPersonnelStore::getEntityNo, person.getEntityNo())
                .eq(MchPersonnelStore::getPersonnelNo, person.getPersonnelNo()).eq(MchPersonnelStore::getStoreNo, store)
                .eq(MchPersonnelStore::getStatus, "ACTIVE")
                .ne(old != null, MchPersonnelStore::getAssignmentNo, request.assignmentNo())
                .lt(request.validTo() != null, MchPersonnelStore::getValidFrom, local(request.validTo()))
                .and(q -> q.isNull(MchPersonnelStore::getValidTo)
                        .or().gt(MchPersonnelStore::getValidTo, local(request.validFrom())));
        if (assignments.selectCount(overlap) > 0) { throw BizException.of(ErrorCode.CONFLICT); }
        if (old != null) {
            old.setStatus("INACTIVE");
            one(assignments.updateById(old));
            var revoke = Wrappers.<MchPersonnelSkill>lambdaUpdate().eq(MchPersonnelSkill::getTenantNo, "MAIN")
                    .eq(MchPersonnelSkill::getEntityNo, person.getEntityNo())
                    .eq(MchPersonnelSkill::getPersonnelNo, person.getPersonnelNo())
                    .eq(MchPersonnelSkill::getStoreNo, store)
                    .eq(MchPersonnelSkill::getStatus, "VERIFIED")
                    .eq(MchPersonnelSkill::getValidFrom, old.getValidFrom())
                    .eq(old.getValidTo() != null, MchPersonnelSkill::getValidTo, old.getValidTo())
                    .isNull(old.getValidTo() == null, MchPersonnelSkill::getValidTo)
                    .set(MchPersonnelSkill::getStatus, "REVOKED").set(MchPersonnelSkill::getUpdatedAt, now())
                    .set(MchPersonnelSkill::getUpdatedBy, actor).setSql("version=version+1");
            skills.update(revoke);
        }
        var assignment = new MchPersonnelStore();
        assignment.setTenantNo("MAIN");
        assignment.setEntityNo(person.getEntityNo());
        assignment.setPersonnelNo(person.getPersonnelNo());
        assignment.setAssignmentNo(BizKey.next("MPA"));
        assignment.setStoreNo(store);
        assignment.setValidFrom(local(request.validFrom()));
        assignment.setValidTo(local(request.validTo()));
        assignment.setPublicVisible(request.publicVisible());
        assignment.setLevelCode(request.levelCode());
        assignment.setSortOrder(request.sortOrder());
        assignment.setStatus("ACTIVE");
        assignments.insert(assignment);
        for (String code : request.positions()) {
            var position = new MchPersonnelPosition();
            position.setTenantNo("MAIN");
            position.setEntityNo(person.getEntityNo());
            position.setPositionNo(BizKey.next("MPP"));
            position.setAssignmentNo(assignment.getAssignmentNo());
            position.setPositionCode(code);
            position.setStatus("ACTIVE");
            positions.insert(position);
        }
        for (var input : request.skills()) {
            var skill = new MchPersonnelSkill();
            skill.setTenantNo("MAIN");
            skill.setEntityNo(person.getEntityNo());
            skill.setSkillNo(BizKey.next("MPS"));
            skill.setPersonnelNo(person.getPersonnelNo());
            skill.setStoreNo(store);
            skill.setGoodsNo(input.goodsNo());
            skill.setSkuNo(input.skuNo());
            skill.setLevelCode(input.levelCode());
            skill.setValidFrom(local(request.validFrom()));
            skill.setValidTo(local(request.validTo()));
            skill.setStatus("VERIFIED");
            skill.setVerifiedBy(actor);
            skills.insert(skill);
        }
        requireDirectoryBounds(person, store);
        return assignment.getAssignmentNo();
    }

    private RuntimeException productFailure(String entity, String store, AssignPersonnelReq request) {
        try {
            if (!store.equals(request.storeNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            var skus = request.skills().stream().map(AssignPersonnelReq.Skill::skuNo).toList();
            if (skus.isEmpty()) { return null; }
            var facts = products.snapshot(skus, Map.of(entity, store));
            for (var input : request.skills()) {
                var fact = facts.get(input.skuNo());
                if (fact == null || !input.skuNo().equals(fact.skuNo()) || !input.goodsNo().equals(fact.goodsNo())
                        || !entity.equals(fact.merchantNo()) || !store.equals(fact.storeNo())
                        || !"SERVICE".equals(fact.categoryType())) { throw BizException.of(ErrorCode.BAD_REQUEST); }
            }
            return null;
        } catch (RuntimeException failure) { return failure; }
    }

    private <T> T command(String entity, String store, String actor, String operation, Object request,
            String key, Class<T> type, Function<ResourceCommandScopeRow, T> action) {
        String replayKey = IdempotencyScope.requireKey(key);
        String endpoint = IdempotencyScope.endpoint("PERSONNEL", operation, entity, store);
        String hash = IdempotencyScope.requestHash(json.writeValueAsString(request));
        try {
            return DataScopeContext.executeWithoutScope(() -> writes.execute(status -> {
                var trusted = scope(entity, store);
                new PlatformWritePrerequisites().require(source);
                return idempotency.execute(replayKey, endpoint, actor, hash, type, () -> action.apply(trusted));
            }));
        } catch (DuplicateKeyException | ConcurrencyFailureException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
    private ResourceCommandScopeRow scope(String entity, String store) {
        var scope = parents.lockScope("MAIN", entity, store);
        if (scope == null || !entity.equals(scope.entityNo()) || !store.equals(scope.storeNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return scope;
    }
    private MchPersonnel lock(ResourceCommandScopeRow scope, String personnelNo, long expected) {
        var person = people.lockScopedPersonnel(scope, personnelNo);
        if (person == null || !personnelNo.equals(person.getPersonnelNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (person.getVersion() != expected) { throw BizException.of(ErrorCode.CONFLICT); }
        return person;
    }
    private MchResource fence(ResourceCommandScopeRow scope, MchPersonnel person) {
        var resource = resources.selectOne(Wrappers.<MchResource>lambdaQuery()
                .eq(MchResource::getTenantNo, "MAIN").eq(MchResource::getEntityNo, person.getEntityNo())
                .eq(MchResource::getPersonnelNo, person.getPersonnelNo()).last("FOR UPDATE"));
        if (resource != null) { occupations.requireNoWork(scope, resource.getResourceNo()); }
        if (resource == null || !"STAFF".equals(resource.getResourceType()) || resource.getCapacity() != 1
                || resource.getStoreNo() != null || resource.getOpenOccupationNo() != null
                || people.countPendingMaintenance(scope.entityNo()) > 0
                || people.countResourceWork(scope.entityNo(), resource.getResourceNo()) > 0) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return resource;
    }
    private void advance(MchPersonnel person, String actor, String status) {
        one(people.advanceMaintenance(person.getEntityNo(), person.getPersonnelNo(), person.getVersion(), status,
                actor, now()));
        person.setVersion(person.getVersion() + 1);
        person.setStatus(status);
    }
    private String statusReason(MchPersonnel person, String store, String actor, String reason) {
        return audit.evidence(person.getEntityNo(), store, actor, "PERSONNEL_STATUS_REASON", reason);
    }
    private void evidence(MchPersonnel person, String store, String actor, String action, String key) {
        evidence(person, store, actor, action, key, Map.of());
    }
    private void evidence(MchPersonnel person, String store, String actor, String action, String key,
            Map<String, Object> extra) {
        String ref = IdempotencyScope.requestHash(key);
        var detail = new LinkedHashMap<String, Object>();
        detail.put("personnelNo", person.getPersonnelNo());
        detail.put("status", person.getStatus());
        detail.put("revision", person.getVersion());
        detail.put("requestRef", ref);
        detail.putAll(extra);
        audit.evidence(person.getEntityNo(), store, actor, action, json.writeValueAsString(detail));
    }
    private PersonnelVO view(MchPersonnel person, String store) {
        var rows = assignments.selectList(Wrappers.<MchPersonnelStore>lambdaQuery()
                .eq(MchPersonnelStore::getTenantNo, "MAIN").eq(MchPersonnelStore::getEntityNo, person.getEntityNo())
                .eq(MchPersonnelStore::getPersonnelNo, person.getPersonnelNo()).eq(MchPersonnelStore::getStoreNo, store)
                .eq(MchPersonnelStore::getStatus, "ACTIVE")
                .and(q -> q.isNull(MchPersonnelStore::getValidTo).or().gt(MchPersonnelStore::getValidTo, now()))
                .orderByAsc(MchPersonnelStore::getValidFrom).last("LIMIT 201"));
        var qualifications = skills.selectList(Wrappers.<MchPersonnelSkill>lambdaQuery()
                .eq(MchPersonnelSkill::getTenantNo, "MAIN").eq(MchPersonnelSkill::getEntityNo, person.getEntityNo())
                .eq(MchPersonnelSkill::getPersonnelNo, person.getPersonnelNo())
                    .eq(MchPersonnelSkill::getStoreNo, store)
                .eq(MchPersonnelSkill::getStatus, "VERIFIED")
                .and(q -> q.isNull(MchPersonnelSkill::getValidTo).or().gt(MchPersonnelSkill::getValidTo, now()))
                .orderByAsc(MchPersonnelSkill::getSkillNo).last("LIMIT 201"));
        if (rows.size() > 200 || qualifications.size() > 200) { throw BizException.of(ErrorCode.CONFLICT); }
        var resource = resources.selectOne(Wrappers.<MchResource>lambdaQuery()
                .eq(MchResource::getTenantNo, "MAIN").eq(MchResource::getEntityNo, person.getEntityNo())
                .eq(MchResource::getPersonnelNo, person.getPersonnelNo()));
        if (resource == null) { throw BizException.of(ErrorCode.CONFLICT); }
        return new PersonnelVO(person.getPersonnelNo(), person.getName(), person.getAvatarRef(), person.getStatus(),
                person.getVersion(), resource.getResourceNo(), rows.stream().map(assignment -> {
                    var roles = positions.selectList(Wrappers.<MchPersonnelPosition>lambdaQuery()
                            .eq(MchPersonnelPosition::getTenantNo, "MAIN")
                            .eq(MchPersonnelPosition::getEntityNo, person.getEntityNo())
                            .eq(MchPersonnelPosition::getAssignmentNo, assignment.getAssignmentNo())
                            .eq(MchPersonnelPosition::getStatus, "ACTIVE")
                            .orderByAsc(MchPersonnelPosition::getPositionCode));
                    return new PersonnelVO.Assignment(assignment.getAssignmentNo(), store,
                            offset(assignment.getValidFrom()), offset(assignment.getValidTo()),
                            assignment.getPublicVisible(), assignment.getLevelCode(), assignment.getSortOrder(),
                            assignment.getStatus(), roles.stream().map(MchPersonnelPosition::getPositionCode).toList());
                }).toList(), qualifications.stream().map(skill -> new PersonnelVO.Qualification(skill.getSkillNo(),
                        skill.getGoodsNo(), skill.getSkuNo(), skill.getLevelCode(), offset(skill.getValidFrom()),
                        offset(skill.getValidTo()), skill.getStatus())).toList());
    }
    private MchAccount lockAccount(String entity, String accountNo) {
        var account = accounts.lockPersonnelAccount(entity, accountNo);
        if (account == null || !accountNo.equals(account.getMchAccountNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return account;
    }
    /** A successful write must never leave more live children than the directory can represent. */
    private void requireDirectoryBounds(MchPersonnel person, String store) {
        long assignmentCount = assignments.selectCount(Wrappers.<MchPersonnelStore>lambdaQuery()
                .eq(MchPersonnelStore::getTenantNo, "MAIN").eq(MchPersonnelStore::getEntityNo, person.getEntityNo())
                .eq(MchPersonnelStore::getPersonnelNo, person.getPersonnelNo()).eq(MchPersonnelStore::getStoreNo, store)
                .eq(MchPersonnelStore::getStatus, "ACTIVE")
                .and(q -> q.isNull(MchPersonnelStore::getValidTo).or().gt(MchPersonnelStore::getValidTo, now())));
        long skillCount = skills.selectCount(Wrappers.<MchPersonnelSkill>lambdaQuery()
                .eq(MchPersonnelSkill::getTenantNo, "MAIN").eq(MchPersonnelSkill::getEntityNo, person.getEntityNo())
                .eq(MchPersonnelSkill::getPersonnelNo, person.getPersonnelNo())
                    .eq(MchPersonnelSkill::getStoreNo, store)
                .eq(MchPersonnelSkill::getStatus, "VERIFIED")
                .and(q -> q.isNull(MchPersonnelSkill::getValidTo).or().gt(MchPersonnelSkill::getValidTo, now())));
        if (assignmentCount > 200 || skillCount > 200) { throw BizException.of(ErrorCode.CONFLICT); }
    }
    private void identity(String entity, String store, String actor) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Personnel owner queries must start outside a caller transaction");
        }
        reference(entity);
        reference(store);
        if (actor == null || actor.isBlank() || actor.length() > 64) { throw BizException.of(ErrorCode.BAD_REQUEST); }
    }
    private void reference(String value) {
        if (value == null || value.isBlank() || value.length() > 32 || !value.equals(value.trim())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    private void requireActive(MchPersonnel person) {
        if (!"ACTIVE".equals(person.getStatus())) { throw BizException.of(ErrorCode.CONFLICT); }
    }
    private void one(int rows) { if (rows != 1) { throw BizException.of(ErrorCode.CONFLICT); } }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private LocalDateTime local(OffsetDateTime at) { return at == null ? null : at.toLocalDateTime(); }
    private OffsetDateTime offset(LocalDateTime at) { return at == null ? null : at.atOffset(ZoneOffset.UTC); }
}
