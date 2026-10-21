package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.SalesRestrictionReq;
import ai.neargo.shop.food.dto.SalesRestrictionQuery;
import ai.neargo.shop.food.dto.SalesRestrictionVO;
import ai.neargo.shop.food.dto.SalesRestrictionEvaluationVO;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbSalesRestriction;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.mapper.FoodMappers.SalesRestrictionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import ai.neargo.shop.food.policy.SalesRestrictionPolicy;
import ai.neargo.shop.food.service.SalesRestrictionService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.SalesRestrictionChanged;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.Comparator;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Serializes local restrictions on the store policy row without writing physical inventory or quota claims. */
public class SalesRestrictionServiceImpl implements SalesRestrictionService {
    private final SalesRestrictionMapper rows;
    private final StorePolicyMapper policies;
    private final ActionLogMapper actions;
    private final IdempotencyService idem;
    private final OutboxPublisher publisher;
    private final ObjectMapper json;
    private final Clock clock;
    private final GoodsCatalogQueryPort goods;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public SalesRestrictionServiceImpl(SalesRestrictionMapper rows, StorePolicyMapper policies, ActionLogMapper actions,
            IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager,
            Clock clock, GoodsCatalogQueryPort goods) {
        this.rows = rows; this.policies = policies; this.actions = actions; this.idem = idem;
        this.publisher = publisher; this.json = json; this.clock = clock; this.goods = goods;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        read = new TransactionTemplate(manager); read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override public SalesRestrictionVO manage(String entity, String store, String actor,
            SalesRestrictionReq req, String key) {
        context(entity, store); MenuDefinitionPolicy.key(actor); SalesRestrictionPolicy.command(req);
        if (key == null || key.isBlank() || key.length() > 128) throw BizException.of(ErrorCode.BAD_REQUEST);
        String scope = FoodCommandFingerprint.scope("SALES_RESTRICTION", entity, store);
        String hash = FoodCommandFingerprint.hash("sales-restriction:v1", json.writeValueAsString(req));
        var replay = DataScopeContext.executeWithoutScope(() -> read.execute(tx ->
                idem.findCompleted(key, scope, actor, hash, SalesRestrictionVO.class)));
        if (replay.isPresent()) return replay.get();
        // Original product identity is looked up outside the local transaction; never a stock read.
        if (!"CLEAR".equals(req.mode())) {
            var fact = goods.getSku(entity, store, req.skuNo());
            if (fact == null || !entity.equals(fact.entityNo()) || !store.equals(fact.storeNo())
                    || !req.skuNo().equals(fact.skuNo()) || fact.version() < 0) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
        }
        try {
            return DataScopeContext.executeWithoutScope(() -> write.execute(tx ->
                    idem.execute(key, scope, actor, hash, SalesRestrictionVO.class, () -> {
                        var policy = lockStore(entity, store);
                        var now = now();
                        if ("CLEAR".equals(req.mode())) {
                            var row = rows.selectOne(Wrappers.<FnbSalesRestriction>lambdaQuery()
                                    .eq(FnbSalesRestriction::getEntityNo, entity)
                                    .eq(FnbSalesRestriction::getStoreNo, store)
                                    .eq(FnbSalesRestriction::getRestrictionNo, req.restrictionNo()).last("FOR UPDATE"));
                            if (row == null || !req.skuNo().equals(row.getSkuNo())) {
                                throw BizException.of(ErrorCode.NOT_FOUND);
                            }
                            if (!Objects.equals(row.getVersion(), req.version()) || !"ACTIVE".equals(row.getStatus())
                                    || req.orderSource() != null && !req.orderSource().equals(row.getOrderSource())
                                    || req.fulfillment() != null && !req.fulfillment().equals(row.getFulfillment())) {
                                throw BizException.of(ErrorCode.CONFLICT);
                            }
                            var before = view(row, now);
                            if (rows.update(null, Wrappers.<FnbSalesRestriction>lambdaUpdate()
                                    .eq(FnbSalesRestriction::getEntityNo, entity)
                                    .eq(FnbSalesRestriction::getStoreNo, store)
                                    .eq(FnbSalesRestriction::getRestrictionNo, req.restrictionNo())
                                    .eq(FnbSalesRestriction::getVersion, req.version())
                                    .eq(FnbSalesRestriction::getStatus, "ACTIVE")
                                    .set(FnbSalesRestriction::getStatus, "CLEARED")
                                    .set(FnbSalesRestriction::getReason, req.reason())
                                    .set(FnbSalesRestriction::getVersion, req.version() + 1)
                                    .set(FnbSalesRestriction::getUpdatedAt, now)
                                    .set(FnbSalesRestriction::getUpdatedBy, actor)) != 1) {
                                throw BizException.of(ErrorCode.CONFLICT);
                            }
                            row.setStatus("CLEARED"); row.setReason(req.reason()); row.setVersion(req.version() + 1);
                            var after = view(row, now); record(entity, store, actor, key, "CLEAR", before, after);
                            return after;
                        }
                        var start = req.startsAt() == null ? now : utc(req.startsAt());
                        var expiry = utc(req.expiresAt());
                        if (req.businessDate() != null) {
                            if (policy.getCutoverMinute() == null || policy.getCutoverMinute() < 0
                                    || policy.getCutoverMinute() > 1439 || policy.getTimezone() == null
                                    || !ZoneId.getAvailableZoneIds().contains(policy.getTimezone())) {
                                throw BizException.of(ErrorCode.CONFLICT);
                            }
                            var zone = ZoneId.of(policy.getTimezone());
                            var from = LocalDateTime.ofInstant(req.businessDate().atStartOfDay()
                                    .plusMinutes(policy.getCutoverMinute()).atZone(zone).toInstant(), ZoneOffset.UTC);
                            var until = LocalDateTime.ofInstant(req.businessDate().plusDays(1).atStartOfDay()
                                    .plusMinutes(policy.getCutoverMinute()).atZone(zone).toInstant(), ZoneOffset.UTC);
                            start = req.startsAt() == null ? from : utc(req.startsAt());
                            expiry = req.expiresAt() == null ? until : utc(req.expiresAt());
                            if (start.isBefore(from) || !start.isBefore(until) || expiry.isAfter(until)) {
                                throw BizException.of(ErrorCode.BAD_REQUEST);
                            }
                        }
                        if (expiry != null && !expiry.isAfter(start)) {
                            throw BizException.of(ErrorCode.BAD_REQUEST);
                        }
                        var existing = rows.selectList(Wrappers.<FnbSalesRestriction>lambdaQuery()
                                .eq(FnbSalesRestriction::getEntityNo, entity).eq(FnbSalesRestriction::getStoreNo, store)
                                .eq(FnbSalesRestriction::getSkuNo, req.skuNo())
                                .eq(FnbSalesRestriction::getMode, req.mode())
                                .eq(FnbSalesRestriction::getStatus, "ACTIVE").last("FOR UPDATE"));
                        for (var row : existing) {
                            if (overlap(row.getOrderSource(), req.orderSource())
                                    && overlap(row.getFulfillment(), req.fulfillment())
                                    && (row.getExpiresAt() == null || row.getExpiresAt().isAfter(start))
                                    && (expiry == null || expiry.isAfter(row.getStartsAt()))) {
                                throw BizException.of(ErrorCode.CONFLICT);
                            }
                        }
                        var row = new FnbSalesRestriction(); row.setEntityNo(entity); row.setStoreNo(store);
                        row.setRestrictionNo(BizKey.next("FSRS")); row.setSkuNo(req.skuNo()); row.setMode(req.mode());
                        row.setBusinessDate(req.businessDate()); row.setQuotaQty(req.quota());
                        row.setReservedQty(BigDecimal.ZERO); row.setConsumedQty(BigDecimal.ZERO);
                        row.setStartsAt(start); row.setExpiresAt(expiry); row.setStatus("ACTIVE");
                        row.setReason(req.reason()); row.setOrderSource(req.orderSource());
                        row.setFulfillment(req.fulfillment()); row.setVersion(0L); rows.insert(row);
                        var after = view(row, now); record(entity, store, actor, key, "CREATE", null, after);
                        return after;
                    })));
        } catch (ConcurrencyFailureException | DuplicateKeyException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override public PageData<SalesRestrictionVO> list(String entity, String store, SalesRestrictionQuery query) {
        context(entity, store); SalesRestrictionPolicy.query(query);
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        return DataScopeContext.executeWithoutScope(() -> read.execute(tx -> {
            var now = now();
            var filter = Wrappers.<FnbSalesRestriction>lambdaQuery().eq(FnbSalesRestriction::getEntityNo, entity)
                    .eq(FnbSalesRestriction::getStoreNo, store);
            if (query != null && query.skuNo() != null) filter.eq(FnbSalesRestriction::getSkuNo, query.skuNo());
            if (query != null && query.status() != null) {
                switch (query.status()) {
                    case "CLEARED" -> filter.eq(FnbSalesRestriction::getStatus, "CLEARED");
                    case "EXPIRED" -> filter.and(w -> w.eq(FnbSalesRestriction::getStatus, "EXPIRED")
                            .or(x -> x.eq(FnbSalesRestriction::getStatus, "ACTIVE")
                                    .le(FnbSalesRestriction::getExpiresAt, now)));
                    case "ACTIVE" -> filter.eq(FnbSalesRestriction::getStatus, "ACTIVE")
                            .and(w -> w.isNull(FnbSalesRestriction::getExpiresAt)
                                    .or().gt(FnbSalesRestriction::getExpiresAt, now));
                    default -> throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
            var result = rows.selectPage(new Page<FnbSalesRestriction>(page, size),
                    filter.orderByAsc(FnbSalesRestriction::getRestrictionNo));
            return PageData.of(result.getRecords().stream().map(r -> view(r, now)).toList(),
                    result.getTotal(), page, size);
        }));
    }

    @Override public SalesRestrictionEvaluationVO evaluate(String entity, String store, String sku,
            String source, String fulfillment) {
        context(entity, store); MenuDefinitionPolicy.key(sku); MenuDefinitionPolicy.key(source);
        MenuDefinitionPolicy.key(fulfillment);
        SalesRestrictionPolicy.scope(source, fulfillment);
        return DataScopeContext.executeWithoutScope(() -> read.execute(tx -> {
            var now = now();
            var selected = rows.selectList(Wrappers.<FnbSalesRestriction>lambdaQuery()
                    .eq(FnbSalesRestriction::getEntityNo, entity).eq(FnbSalesRestriction::getStoreNo, store)
                    .eq(FnbSalesRestriction::getSkuNo, sku).eq(FnbSalesRestriction::getStatus, "ACTIVE")
                    .le(FnbSalesRestriction::getStartsAt, now)
                    .and(w -> w.isNull(FnbSalesRestriction::getExpiresAt)
                            .or().gt(FnbSalesRestriction::getExpiresAt, now))
                    .and(w -> w.isNull(FnbSalesRestriction::getOrderSource)
                            .or().eq(FnbSalesRestriction::getOrderSource, source))
                    .and(w -> w.isNull(FnbSalesRestriction::getFulfillment)
                            .or().eq(FnbSalesRestriction::getFulfillment, fulfillment))
                    .orderByAsc(FnbSalesRestriction::getRestrictionNo)).stream().map(r -> view(r, now)).toList();
            BigDecimal remaining = null; boolean blocked = false;
            for (var r : selected) {
                if ("SOLD_OUT".equals(r.mode())) blocked = true;
                else {
                    var available = r.quota().subtract(r.reservedQty()).subtract(r.consumedQty());
                    remaining = remaining == null ? available : remaining.min(available);
                    if (available.signum() == 0) blocked = true;
                }
            }
            return new SalesRestrictionEvaluationVO(blocked, remaining, false, now.toInstant(ZoneOffset.UTC), selected);
        }));
    }
    private FnbStorePolicy lockStore(String entity, String store) {
        var rows = policies.selectList(Wrappers.<FnbStorePolicy>lambdaQuery().eq(FnbStorePolicy::getEntityNo, entity)
                .eq(FnbStorePolicy::getStoreNo, store).orderByAsc(FnbStorePolicy::getPolicyNo).last("FOR UPDATE"));
        return rows.stream().filter(r -> r.getEffectiveFrom() != null && !r.getEffectiveFrom().isAfter(now()))
                .max(Comparator.comparing(FnbStorePolicy::getEffectiveFrom))
                .orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
    }
    private SalesRestrictionVO view(FnbSalesRestriction row, LocalDateTime now) {
        var status = "ACTIVE".equals(row.getStatus()) && row.getExpiresAt() != null
                && !row.getExpiresAt().isAfter(now) ? "EXPIRED" : row.getStatus();
        var v = new SalesRestrictionVO(row.getRestrictionNo(), row.getStoreNo(), row.getSkuNo(), row.getMode(),
                row.getBusinessDate(), row.getQuotaQty(), row.getReservedQty(), row.getConsumedQty(),
                row.getStartsAt().toInstant(ZoneOffset.UTC),
                row.getExpiresAt() == null ? null : row.getExpiresAt().toInstant(ZoneOffset.UTC),
                status, row.getReason(),
                row.getOrderSource(), row.getFulfillment(), row.getVersion());
        if (!SalesRestrictionPolicy.valid(v, row.getStoreNo())) {
            throw new IllegalStateException("Invalid stored restriction");
        }
        return v;
    }
    private void record(String entity, String store, String actor, String key, String action,
            SalesRestrictionVO before, SalesRestrictionVO after) {
        var audit = new FnbActionLog(); audit.setEntityNo(entity); audit.setStoreNo(store);
        audit.setActionNo(BizKey.next("FACT")); audit.setObjectType("SALES_RESTRICTION");
        audit.setObjectNo(after.restrictionNo()); audit.setAction(action); audit.setOperatorNo(actor);
        audit.setRequestNo(FoodCommandFingerprint.hash(
                FoodCommandFingerprint.scope("SALES_RESTRICTION", entity, store), key));
        if (before != null) audit.setBeforeRef(FoodCommandFingerprint.hash(json.writeValueAsString(before)));
        audit.setAfterRef(FoodCommandFingerprint.hash(json.writeValueAsString(after))); actions.insert(audit);
        publisher.publish(new SalesRestrictionChanged(entity, store, after.restrictionNo(), action,
                audit.getActionNo(), after), new PublishOptions("FOOD_SALES_RESTRICTION:" + after.restrictionNo(), 1));
    }
    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
    private static boolean overlap(String left, String right) { return left == null || right == null
            || left.equals(right); }
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }
    private static void context(String entity, String store) {
        MenuDefinitionPolicy.key(entity); MenuDefinitionPolicy.key(store);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Restriction operations cannot join an existing transaction");
        }
    }
}
