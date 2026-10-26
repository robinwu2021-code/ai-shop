package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStockLock;
import ai.neargo.shop.product.entity.PrdStoreStock;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockLockMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper;
import ai.neargo.shop.product.port.StockPortImpl;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台档库存的两条「0 行就当没事」：
 *
 * <ol>
 *   <li>付款确认把锁定转实扣，SQL 带 {@code locked_stock >= qty AND stock >= qty}，
 *       0 行时此前照样把锁标成 CONFIRMED —— 锁定量永远漏在那里，那批货再也卖不出去，
 *       而账上写着「已扣」。</li>
 *   <li>手工改库存可以改到 locked_stock 以下 —— 已经锁给买家的货在账上没了，
 *       之后那一单付款确认就撞上第 1 条。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class StockConfirmGuardTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    StockPortImpl stockPort;
    @Autowired
    SkuMapper skuMapper;
    @Autowired
    StockLockMapper lockMapper;
    @Autowired
    StoreStockMapper storeStockMapper;

    private final List<String> skus = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 共享库：造的行要收走
        for (String sku : skus) {
            DataScopeContext.executeWithoutScope(() -> {
                skuMapper.delete(Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getSkuNo, sku));
                storeStockMapper.delete(Wrappers.<PrdStoreStock>lambdaQuery().eq(PrdStoreStock::getSkuNo, sku));
                return lockMapper.delete(Wrappers.<PrdStockLock>lambdaQuery().eq(PrdStockLock::getSkuNo, sku));
            });
        }
    }

    private String sku(int stock, int locked) {
        String skuNo = "SKU-CG-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
        PrdSku sku = new PrdSku();
        sku.setSkuNo(skuNo);
        sku.setGoodsNo("G-CG-" + skuNo);
        sku.setEntityNo("E-CG");
        sku.setMarket("CN");
        sku.setStock(stock);
        sku.setLockedStock(locked);
        sku.setPrice(100L);
        DataScopeContext.executeWithoutScope(() -> skuMapper.insert(sku));
        skus.add(skuNo);
        return skuNo;
    }

    private PrdSku load(String skuNo) {
        return DataScopeContext.executeWithoutScope(() -> skuMapper.selectOne(
                Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getSkuNo, skuNo)));
    }

    private String lock(String skuNo, int qty) {
        String lockNo = "LK-CG-" + skuNo;
        PrdStockLock lock = new PrdStockLock();
        lock.setLockNo(lockNo);
        lock.setSkuNo(skuNo);
        lock.setQty(qty);
        lock.setPresale(false);
        lock.setStatus(PrdStockLock.LOCKED);
        lock.setLockedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> lockMapper.insert(lock));
        return lockNo;
    }

    private String lockStatus(String lockNo) {
        return DataScopeContext.executeWithoutScope(() -> lockMapper.selectOne(
                Wrappers.<PrdStockLock>lambdaQuery().eq(PrdStockLock::getLockNo, lockNo))).getStatus();
    }

    @Test
    @DisplayName("★★★ 付款确认扣不动（0 行）→ 抛出，锁保持 LOCKED，不假装已扣")
    void confirmFailsLoudlyWhenDeductionMisses() {
        // 锁定量已经对不上（被手工改过 / 历史漏账）：locked_stock 只剩 0，锁行却要确认 3 件
        String skuNo = sku(10, 0);
        String lockNo = lock(skuNo, 3);

        assertThatThrownBy(() -> stockPort.confirm(lockNo))
                .as("0 行照样标 CONFIRMED = 账上「已扣」、库里没扣，对账前没有任何人知道")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);
        assertThat(lockStatus(lockNo)).isEqualTo(PrdStockLock.LOCKED);
        assertThat(load(skuNo).getStock()).isEqualTo(10);
    }

    @Test
    @DisplayName("★★ 正常确认照常扣：stock 与 locked_stock 同减，锁标 CONFIRMED")
    void confirmStillWorks() {
        String skuNo = sku(10, 3);
        String lockNo = lock(skuNo, 3);
        stockPort.confirm(lockNo);
        assertThat(lockStatus(lockNo)).isEqualTo(PrdStockLock.CONFIRMED);
        PrdSku after = load(skuNo);
        assertThat(after.getStock()).isEqualTo(7);
        assertThat(after.getLockedStock()).isZero();
    }

    @Test
    @DisplayName("★★★ 手工改库存不能改到已锁定量以下（主体级）")
    void setOnHandCannotGoBelowLocked() {
        String skuNo = sku(10, 5);
        assertThatThrownBy(() -> stockPort.setOnHand(skuNo, null, 3, "OTHER"))
                .as("改到锁定量以下 = 已经卖给买家的 5 件在账上只剩 3 件，那几单付款时扣不动")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(load(skuNo).getStock()).isEqualTo(10);

        // 恰好等于锁定量是合法的：可卖 0，锁着的那几单照样能确认
        stockPort.setOnHand(skuNo, null, 5, "OTHER");
        assertThat(load(skuNo).getStock()).isEqualTo(5);

        // 不存在的 SKU 仍然是 404，不与上面混成一个错
        assertThatThrownBy(() -> stockPort.setOnHand("SKU-CG-NOPE", null, 1, "OTHER"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("★★★ 手工改库存不能改到已锁定量以下（门店级）")
    void setOnHandPerStoreCannotGoBelowLocked() {
        String skuNo = sku(0, 0);
        PrdStoreStock row = new PrdStoreStock();
        row.setStoreNo("ST-CG");
        row.setSkuNo(skuNo);
        row.setEntityNo("E-CG");
        row.setStock(8);
        row.setLockedStock(4);
        DataScopeContext.executeWithoutScope(() -> storeStockMapper.insert(row));

        assertThatThrownBy(() -> stockPort.setOnHand(skuNo, "ST-CG", 2, "OTHER"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);
        PrdStoreStock after = DataScopeContext.executeWithoutScope(() -> storeStockMapper.selectOne(
                Wrappers.<PrdStoreStock>lambdaQuery().eq(PrdStoreStock::getSkuNo, skuNo)));
        assertThat(after.getStock()).isEqualTo(8);
    }
}
