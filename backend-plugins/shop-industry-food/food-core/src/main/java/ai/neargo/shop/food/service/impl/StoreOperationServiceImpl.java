package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.config.ModuleDataSources;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.CreatePolicyReq;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.SaveCalendarReq;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbServiceCalendar;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceCalendarMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.ServiceCalendarPolicy;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.spi.idempotency.RequestIdempotencyPort;
import ai.neargo.shop.spi.food.ServiceCalendarSaved;
import ai.neargo.shop.spi.food.StorePolicyPublished;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Serializes immutable policy publication, including the first publication's empty InnoDB key range. */
@Service
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class StoreOperationServiceImpl implements StoreOperationService {
    private static final String POLICY_PREFIX = "FPOL";
    private static final String ACTION_PREFIX = "FACT";
    private final StorePolicyMapper policies;
    private final ServiceCalendarMapper calendars;
    private final ActionLogMapper actions;
    private final RequestIdempotencyPort idempotency;
    private final OutboxPublisher publisher;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final Clock clock;

    /** Reuses the platform primary transaction and its existing idempotency/outbox implementations. */
    @Autowired
    public StoreOperationServiceImpl(StorePolicyMapper policies, ActionLogMapper actions,
            ServiceCalendarMapper calendars,
            RequestIdempotencyPort idempotency, @Qualifier("foodOutboxPublisher") OutboxPublisher publisher,
            ObjectMapper json, @Qualifier(ModuleDataSources.INDUSTRY_TX_MANAGER) PlatformTransactionManager manager) {
        this(policies, actions, calendars, idempotency, publisher, json, manager, Clock.systemUTC());
    }

    /** Explicit clock keeps reads deterministic; publication effective time always comes from the typed request. */
    public StoreOperationServiceImpl(StorePolicyMapper policies, ActionLogMapper actions,
            ServiceCalendarMapper calendars,
            RequestIdempotencyPort idempotency, OutboxPublisher publisher, ObjectMapper json,
            PlatformTransactionManager manager, Clock clock) {
        this.policies = policies;
        this.calendars = calendars;
        this.actions = actions;
        this.idempotency = idempotency;
        this.publisher = publisher;
        this.json = json;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    /** Returns the first receipt on replay, without external RPC, write retries, or fallback. */
    @Override
    public OperationVO createPolicy(String entityNo, String storeNo, String actorNo,
            CreatePolicyReq req, String key) {
        if (blank(entityNo) || entityNo.length() > 32 || blank(storeNo) || storeNo.length() > 32
                || blank(actorNo) || actorNo.length() > 32) throw BizException.of(ErrorCode.FORBIDDEN);
        if (blank(key) || key.length() > 128) throw BizException.of(ErrorCode.BAD_REQUEST);
        validate(req);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Policy publication cannot join an existing transaction");
        }
        String scope = FoodCommandFingerprint.scope("CREATE_POLICY", entityNo, storeNo);
        String hash = FoodCommandFingerprint.policy(req);
        try {
            return transaction.execute(status -> idempotency.execute(key, scope, actorNo, hash, OperationVO.class,
                    () -> publish(entityNo, storeNo, actorNo, req, FoodCommandFingerprint.hash(scope, key))));
        } catch (ConcurrencyFailureException | DuplicateKeyException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public OperationVO saveCalendar(String entityNo, String storeNo, LocalDate date, String actorNo,
            SaveCalendarReq req, String key) {
        if (blank(entityNo) || entityNo.length() > 32 || blank(storeNo) || storeNo.length() > 32
                || blank(actorNo) || actorNo.length() > 32) throw BizException.of(ErrorCode.FORBIDDEN);
        if (blank(key) || key.length() > 128 || date == null || date.getYear() < 1000 || date.getYear() > 9999
                || req == null || req.version() == null || req.version() < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var intervals = ServiceCalendarPolicy.normalize(req.mode(), req.intervals());
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Calendar save cannot join an existing transaction");
        }
        String scope = FoodCommandFingerprint.scope("SAVE_CALENDAR", entityNo, storeNo);
        String hash = FoodCommandFingerprint.calendar(date, req, intervals);
        try {
            return transaction.execute(status -> idempotency.execute(key, scope, actorNo, hash, OperationVO.class,
                    () -> saveDay(entityNo, storeNo, date, actorNo, req, intervals,
                            FoodCommandFingerprint.hash(scope, key))));
        } catch (ConcurrencyFailureException | DuplicateKeyException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private OperationVO saveDay(String entityNo, String storeNo, LocalDate date, String actorNo,
            SaveCalendarReq req, List<String> intervals, String requestNo) {
        var policy = policies.lockCurrent(entityNo, storeNo);
        if (policy == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var rows = calendars.selectList(Wrappers.<FnbServiceCalendar>lambdaQuery()
                .eq(FnbServiceCalendar::getEntityNo, entityNo).eq(FnbServiceCalendar::getStoreNo, storeNo)
                .eq(FnbServiceCalendar::getLocalDate, date).last("LIMIT 1 FOR UPDATE"));
        var row = rows.isEmpty() ? new FnbServiceCalendar() : rows.getFirst();
        long oldVersion = rows.isEmpty() ? 0 : row.getVersion();
        if (req.version() != oldVersion || oldVersion == Long.MAX_VALUE) throw BizException.of(ErrorCode.CONFLICT);
        String before = rows.isEmpty() ? null : FoodCommandFingerprint.hash(row.getMode(), row.getIntervals(),
                row.getPolicyNo(), row.getVersion().toString());
        row.setMode(req.mode());
        row.setIntervals(json.writeValueAsString(intervals));
        row.setPolicyNo(policy.getPolicyNo());
        if (rows.isEmpty()) {
            row.setEntityNo(entityNo);
            row.setStoreNo(storeNo);
            row.setLocalDate(date);
            row.setCalendarNo(BizKey.next("FCAL"));
            row.setVersion(1L);
            calendars.insert(row);
        } else if (calendars.updateById(row) != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var audit = new FnbActionLog();
        audit.setEntityNo(entityNo);
        audit.setStoreNo(storeNo);
        audit.setActionNo(BizKey.next(ACTION_PREFIX));
        audit.setObjectType("SERVICE_CALENDAR");
        audit.setObjectNo(row.getCalendarNo());
        audit.setAction("SAVE_CALENDAR");
        audit.setOperatorNo(actorNo);
        audit.setRequestNo(requestNo);
        audit.setBeforeRef(before);
        audit.setAfterRef(FoodCommandFingerprint.hash(row.getMode(), row.getIntervals(), row.getPolicyNo(),
                row.getVersion().toString()));
        actions.insert(audit);
        publisher.publish(new ServiceCalendarSaved(entityNo, storeNo, row.getCalendarNo(), date, row.getMode(),
                        row.getVersion(), audit.getActionNo()),
                new PublishOptions("FOOD_CALENDAR:"
                        + FoodCommandFingerprint.hash(entityNo, storeNo, date.toString()), 1));
        return new OperationVO(audit.getActionNo(), "SUCCEEDED", row.getVersion(), row.getCalendarNo());
    }

    private OperationVO publish(String entityNo, String storeNo, String actorNo, CreatePolicyReq req,
            String requestNo) {
        // 每店每行业一行，改策略即原地更新、立即生效（TDD §2.5，不保留历史）；版本号做乐观锁。
        var head = policies.lockCurrent(entityNo, storeNo);
        long version = head == null ? 0 : head.getVersion();
        if (version == Long.MAX_VALUE || req.version() != version) throw BizException.of(ErrorCode.CONFLICT);
        String before = head == null ? null : head.getPolicyNo() + ":" + version;
        var policy = head == null ? new FnbStorePolicy() : head;
        if (head == null) {
            // 新库唯一键含可空的 tenant_no，必须显式写 MAIN（TDD §2.5）。
            policy.setTenantNo("MAIN");
            policy.setEntityNo(entityNo);
            policy.setStoreNo(storeNo);
            policy.setPolicyNo(BizKey.next(POLICY_PREFIX));
            policy.setIndustryCode(FnbStorePolicy.INDUSTRY);
        }
        policy.setTimezone(req.timezone());
        policy.setCutoverMinute(req.cutoverMinute());
        policy.setPayPolicy(req.payPolicy());
        policy.setCapabilities(json.writeValueAsString(req.capabilities()));
        policy.setStatus(FnbStorePolicy.ACTIVE);
        if (head == null) {
            policy.setVersion(1L);
            policies.insert(policy);
        } else if (policies.update(
                        null,
                        Wrappers.<FnbStorePolicy>lambdaUpdate()
                                .eq(FnbStorePolicy::getId, head.getId())
                                .eq(FnbStorePolicy::getVersion, version)
                                .set(FnbStorePolicy::getTimezone, req.timezone())
                                .set(FnbStorePolicy::getCutoverMinute, req.cutoverMinute())
                                .set(FnbStorePolicy::getPayPolicy, req.payPolicy())
                                .set(FnbStorePolicy::getCapabilities, policy.getCapabilities())
                                .set(FnbStorePolicy::getStatus, FnbStorePolicy.ACTIVE)
                                .set(FnbStorePolicy::getUpdatedBy, actorNo)
                                .set(FnbStorePolicy::getVersion, version + 1))
                != 1) throw BizException.of(ErrorCode.CONFLICT);
        var audit = new FnbActionLog();
        audit.setEntityNo(entityNo);
        audit.setStoreNo(storeNo);
        audit.setActionNo(BizKey.next(ACTION_PREFIX));
        audit.setObjectType("STORE_POLICY");
        audit.setObjectNo(policy.getPolicyNo());
        audit.setAction("PUBLISH");
        audit.setOperatorNo(actorNo);
        audit.setRequestNo(requestNo);
        audit.setBeforeRef(before);
        audit.setAfterRef(policy.getPolicyNo() + ":" + (version + 1));
        actions.insert(audit);
        publisher.publish(new StorePolicyPublished(entityNo, storeNo, policy.getPolicyNo(), version + 1,
                        req.effectiveFrom(), audit.getActionNo()),
                new PublishOptions("FOOD_POLICY:" + FoodCommandFingerprint.hash(entityNo, storeNo), 1));
        return new OperationVO(audit.getActionNo(), "SUCCEEDED", version + 1, policy.getPolicyNo());
    }

    private void validate(CreatePolicyReq req) {
        if (req == null || blank(req.timezone()) || req.timezone().length() > 64
                || req.cutoverMinute() == null || req.cutoverMinute() < 0 || req.cutoverMinute() > 1439
                || req.effectiveFrom() == null || req.effectiveFrom().getNano() % 1_000_000 != 0
                || !("PREPAY".equals(req.payPolicy()) || "POSTPAY".equals(req.payPolicy()))
                || req.version() == null || req.version() < 0 || req.capabilities() == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        try {
            if (!ZoneId.getAvailableZoneIds().contains(req.timezone())) {
                throw new DateTimeException("Invalid IANA zone");
            }
            int year = LocalDateTime.ofInstant(req.effectiveFrom(), ZoneOffset.UTC).getYear();
            if (year < 1000 || year > 9999) throw new DateTimeException("Outside DATETIME range");
        } catch (DateTimeException invalid) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (!req.capabilities().isEmpty() || !blank(req.approvalRef())) throw BizException.of(ErrorCode.FORBIDDEN);
        // 不支持未来生效（TDD §2.5，用户 2026-11-27 定）：策略改了立即生效，未来时间拒绝。
        if (req.effectiveFrom().isAfter(clock.instant())) throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    @Override
    @Transactional(transactionManager = ModuleDataSources.INDUSTRY_TX_MANAGER, readOnly = true)
    public StoreOperationPolicyResultVO getPolicy(String entityNo, String storeNo, GetPolicyQuery query) {
        if (entityNo == null || entityNo.isBlank() || storeNo == null || storeNo.isBlank()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        // 不保留历史：任何时刻都按门店当前策略（TDD §2.5）。
        FnbStorePolicy row = policies.current(entityNo, storeNo);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        try {
            ZoneId.of(row.getTimezone());
            if (row.getCutoverMinute() == null || row.getCutoverMinute() < 0 || row.getCutoverMinute() > 1439
                    || !Set.of("PREPAY", "POSTPAY").contains(row.getPayPolicy())
                    || row.getVersion() == null || row.getVersion() < 0) {
                throw new IllegalArgumentException("Invalid policy snapshot");
            }
            var capabilities = json.readTree(row.getCapabilities());
            if (capabilities == null || !capabilities.isArray()) {
                throw new IllegalArgumentException("Invalid capabilities");
            }
            var names = new ArrayList<String>();
            for (var item : capabilities) {
                if (!item.isTextual() || item.asString().isBlank()) {
                    throw new IllegalArgumentException("Invalid capability");
                }
                names.add(item.asString());
            }
            return new StoreOperationPolicyResultVO(row.getPolicyNo(), row.getTimezone(), row.getCutoverMinute(),
                    row.getPayPolicy(), names, row.getVersion());
        } catch (RuntimeException invalidSnapshot) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
}
