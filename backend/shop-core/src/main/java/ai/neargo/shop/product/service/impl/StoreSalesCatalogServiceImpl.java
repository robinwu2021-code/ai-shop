package ai.neargo.shop.product.service.impl;

import ai.neargo.shop.common.config.ModuleDataSources;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.PlatformWritePrerequisites;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.idempotency.RequestIdempotencyPort;
import ai.neargo.shop.product.entity.PrdStoreCatalog;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.product.service.StoreSalesCatalogService;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.Definition;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.Entry;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.PublishReq;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.Snapshot;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Owner facts precede a short local transaction; version insert, idempotency and evidence are atomic.
 *
 * <p>门店交易库起目录一个版本一行（批次 2-g）：发布 = 锁住最新一版、核对 expectedRevision、插下一版；
 * 版本号的唯一约束兜住并发。读与结账取「已生效里最新的那一版」，所以定时生效照旧。
 */
@Service
public class StoreSalesCatalogServiceImpl implements StoreSalesCatalogService {
    private final ProductMappers.StoreCatalogMapper catalogs;
    private final StoreDirectoryPort stores;
    private final GoodsQueryPort quotes;
    private final RequestIdempotencyPort idempotency;
    private final DataSource source;
    private final ObjectMapper json;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    public StoreSalesCatalogServiceImpl(ProductMappers.StoreCatalogMapper catalogs,
            StoreDirectoryPort stores, GoodsQueryPort quotes,
            RequestIdempotencyPort idempotency,
            // 商品库句柄（第 4 批）：与交易绑同一个数据源名时就是同一个连接池，结账事务里的 hasResource 判据照旧成立
            @Qualifier(ModuleDataSources.PRODUCT_DATA_SOURCE) DataSource source, ObjectMapper json,
            @Qualifier(ModuleDataSources.PRODUCT_TX_MANAGER) PlatformTransactionManager manager) {
        this.catalogs = catalogs;
        this.stores = stores;
        this.quotes = quotes;
        this.idempotency = idempotency;
        this.source = source;
        this.json = json;
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Autowired
    @Qualifier("checkoutClock")
    private ObjectProvider<Clock> checkoutClocks;

    @Override
    public Snapshot lockForCheckout(String buyerNo, String entityNo, String storeNo, Instant at) {
        var current = SecurityUtils.requireUser();
        if (current.realm() != Realm.CONSUMER
                || current.subjectKind() != SubjectKind.USR
                || !"MAIN".equals(current.tenantNo())
                || !current.userNo().equals(buyerNo)
                || SecurityUtils.currentToken().filter(t -> !t.isBlank()).isEmpty()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return lockCurrentCatalog(entityNo, storeNo, at);
    }

    @Override
    public Snapshot lockForCounterCheckout(String tenant, String entity, String store, Instant at) {
        var current = SecurityUtils.requireUser();
        if (current.realm() != Realm.MERCHANT
                || !"MAIN".equals(tenant)
                || !tenant.equals(current.tenantNo())
                || !(current.subjectKind() == SubjectKind.MCH
                        || current.subjectKind() == SubjectKind.USR)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return lockCurrentCatalog(entity, store, at);
    }

    private Snapshot lockCurrentCatalog(String entityNo, String storeNo, Instant at) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(source)) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
        if (at == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return DataScopeContext.executeWithoutScope(
                () -> {
                    stores.checkoutCardInCurrentTransaction("MAIN", entityNo, storeNo);
                    var locked = catalogs.lockLatest(entityNo, storeNo);
                    if (locked == null) {
                        throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
                    }
                    // Acquiring the lock may have crossed a scheduled version boundary.
                    var currentTime = checkoutClocks.getIfAvailable(Clock::systemUTC).instant();
                    var version = effective(entityNo, storeNo, locked.getCatalogNo(), currentTime);
                    if (version == null) {
                        throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
                    }
                    return snapshot(locked, version);
                });
    }

    @Override
    public Snapshot read(String entityNo, String storeNo, Instant at) {
        requireExternalScope(entityNo, storeNo, false);
        if (at == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        return DataScopeContext.executeWithoutScope(() -> reads.execute(status -> {
            var head = latest(entityNo, storeNo);
            if (head == null) { return empty(entityNo, storeNo, null); }
            var version = effective(entityNo, storeNo, head.getCatalogNo(), at);
            return version == null ? empty(entityNo, storeNo, head) : snapshot(head, version);
        }));
    }

    @Override
    public Snapshot publish(String entityNo, String storeNo, String actorNo, PublishReq request, String key) {
        key = IdempotencyScope.requireKey(key);
        if (request == null || actorNo == null || actorNo.isBlank() || actorNo.length() > 64) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        requireExternalScope(entityNo, storeNo, true);
        String payload = json.writeValueAsString(request.definition());
        if (payload.getBytes(StandardCharsets.UTF_8).length > 65536) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        RuntimeException failure = null;
        try { validateProducts(entityNo, storeNo, request); }
        catch (RuntimeException unavailable) { failure = unavailable; }
        RuntimeException productFailure = failure;
        new PlatformWritePrerequisites().require(source);
        String endpoint = IdempotencyScope.endpoint("PRODUCT", "PUBLISH_STORE_CATALOG", entityNo, storeNo);
        String hash = hash(request.validFrom().toLocalDateTime(), payload);
        String requestHash = IdempotencyScope.requestHash(request.expectedRevision().toString(), hash);
        String replayKey = key;
        try {
            return DataScopeContext.executeWithoutScope(() -> writes.execute(status -> idempotency.execute(
                    replayKey, endpoint, actorNo, requestHash, Snapshot.class,
                    () -> {
                        if (productFailure != null) { throw productFailure; }
                        return write(entityNo, storeNo, actorNo, request, payload, hash);
                    })));
        } catch (DuplicateKeyException | ConcurrencyFailureException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    /** Capture current owner errors before the transaction; committed replay must bypass a new-action check. */
    private void validateProducts(String entityNo, String storeNo, PublishReq request) {
        var refs = request.entries().stream().map(Entry::skuNo).sorted().toList();
        if (!refs.isEmpty()) {
            var facts = quotes.snapshot(refs, Map.of(entityNo, storeNo));
            for (String sku : refs) {
                var fact = facts.get(sku);
                if (fact == null || !sku.equals(fact.skuNo()) || !entityNo.equals(fact.merchantNo())
                        || !storeNo.equals(fact.storeNo()) || !fact.onSale()) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
        }
    }

    private Snapshot write(String entity, String store, String actor, PublishReq request, String payload, String hash) {
        // 锁住最新一版再判 expectedRevision：同店的结账与发布在这一行上排队
        var head = catalogs.lockLatest(entity, store);
        long current = head == null ? 0 : head.getVersionNo();
        if (request.expectedRevision() != current) { throw BizException.of(ErrorCode.CONFLICT); }
        var version = new PrdStoreCatalog();
        version.setTenantNo("MAIN");
        version.setEntityNo(entity);
        version.setStoreNo(store);
        // 首发用门店号作目录号：两个人同时首发撞 (entity, catalog_no, version_no) 唯一键，只留一份目录
        version.setCatalogNo(head == null ? store : head.getCatalogNo());
        version.setCatalogVersionNo(BizKey.next("PCV"));
        version.setStage(PrdStoreCatalog.STAGE_PUBLISHED);
        version.setVersionNo(Math.toIntExact(Math.addExact(current, 1L)));
        version.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC));
        version.setEffectiveAt(request.validFrom().toLocalDateTime());
        version.setFrozenContent(payload);
        version.setSnapshotHash(hash);
        version.setCreatedBy(actor);
        version.setUpdatedBy(actor);
        catalogs.insert(version);
        return snapshot(version, version);
    }

    private void requireExternalScope(String entity, String store, boolean writing) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Catalog owner facts must be read outside the local transaction");
        }
        if (entity == null || entity.isBlank() || entity.length() > 64 || store == null || store.isBlank()
                || store.length() > 64 || !entity.equals(entity.trim()) || !store.equals(store.trim())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var card = stores.cards(List.of(store)).get(store);
        if (card == null || !store.equals(card.storeNo()) || !entity.equals(card.entityNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!(StoreDirectoryPort.STORE_ACTIVE.equals(card.status())
                || !writing && StoreDirectoryPort.STORE_READONLY.equals(card.status()))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }
    /** 这家店目录的最新一版（不论生效与否）：目录号与当前版本号取它 */
    private PrdStoreCatalog latest(String entity, String store) {
        return catalogs.selectOne(Wrappers.<PrdStoreCatalog>lambdaQuery().eq(PrdStoreCatalog::getTenantNo, "MAIN")
                .eq(PrdStoreCatalog::getEntityNo, entity).eq(PrdStoreCatalog::getStoreNo, store)
                .orderByDesc(PrdStoreCatalog::getVersionNo).last("LIMIT 1"));
    }

    /** 在 {@code at} 时已生效的最新一版；同一生效时刻发了多版取版本号大的 */
    private PrdStoreCatalog effective(String entity, String store, String catalogNo, Instant at) {
        return catalogs.selectOne(Wrappers.<PrdStoreCatalog>lambdaQuery()
                .eq(PrdStoreCatalog::getTenantNo, "MAIN")
                .eq(PrdStoreCatalog::getEntityNo, entity)
                .eq(PrdStoreCatalog::getStoreNo, store)
                .eq(PrdStoreCatalog::getCatalogNo, catalogNo)
                .le(PrdStoreCatalog::getEffectiveAt, LocalDateTime.ofInstant(at, ZoneOffset.UTC))
                .orderByDesc(PrdStoreCatalog::getEffectiveAt, PrdStoreCatalog::getVersionNo)
                .last("LIMIT 1"));
    }

    private String hash(LocalDateTime effective, String payload) {
        return IdempotencyScope.requestHash("STORE_CATALOG_V1", effective.toString(), payload);
    }
    /** {@code head} 是最新一版（给出目录号与当前版本号），{@code version} 是生效的那一版 */
    private Snapshot snapshot(PrdStoreCatalog head, PrdStoreCatalog version) {
        if (!hash(version.getEffectiveAt(), version.getFrozenContent()).equals(version.getSnapshotHash())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        Definition definition;
        try { definition = json.readValue(version.getFrozenContent(), Definition.class); }
        catch (RuntimeException invalid) { throw BizException.of(ErrorCode.CONFLICT); }
        return new Snapshot(head.getEntityNo(), head.getStoreNo(), head.getCatalogNo(), head.getVersionNo(),
                version.getCatalogVersionNo(), version.getVersionNo(),
                version.getEffectiveAt().atOffset(ZoneOffset.UTC), true, definition, version.getSnapshotHash());
    }

    private Snapshot empty(String entity, String store, PrdStoreCatalog head) {
        return new Snapshot(entity, store, head == null ? null : head.getCatalogNo(),
                head == null ? 0 : head.getVersionNo(), null, 0, null, false, null, null);
    }
}
