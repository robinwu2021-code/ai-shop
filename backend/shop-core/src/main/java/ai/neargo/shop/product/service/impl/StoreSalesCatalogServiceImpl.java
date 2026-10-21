package ai.neargo.shop.product.service.impl;

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
import ai.neargo.shop.product.entity.PrdStoreCatalog;
import ai.neargo.shop.product.entity.PrdStoreCatalogVersion;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.product.service.StoreSalesCatalogService;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.Definition;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.Entry;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.PublishReq;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.Snapshot;
import ai.neargo.shop.spi.product.StoreCatalogVersionPublished;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

/** Owner facts precede a short local transaction; head CAS, immutable version, idempotency and evidence are atomic. */
@Service
public class StoreSalesCatalogServiceImpl implements StoreSalesCatalogService {
    private final ProductMappers.StoreCatalogMapper heads;
    private final ProductMappers.StoreCatalogVersionMapper versions;
    private final StoreDirectoryPort stores;
    private final GoodsQueryPort quotes;
    private final IdempotencyService idempotency;
    private final DataSource source;
    private final ObjectMapper json;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    private final OutboxPublisher publisher;

    public StoreSalesCatalogServiceImpl(ProductMappers.StoreCatalogMapper heads,
            ProductMappers.StoreCatalogVersionMapper versions, StoreDirectoryPort stores, GoodsQueryPort quotes,
            IdempotencyService idempotency, @Qualifier("dataSource") DataSource source, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<UnifiedOutboxFacade> facades, ObjectProvider<OutboxConsumer> consumers,
            @Value("${shop.catalog.publication.archive-only:false}") boolean archiveOnly,
            @Value("${shop.outbox.maintenance:false}") boolean maintenance) {
        this.heads = heads;
        this.versions = versions;
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
        publisher = PlatformPublicationPublisher.create(facades::getIfAvailable, manager, () -> source, json,
                consumers::orderedStream, Set.of("PRODUCT_STORE_CATALOG_VERSION_PUBLISHED"), archiveOnly, maintenance);
    }

    @Override
    public Snapshot read(String entityNo, String storeNo, Instant at) {
        requireExternalScope(entityNo, storeNo, false);
        if (at == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        return DataScopeContext.executeWithoutScope(() -> reads.execute(status -> {
            var head = head(entityNo, storeNo);
            if (head == null) { return empty(entityNo, storeNo, null); }
            var version = versions.selectOne(Wrappers.<PrdStoreCatalogVersion>lambdaQuery()
                    .eq(PrdStoreCatalogVersion::getTenantNo, "MAIN")
                    .eq(PrdStoreCatalogVersion::getEntityNo, entityNo)
                    .eq(PrdStoreCatalogVersion::getStoreNo, storeNo)
                    .eq(PrdStoreCatalogVersion::getCatalogNo, head.getCatalogNo())
                    .le(PrdStoreCatalogVersion::getEffectiveAt, LocalDateTime.ofInstant(at, ZoneOffset.UTC))
                    .orderByDesc(PrdStoreCatalogVersion::getEffectiveAt, PrdStoreCatalogVersion::getRevisionIndex)
                    .last("LIMIT 1"));
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
        var head = head(entity, store);
        String next = BizKey.next("PCV");
        long revision = Math.addExact(request.expectedRevision(), 1L);
        if (head == null) {
            if (request.expectedRevision() != 0) { throw BizException.of(ErrorCode.CONFLICT); }
            head = new PrdStoreCatalog();
            head.setTenantNo("MAIN");
            head.setEntityNo(entity);
            head.setStoreNo(store);
            head.setCatalogNo(BizKey.next("PCT"));
            head.setCurrentVersionNo(next);
            head.setVersion(revision);
            heads.insert(head);
        } else {
            if (heads.advance(entity, store, head.getCatalogNo(), request.expectedRevision(), next, actor,
                    LocalDateTime.now(ZoneOffset.UTC)) != 1) { throw BizException.of(ErrorCode.CONFLICT); }
            head.setVersion(revision);
            head.setCurrentVersionNo(next);
        }
        var version = new PrdStoreCatalogVersion();
        version.setTenantNo("MAIN");
        version.setEntityNo(entity);
        version.setStoreNo(store);
        version.setCatalogNo(head.getCatalogNo());
        version.setCatalogVersionNo(next);
        version.setRevisionIndex(revision);
        version.setEffectiveAt(request.validFrom().toLocalDateTime());
        version.setFrozenPayload(payload);
        version.setSnapshotHash(hash);
        versions.insert(version);
        publisher.publish(new StoreCatalogVersionPublished(head.getCatalogNo(), entity, store, next, revision, hash),
                new PublishOptions("PRODUCT:CATALOG:" + IdempotencyScope.requestHash(entity, store), 1));
        return snapshot(head, version);
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
    private PrdStoreCatalog head(String entity, String store) {
        return heads.selectOne(Wrappers.<PrdStoreCatalog>lambdaQuery().eq(PrdStoreCatalog::getTenantNo, "MAIN")
                .eq(PrdStoreCatalog::getEntityNo, entity).eq(PrdStoreCatalog::getStoreNo, store));
    }
    private String hash(LocalDateTime effective, String payload) {
        return IdempotencyScope.requestHash("STORE_CATALOG_V1", effective.toString(), payload);
    }
    private Snapshot snapshot(PrdStoreCatalog head, PrdStoreCatalogVersion version) {
        if (!hash(version.getEffectiveAt(), version.getFrozenPayload()).equals(version.getSnapshotHash())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        Definition definition;
        try { definition = json.readValue(version.getFrozenPayload(), Definition.class); }
        catch (RuntimeException invalid) { throw BizException.of(ErrorCode.CONFLICT); }
        return new Snapshot(head.getEntityNo(), head.getStoreNo(), head.getCatalogNo(), head.getVersion(),
                version.getCatalogVersionNo(), version.getRevisionIndex(),
                version.getEffectiveAt().atOffset(ZoneOffset.UTC), true, definition, version.getSnapshotHash());
    }
    private Snapshot empty(String entity, String store, PrdStoreCatalog head) {
        return new Snapshot(entity, store, head == null ? null : head.getCatalogNo(),
                head == null ? 0 : head.getVersion(), null, 0, null, false, null, null);
    }
}
