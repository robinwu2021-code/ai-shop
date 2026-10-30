package ai.neargo.shop.beauty.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.beauty.dto.PublicServiceVO;
import ai.neargo.shop.beauty.dto.PublicSalesCatalogVO;
import ai.neargo.shop.beauty.dto.ServiceSalesVO;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.beauty.dto.PublicStoreVO;
import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;
import ai.neargo.shop.beauty.dto.ServiceDetailQuery;
import ai.neargo.shop.beauty.dto.ServiceDetailVO;
import ai.neargo.shop.beauty.entity.BtyServiceDefinition;
import ai.neargo.shop.beauty.entity.BtyServiceDefinitionVersion;
import ai.neargo.shop.beauty.entity.BtyStorePolicy;
import ai.neargo.shop.beauty.entity.BtyStorePolicyVersion;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.ServiceCatalogFactsInternalApi;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Fetches remote owner facts before a short, consistent read of private, effective beauty snapshots. */
public class ServiceCatalogQueryServiceImpl implements ServiceCatalogQueryService {
    private final BeautyMappers.StorePolicyMapper policies;
    private final BeautyMappers.StorePolicyVersionMapper policyVersions;
    private final BeautyMappers.ServiceDefinitionMapper definitions;
    private final BeautyMappers.ServiceDefinitionVersionMapper definitionVersions;
    private final ObjectProvider<ServiceCatalogFactsPort> facts;
    private final ObjectMapper json;
    private final TransactionTemplate reads;

    public ServiceCatalogQueryServiceImpl(BeautyMappers.StorePolicyMapper policies,
            BeautyMappers.StorePolicyVersionMapper policyVersions, BeautyMappers.ServiceDefinitionMapper definitions,
            BeautyMappers.ServiceDefinitionVersionMapper definitionVersions,
            ObjectProvider<ServiceCatalogFactsPort> facts, ObjectMapper json, PlatformTransactionManager manager) {
        this.policies = policies;
        this.policyVersions = policyVersions;
        this.definitions = definitions;
        this.definitionVersions = definitionVersions;
        this.facts = facts;
        this.json = json;
        this.reads = new TransactionTemplate(manager);
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public PublicStoreVO getPublicStore(String storeNo) {
        outsideTransaction();
        new ServiceCatalogFactsInternalApi.StoreLookup(storeNo);
        var store = facts.getObject().store(storeNo);
        validateStore(storeNo, store);
        Instant now = Instant.now();
        return read(() -> publicStore(store, now));
    }

    @Override
    public PublicServiceVO getPublicService(String skuNo, ServiceDetailQuery query) {
        return detail(null, null, skuNo, query).service();
    }

    @Override
    public ServiceDetailVO getService(String entityNo, String currentStoreNo, String actorNo,
            String skuNo, ServiceDetailQuery query) {
        if (entityNo == null || entityNo.isBlank() || currentStoreNo == null || currentStoreNo.isBlank()
                || actorNo == null || actorNo.isBlank() || query == null
                || !currentStoreNo.equals(query.storeNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
        return detail(entityNo, currentStoreNo, skuNo, query);
    }

    private ServiceDetailVO detail(String entity, String storeNo, String skuNo, ServiceDetailQuery query) {
        outsideTransaction();
        if (query == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        new ServiceCatalogFactsInternalApi.ServiceLookup(query.storeNo(), skuNo);
        var product = facts.getObject().service(query.storeNo(), skuNo);
        if (product == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        validateStore(query.storeNo(), product.store());
        if (!"ACTIVE".equals(product.store().status()) || !skuNo.equals(product.skuNo())
                || product.goodsNo() == null || product.durationMin() < 1 || product.priceMinor() < 0) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (entity != null && (!entity.equals(product.store().entityNo())
                || !storeNo.equals(product.store().storeNo()))) { throw BizException.of(ErrorCode.FORBIDDEN); }
        var catalog = product.store().catalog();
        var entry = catalog.configured() ? catalog.definition().visibleEntry(skuNo) : null;
        if (catalog.configured() && entry == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        Instant now = Instant.now();
        var sales = catalog.configured()
                ? new ServiceSalesVO(catalog.versionNo(), entry.groupKey(), entry.position(),
                        catalog.definition().timezone(), entry.salesMode(), entry.windows(),
                        entry.saleableAt(now, catalog.definition().timezone()))
                : new ServiceSalesVO(null, null, 0, null, StoreSalesCatalogPort.SalesMode.UNRESTRICTED,
                        List.of(), true);
        return read(() -> {
            var store = publicStore(product.store(), now);
            var head = definitions.selectOne(Wrappers.<BtyServiceDefinition>lambdaQuery()
                    .eq(BtyServiceDefinition::getTenantNo, "MAIN")
                    .eq(BtyServiceDefinition::getEntityNo, product.store().entityNo())
                    .eq(BtyServiceDefinition::getStoreNo, product.store().storeNo())
                    .eq(BtyServiceDefinition::getSkuNo, product.skuNo())
                    .eq(BtyServiceDefinition::getStatus, "ACTIVE"));
            if (head == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
            var version = definitionVersions.selectOne(Wrappers.<BtyServiceDefinitionVersion>lambdaQuery()
                    .eq(BtyServiceDefinitionVersion::getTenantNo, "MAIN")
                    .eq(BtyServiceDefinitionVersion::getEntityNo, product.store().entityNo())
                    .eq(BtyServiceDefinitionVersion::getDefinitionNo, head.getDefinitionNo())
                    .le(BtyServiceDefinitionVersion::getCreatedAt, LocalDateTime.ofInstant(now, ZoneId.systemDefault()))
                    .orderByDesc(BtyServiceDefinitionVersion::getRevisionIndex).last("LIMIT 1"));
            if (version == null || version.getDurationMinSnapshot() != product.durationMin()) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            var duration = new PublishServiceDefinitionVersionReq.Duration(version.getDurationMinSnapshot(),
                    version.getBufferBeforeMin(), version.getBufferAfterMin());
            var service = new PublicServiceVO(product.store().storeNo(), product.skuNo(), product.goodsNo(),
                    product.title(), product.cover(), product.priceMinor(), version.getDefinitionVersionNo(), duration,
                    version.getNotes() == null ? List.of()
                            : List.of(json.readValue(version.getNotes(), String[].class)),
                    store, sales);
            return new ServiceDetailVO(service, head.getDefinitionNo(), head.getVersion());
        });
    }

    private PublicStoreVO publicStore(ServiceCatalogFactsPort.Store store, Instant now) {
        var head = policies.selectOne(Wrappers.<BtyStorePolicy>lambdaQuery()
                .eq(BtyStorePolicy::getTenantNo, "MAIN").eq(BtyStorePolicy::getEntityNo, store.entityNo())
                .eq(BtyStorePolicy::getStoreNo, store.storeNo()).eq(BtyStorePolicy::getStatus, "ACTIVE"));
        if (head == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        var version = policyVersions.selectOne(Wrappers.<BtyStorePolicyVersion>lambdaQuery()
                .eq(BtyStorePolicyVersion::getTenantNo, "MAIN")
                .eq(BtyStorePolicyVersion::getEntityNo, store.entityNo())
                .eq(BtyStorePolicyVersion::getPolicyNo, head.getPolicyNo())
                .le(BtyStorePolicyVersion::getEffectiveAt, LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .orderByDesc(BtyStorePolicyVersion::getEffectiveAt, BtyStorePolicyVersion::getRevisionIndex)
                .last("LIMIT 1"));
        if (version == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        var cancel = json.readValue(version.getCancelRule(), PublishStorePolicyVersionReq.CancellationRule.class);
        var modes = json.readValue(version.getAllowedModes(), String[].class);
        return new PublicStoreVO(store.storeNo(), store.name(), store.logo(), store.status(), store.openHours(),
                store.address(), version.getPolicyVersionNo(), version.getEffectiveAt().atOffset(ZoneOffset.UTC),
                new PublishStorePolicyVersionReq.Policy(version.getBookingAheadDays(), cancel, Set.of(modes)),
                store.catalog().configured() ? new PublicSalesCatalogVO(store.catalog().versionNo(),
                        store.catalog().validFrom(), store.catalog().definition().timezone(),
                        store.catalog().definition().groups().stream().filter(g -> g.visible()).toList())
                        : new PublicSalesCatalogVO(null, null, null, List.of()));
    }

    private void validateStore(String requested, ServiceCatalogFactsPort.Store store) {
        if (store == null || !requested.equals(store.storeNo()) || store.entityNo() == null
                || store.entityNo().isBlank() || store.catalog() == null
                || !store.entityNo().equals(store.catalog().entityNo())
                || !store.storeNo().equals(store.catalog().storeNo())
                || store.catalog().configured() && store.catalog().definition() == null
                || !("ACTIVE".equals(store.status()) || "READONLY".equals(store.status()))) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }
    private void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Catalog lookup must start outside a caller transaction");
        }
    }
    private <T> T read(Supplier<T> operation) {
        return DataScopeContext.executeWithoutScope(() -> reads.execute(status -> operation.get()));
    }
}
