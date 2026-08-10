package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.product.entity.PrdStockLock;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.entity.PrdStoreStock;
import ai.neargo.shop.product.mapper.ProductMappers.StockLockMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 库存锁定实现（{@link StockPort}）。
 *
 * <p><b>靠 SQL 的条件更新做原子扣减</b>，不是「先查后改」：
 * <pre>{@code UPDATE prd_sku SET locked_stock = locked_stock + n WHERE stock - locked_stock >= n}</pre>
 * 更新影响行数为 0 就是库存不足。先查后改在并发下必然超卖 —— 两个请求都查到「还有 1 件」。
 *
 * <p>S2 用 DB 条件更新。等到秒杀这类真正的高并发场景（S5 活动）再前置 Redis 原子扣减，
 * 那时 DB 这层作为兜底保留，不是替换。
 */
@Component
public class StockPortImpl implements StockPort {

    private final SkuMapper skuMapper;
    private final StockLockMapper lockMapper;
    private final StoreStockMapper storeStockMapper;

    public StockPortImpl(SkuMapper skuMapper, StockLockMapper lockMapper,
                         StoreStockMapper storeStockMapper) {
        this.skuMapper = skuMapper;
        this.lockMapper = lockMapper;
        this.storeStockMapper = storeStockMapper;
    }

    @Override
    @Transactional
    public List<String> lock(String lockNo, List<SkuQty> items) {
        List<String> failed = new ArrayList<>();
        for (SkuQty item : items) {
            String storeNo = item.storeNo();
            /*
             * 按 SKU 判定走哪条路，而不是按商家或按请求 ——
             * 同一个商家完全可能「大米按店管、酱油还没分」，一刀切会让没分的那些
             * 突然变成 0 库存（如果强制走店级）或无限库存（如果强制走主体级）。
             */
            boolean perStore = storeNo != null && !storeNo.isBlank() && hasStoreStock(item.skuNo());
            int affected = perStore
                    ? storeStockMapper.lockStock(storeNo, item.skuNo(), item.qty())
                    : skuMapper.lockStock(item.skuNo(), item.qty());
            if (affected == 0) {
                failed.add(item.skuNo());
                // 不 break：一次把所有不足的 SKU 都收集齐，端上能一次性标红，
                // 而不是让用户改一个提交一次再发现下一个
                continue;
            }
            PrdStockLock lock = new PrdStockLock();
            lock.setLockNo(lockNo);
            lock.setSkuNo(item.skuNo());
            lock.setQty(item.qty());
            // **锁在哪张表就记在哪** —— 释放与确认靠它决定把数减回哪里，
            // 记错的后果是库存凭空多出或少掉一批，而且要等到对账才发现
            lock.setStoreNo(perStore ? storeNo : null);
            lock.setStatus(PrdStockLock.LOCKED);
            lock.setLockedAt(LocalDateTime.now());
            lockMapper.insert(lock);
        }
        if (!failed.isEmpty()) {
            // 全成功或全失败：部分锁定的订单没法结算也没法退（TDD-backend 里 StockPort 的约定）
            // 抛异常让事务回滚，已锁的自然释放
            throw new PartialLockException(failed);
        }
        return failed;
    }

    @Override
    @Transactional
    public void release(String lockNo) {
        forEachActiveLock(lockNo, lock -> {
            if (lock.getStoreNo() != null) {
                storeStockMapper.releaseStock(lock.getStoreNo(), lock.getSkuNo(), lock.getQty());
            } else {
                skuMapper.releaseStock(lock.getSkuNo(), lock.getQty());
            }
            lock.setStatus(PrdStockLock.RELEASED);
            lock.setSettledAt(LocalDateTime.now());
            lockMapper.updateById(lock);
        });
    }

    @Override
    @Transactional
    public void confirm(String lockNo) {
        forEachActiveLock(lockNo, lock -> {
            // 锁定转实扣：总库存减、锁定量同步减，此后不再释放
            if (lock.getStoreNo() != null) {
                storeStockMapper.confirmStock(lock.getStoreNo(), lock.getSkuNo(), lock.getQty());
            } else {
                skuMapper.confirmStock(lock.getSkuNo(), lock.getQty());
            }
            lock.setStatus(PrdStockLock.CONFIRMED);
            lock.setSettledAt(LocalDateTime.now());
            lockMapper.updateById(lock);
        });
    }

    /**
     * 这个 SKU 启用分店库存了吗 —— **只要有任意一条行就算启用**。
     *
     * <p>判据刻意放在「有没有行」而不是「这家店有没有行」：
     * 后者会让商家给 A 店设了库存之后，B 店因为没有行而回退到主体总量，
     * 变成事实上的无限供应。前者则是「你开始分店管了，那每家店都得设」，
     * 没设的店卖不出去 —— 少卖是可恢复的，超卖不是。
     */
    private boolean hasStoreStock(String skuNo) {
        return DataScopeContext.executeWithoutScope(() ->
                storeStockMapper.selectCount(Wrappers.<PrdStoreStock>lambdaQuery()
                        .eq(PrdStoreStock::getSkuNo, skuNo))) > 0;
    }

    /** 只处理 LOCKED 状态的行 —— 重复释放/确认因此天然幂等（超时任务与用户取消会撞车）。 */
    private void forEachActiveLock(String lockNo, java.util.function.Consumer<PrdStockLock> action) {
        List<PrdStockLock> locks = DataScopeContext.executeWithoutScope(() ->
                lockMapper.selectList(Wrappers.<PrdStockLock>lambdaQuery()
                        .eq(PrdStockLock::getLockNo, lockNo)
                        .eq(PrdStockLock::getStatus, PrdStockLock.LOCKED)));
        locks.forEach(action);
    }

    /** 库存不足。带上具体 SKU，端上才能精确标红。 */
    public static class PartialLockException extends RuntimeException {
        private final transient List<String> skuNos;

        public PartialLockException(List<String> skuNos) {
            super("stock not enough: " + skuNos);
            this.skuNos = skuNos;
        }

        public List<String> skuNos() {
            return skuNos;
        }
    }
}
