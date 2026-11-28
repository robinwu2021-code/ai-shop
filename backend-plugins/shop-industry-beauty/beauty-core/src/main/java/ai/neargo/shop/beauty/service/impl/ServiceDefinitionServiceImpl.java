package ai.neargo.shop.beauty.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.ServiceDefinitionQuery;
import ai.neargo.shop.beauty.dto.ServiceDefinitionVO;
import ai.neargo.shop.beauty.dto.ServiceDefinitionVersionVO;
import ai.neargo.shop.beauty.entity.BtyActionLog;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.service.ServiceDefinitionService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.idempotency.RequestIdempotencyPort;
import ai.neargo.shop.spi.product.ServiceProductFactsPort;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * 美业的服务定义发布与查询。定义本身归商品域（TDD-美业域迁移新库 §2.8 P5），经 {@link ServiceDefinitions} 读写；
 * 发布入口、幂等、操作日志留在美业，美业的幂等键作为商品侧的操作号（{@code source_ref}），重放回到同一版本。
 */
public class ServiceDefinitionServiceImpl implements ServiceDefinitionService {
    private final ServiceDefinitions definitions;
    private final BeautyMappers.ActionLogMapper actions;
    private final RequestIdempotencyPort idempotency;
    private final ObjectProvider<ServiceProductFactsPort> products;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public ServiceDefinitionServiceImpl(ServiceDefinitions definitions, BeautyMappers.ActionLogMapper actions,
            RequestIdempotencyPort idempotency, ObjectProvider<ServiceProductFactsPort> products,
            PlatformTransactionManager manager, ObjectMapper json) {
        this.definitions = definitions;
        this.actions = actions;
        this.idempotency = idempotency;
        this.products = products;
        this.transactions = new TransactionTemplate(manager);
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
                // 不再检查主库的技术表（幂等、事件）：防重由宿主的幂等端口与业务唯一键负责（TDD §2.6-13）
                return idempotency.execute(key, endpoint, actorNo, hash, ServiceDefinitionVersionVO.class, () -> {
                    if (productFailure != null) { throw productFailure; }
                    validateProduct(entityNo, currentStoreNo, req, product);
                    return publish(entityNo, currentStoreNo, actorNo, req, key);
                });
            }));
        } catch (DuplicateKeyException | ConcurrencyFailureException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    /** 时长归服务定义（发布后镜像到商品），这里只核归属与技能引用。 */
    private void validateProduct(String entity, String store, PublishServiceDefinitionVersionReq req,
            ServiceProductFactsPort.Fact product) {
        if (product == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        if (!entity.equals(product.entityNo()) || !store.equals(product.storeNo())
                || !req.skuNo().equals(product.skuNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
        if (req.requirements().stream().anyMatch(row -> row.skillGoodsNo() != null
                && !row.skillGoodsNo().equals(product.goodsNo()))) { throw BizException.of(ErrorCode.BAD_REQUEST); }
    }

    private ServiceDefinitionVersionVO publish(String entity, String store, String actor,
            PublishServiceDefinitionVersionReq req, String key) {
        var published = definitions.publish(entity, store, actor, req, IdempotencyScope.requestHash(key));
        var action = new BtyActionLog();
        action.setEntityNo(entity);
        action.setActionNo(BizKey.next("BTAL"));
        action.setStoreNo(store);
        action.setAggregateType("BEAUTY_SERVICE_DEFINITION");
        action.setAggregateNo(published.definitionNo());
        action.setAction("DEFINITION_VERSION_PUBLISHED");
        action.setOperatorRef(actor);
        action.setExpectedRevision(req.expectedRevision());
        action.setResultRevision(published.revision());
        action.setSafeSnapshot(json.writeValueAsString(Map.of("definitionVersionNo", published.definitionVersionNo(),
                "hash", published.hash())));
        action.setRequestRef(IdempotencyScope.requestHash(key));
        actions.insert(action);
        return new ServiceDefinitionVersionVO(published.definitionNo(), published.definitionVersionNo(),
                published.revision(), published.hash());
    }

    @Override
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
        Instant at = query.at() == null ? Instant.now() : query.at().toInstant();
        List<ServiceDefinitions.Snapshot> rows = definitions.list(entityNo, currentStoreNo, at).stream()
                .filter(ServiceDefinitions.Snapshot::active).toList();
        if (query.skuNo() != null) {
            // 某个 SKU：有覆盖行用覆盖行，否则用默认行（商品号由覆盖行或唯一的默认行给出）
            var own = rows.stream().filter(row -> query.skuNo().equals(row.skuNo())).findFirst();
            rows = own.isPresent() ? List.of(own.get()) : List.of();
            if (own.isEmpty()) {
                var current = definitions.current(entityNo, currentStoreNo, query.skuNo());
                if (current != null && current.active() && !current.publishedAt().isAfter(at)) {
                    rows = List.of(current);
                }
            }
        }
        // revision 给「现在」的版本号（下一次发布的期望版本），effectiveRevision 给 at 时刻生效的那一版
        Map<String, Long> heads = new java.util.HashMap<>();
        for (var head : definitions.list(entityNo, currentStoreNo, LATEST)) {
            heads.merge(head.definitionNo(), head.revision(), Math::max);
        }
        long from = (long) (query.page() - 1) * query.size();
        var page = rows.stream().skip(from).limit(query.size())
                .map(row -> view(currentStoreNo, row, heads.getOrDefault(row.definitionNo(), row.revision()))).toList();
        return new PageData<>(page, rows.size(), query.page(), query.size());
    }

    /** 「现在」：取各商品最新一版用的时刻上界。 */
    private static final Instant LATEST = Instant.parse("9999-12-31T00:00:00Z");

    private ServiceDefinitionVO view(String store, ServiceDefinitions.Snapshot row, long head) {
        return new ServiceDefinitionVO(row.definitionNo(), store, row.skuNo(), head,
                row.definitionVersionNo(), row.revision(), row.publishedAt().atOffset(ZoneOffset.UTC),
                row.duration(), row.inputs(), row.hash(), row.notes());
    }

    private String identity(String operation, String entity, String store, String actor) {
        if (actor == null || actor.isBlank()) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        return IdempotencyScope.endpoint("BEAUTY", operation, entity, store);
    }
}
