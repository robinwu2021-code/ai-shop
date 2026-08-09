package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.product.entity.PrdStockLock;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockLockMapper;
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

    public StockPortImpl(SkuMapper skuMapper, StockLockMapper lockMapper) {
        this.skuMapper = skuMapper;
        this.lockMapper = lockMapper;
    }

    @Override
    @Transactional
    public List<String> lock(String lockNo, List<SkuQty> items) {
        List<String> failed = new ArrayList<>();
        for (SkuQty item : items) {
            int affected = skuMapper.lockStock(item.skuNo(), item.qty());
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
            skuMapper.releaseStock(lock.getSkuNo(), lock.getQty());
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
            skuMapper.confirmStock(lock.getSkuNo(), lock.getQty());
            lock.setStatus(PrdStockLock.CONFIRMED);
            lock.setSettledAt(LocalDateTime.now());
            lockMapper.updateById(lock);
        });
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
