package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void restore(String purpose, String original, List<SkuQty> items) {
        DataScopeContext.executeWithoutScope(() -> {
            List<PrdStockLock> source = locks.selectList(Wrappers.<PrdStockLock>lambdaQuery()
                    .eq(PrdStockLock::getLockNo, original).eq(PrdStockLock::getStatus, PrdStockLock.CONFIRMED)
                    .orderByAsc(PrdStockLock::getId).last("FOR UPDATE"));
            Map<String, Integer> wanted = new LinkedHashMap<>();
            items.forEach(i -> wanted.merge(i.skuNo(), i.qty(), Math::addExact));
            for (var entry : wanted.entrySet()) {
                Set<String> requestedStores = items.stream().filter(i -> i.skuNo().equals(entry.getKey()))
                        .map(SkuQty::storeNo).collect(Collectors.toSet());
                List<PrdStockLock> candidates = source.stream().filter(l -> l.getSkuNo().equals(entry.getKey()))
                        .filter(l -> l.getStoreNo() == null || requestedStores.contains(l.getStoreNo())).toList();
                restoreSku(purpose, candidates, entry.getValue());
            }
            return null;
        });
    }

    private void restoreSku(String purpose, List<PrdStockLock> source, int quantity) {
        if (quantity <= 0 || source.isEmpty()) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        List<Long> ids = source.stream().map(PrdStockLock::getId).toList();
        List<PrdStockRestore> prior = claims.selectList(Wrappers.<PrdStockRestore>lambdaQuery()
                .in(PrdStockRestore::getOriginalLockId, ids));
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
