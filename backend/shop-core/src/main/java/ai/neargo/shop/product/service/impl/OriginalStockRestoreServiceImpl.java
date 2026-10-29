package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.policy.ExecutionStockRetentionGuard;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.entity.PrdStockLock;
import ai.neargo.shop.product.entity.PrdStockRestore;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockLockMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper;
import ai.neargo.shop.product.mapper.StockRestoreMapper;
import ai.neargo.shop.product.service.OriginalStockRestoreService;
import ai.neargo.shop.spi.product.StockPort.SkuQty;
import ai.neargo.shop.spi.product.StockDispositionPort.Original;
import ai.neargo.shop.spi.product.StockDispositionContract;
import java.util.ArrayList;
import java.util.Objects;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** The source row lock protects both the restore claim and the aggregate quantity cap in one transaction. */
@Service
public class OriginalStockRestoreServiceImpl implements OriginalStockRestoreService {
    private final StockLockMapper locks;
    private final StockRestoreMapper claims;
    private final SkuMapper skus;
    private final StoreStockMapper stores;

    public OriginalStockRestoreServiceImpl(StockLockMapper locks, StockRestoreMapper claims,
                                           SkuMapper skus, StoreStockMapper stores) {
        this.locks = locks;
        this.claims = claims;
        this.skus = skus;
        this.stores = stores;
    }

    private ExecutionStockRetentionGuard retentionGuard;

    @Autowired
    public void setRetentionGuard(ExecutionStockRetentionGuard retentionGuard) {
        this.retentionGuard = retentionGuard;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void restore(String purpose, String original, List<SkuQty> items) {
        restoreLocked(purpose, original, null, null, items);
    }
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void restore(String purpose, Original original, String confirmationOperationNo, List<SkuQty> items) {
        StockDispositionContract.requireOperation(original, confirmationOperationNo);
        if (!List.of("PLATFORM", "DUAL").contains(original.authority())) throw BizException.of(ErrorCode.CONFLICT);
        restoreLocked(purpose, original.orderNo(), original, confirmationOperationNo, items);
    }
    private void restoreLocked(String purpose, String original, Original frozen, String operation, List<SkuQty> items) {
        DataScopeContext.executeWithoutScope(() -> {
            List<PrdStockLock> source = locks.lockOriginal(original);
            if (frozen != null) requireFrozenPhysical(frozen, operation, source);
            retentionGuard.checkLocked(source);
            if (source.isEmpty() || source.stream().anyMatch(row -> !PrdStockLock.CONFIRMED.equals(row.getStatus()))) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            var originalIds = source.stream().map(PrdStockLock::getId).toList();
            var allClaims = claims.lockOriginalClaims(originalIds);
            if (allClaims.stream().anyMatch(c -> !Integer.valueOf(0).equals(c.getDeleted())
                    || c.getQty() == null || c.getQty() <= 0 || c.getPurposeNo() == null
                    || !originalIds.contains(c.getOriginalLockId()))) throw BizException.of(ErrorCode.CONFLICT);
            // Current accounting-row ownership follows links/claims, preserving the common owner lock hierarchy.
            if (frozen != null) {
                for (String skuNo : source.stream().map(PrdStockLock::getSkuNo).distinct().sorted().toList()) {
                    var identities = skus.lockSkuCurrent(skuNo);
                    if (identities.isEmpty() || identities.stream().anyMatch(sku ->
                            !Integer.valueOf(0).equals(sku.getDeleted()) || !frozen.tenantNo().equals(sku.getTenantNo())
                            || !frozen.entityNo().equals(sku.getEntityNo()))) throw BizException.of(ErrorCode.CONFLICT);
                }
            }
            if (frozen != null) {
                for (var row : source) {
                    if (row.getStoreNo() == null) continue;
                    var pools = stores.lockSkuStoresCurrent(row.getSkuNo()).stream()
                            .filter(pool -> row.getStoreNo().equals(pool.getStoreNo())).toList();
                    if (pools.size() != 1 || !Integer.valueOf(0).equals(pools.getFirst().getDeleted())
                            || !frozen.entityNo().equals(pools.getFirst().getEntityNo())
                            || !frozen.tenantNo().equals(pools.getFirst().getTenantNo())) {
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                }
            }
            Map<String, Integer> wanted = new LinkedHashMap<>();
            items.forEach(i -> wanted.merge(i.skuNo(), i.qty(), Math::addExact));
            for (var entry : wanted.entrySet()) {
                Set<String> requestedStores = items.stream().filter(i -> i.skuNo().equals(entry.getKey()))
                        .map(SkuQty::storeNo).collect(Collectors.toSet());
                List<PrdStockLock> candidates = source.stream().filter(l -> l.getSkuNo().equals(entry.getKey()))
                        .filter(l -> l.getStoreNo() == null || requestedStores.contains(l.getStoreNo())).toList();
                restoreSku(purpose, candidates, entry.getValue(), allClaims);
            }
            return null;
        });
    }
    /** All physical rows are locked before tuple/manifest validation; no unpinned subset can be restored. */
    private void requireFrozenPhysical(Original original, String operation, List<PrdStockLock> rows) {
        if (rows.isEmpty()) throw BizException.of(ErrorCode.CONFLICT);
        var actual = new ArrayList<SkuQty>();
        for (var row : rows) {
            if (!Integer.valueOf(0).equals(row.getDeleted()) || !original.tenantNo().equals(row.getTenantNo())
                    || !original.orderNo().equals(row.getLockNo()) || row.getQty() == null || row.getQty() <= 0
                    || row.getVersion() == null || row.getVersion() < 0
                    || !original.manifestHash().equals(row.getSourceManifestHash())
                    || !Objects.equals(original.lifecycleVersion(), row.getLifecycleVersion())
                    || !operation.equals(row.getConfirmationOperationNo()) || row.getFulfillmentRef() == null
                    || !PrdStockLock.CONFIRMED.equals(row.getStatus())
                    || row.getStoreNo() != null && !row.getStoreNo().equals(row.getSourceStoreNo())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            actual.add(new SkuQty(row.getSkuNo(), row.getQty(), row.getSourceStoreNo(),
                    row.getFulfillmentRef(), row.getLifecycleVersion()));
        }
        if (!original.manifestHash().equals(StockDispositionContract.manifestHash(
            StockDispositionContract.needs(actual)))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private void restoreSku(String purpose, List<PrdStockLock> source, int quantity, List<PrdStockRestore> allClaims) {
        if (quantity <= 0 || source.isEmpty()) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        List<Long> ids = source.stream().map(PrdStockLock::getId).toList();
        List<PrdStockRestore> prior = allClaims.stream().filter(c -> ids.contains(c.getOriginalLockId())).toList();
        if (prior.stream().anyMatch(c -> !Integer.valueOf(0).equals(c.getDeleted())
                || c.getQty() == null || c.getQty() <= 0 || c.getPurposeNo() == null
                || !ids.contains(c.getOriginalLockId()))) throw BizException.of(ErrorCode.CONFLICT);
        int own = prior.stream().filter(c -> purpose.equals(c.getPurposeNo())).mapToInt(PrdStockRestore::getQty).sum();
        if (own == quantity) {
            return;
        }
        if (own != 0) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        int remaining = quantity;
        for (PrdStockLock original : source) {
            int restored = prior.stream().filter(c -> original.getId().equals(c.getOriginalLockId()))
                    .mapToInt(PrdStockRestore::getQty).sum();
            int amount = Math.min(remaining, original.getQty() - restored);
            if (amount > 0) {
                post(purpose, original, amount);
                remaining -= amount;
            }
        }
        if (remaining != 0) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private void post(String purpose, PrdStockLock original, int qty) {
        PrdStockRestore claim = new PrdStockRestore();
        claim.setPurposeNo(purpose);
        claim.setOriginalLockId(original.getId());
        claim.setQty(qty);
        claims.insert(claim);
        int changed;
        if (Boolean.TRUE.equals(original.getPresale())) {
            changed = skus.releasePresale(original.getSkuNo(), qty);
        } else if (original.getStoreNo() != null) {
            changed = stores.restoreStock(original.getStoreNo(), original.getSkuNo(), qty);
        } else {
            changed = skus.restoreStock(original.getSkuNo(), qty);
        }
        if (changed != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
}
