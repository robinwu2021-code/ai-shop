package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSellRule;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStockSyncLog;
import ai.neargo.shop.product.entity.PrdStoreSku;
import ai.neargo.shop.product.entity.PrdStoreStockSync;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SellRuleMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockSyncLogMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreSkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreStockSyncMapper;
import ai.neargo.shop.product.service.StockSyncService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class StockSyncServiceImpl implements StockSyncService {

    private static final Set<String> SCOPES = Set.of(
            PrdSellRule.SCOPE_STORE, PrdSellRule.SCOPE_CATEGORY, PrdSellRule.SCOPE_GOODS);
    private static final Set<String> RULES = Set.of(PrdSellRule.ALL, PrdSellRule.RESERVE, PrdSellRule.RATIO,
            PrdSellRule.CAP, PrdSellRule.MANUAL, PrdSellRule.INHERIT);

    private final SellRuleMapper ruleMapper;
    private final StoreStockSyncMapper syncMapper;
    private final StockSyncLogMapper logMapper;
    private final StoreSkuMapper storeStockMapper;
    private final SkuMapper skuMapper;
    private final MerchantQueryPort merchants;
    private final GoodsMapper goodsMapper;
    /** 本市场：新建店级库存行写在这个市场上（按部署配置，正式环境 TW；没配回落 CN，与 StoreMarkets 一致）。 */
    private String homeMarket = "CN";

    @org.springframework.beans.factory.annotation.Value("${shop.merchant.default-market:CN}")
    void setHomeMarket(String market) {
        this.homeMarket = market.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public StockSyncServiceImpl(SellRuleMapper ruleMapper, StoreStockSyncMapper syncMapper,
                                StockSyncLogMapper logMapper, StoreSkuMapper storeStockMapper,
                                SkuMapper skuMapper, MerchantQueryPort merchants, GoodsMapper goodsMapper) {
        this.goodsMapper = goodsMapper;
        this.ruleMapper = ruleMapper;
        this.syncMapper = syncMapper;
        this.logMapper = logMapper;
        this.storeStockMapper = storeStockMapper;
        this.skuMapper = skuMapper;
        this.merchants = merchants;
    }

    // ------------------------------------------------------------------ 规则

    @Override
    public Rule ruleOf(String storeNo, String goodsNo, String categoryNo) {
        return StockSyncService.resolveRule(rules(storeNo), storeNo, goodsNo, categoryNo);
    }

    @Override
    public List<PrdSellRule> rules(String storeNo) {
        if (storeNo == null) {
            return List.of();
        }
        return DataScopeContext.executeWithoutScope(() -> ruleMapper.selectList(
                Wrappers.<PrdSellRule>lambdaQuery().eq(PrdSellRule::getStoreNo, storeNo)));
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.PRODUCT_TX_MANAGER)
    public PrdSellRule saveRule(String storeNo, String scopeType, String scopeRef, String ruleType, int param,
                                String operator) {
        if (storeNo == null || !SCOPES.contains(scopeType) || !RULES.contains(ruleType)
                || param < 0 || (PrdSellRule.RATIO.equals(ruleType) && (param < 1 || param > 100))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (PrdSellRule.INHERIT.equals(ruleType) && PrdSellRule.SCOPE_STORE.equals(scopeType)) {
            // 本店默认没有上一级可回
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String ref = PrdSellRule.SCOPE_STORE.equals(scopeType) ? storeNo : scopeRef;
        if (ref == null || ref.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PrdSellRule row = DataScopeContext.executeWithoutScope(() -> ruleMapper.selectOne(
                Wrappers.<PrdSellRule>lambdaQuery()
                        .eq(PrdSellRule::getStoreNo, storeNo)
                        .eq(PrdSellRule::getScopeType, scopeType)
                        .eq(PrdSellRule::getScopeRef, ref)
                        .last("limit 1")));
        if (row == null) {
            PrdSellRule n = new PrdSellRule();
            n.setStoreNo(storeNo);
            n.setScopeType(scopeType);
            n.setScopeRef(ref);
            n.setRuleType(ruleType);
            n.setParam(param);
            n.setCreatedBy(operator);
            n.setUpdatedBy(operator);
            DataScopeContext.executeWithoutScope(() -> ruleMapper.insert(n));
            return n;
        }
        row.setRuleType(ruleType);
        row.setParam(param);
        row.setUpdatedBy(operator);
        DataScopeContext.executeWithoutScope(() -> ruleMapper.updateById(row));
        return row;
    }

    // ------------------------------------------------------------------ 开关与对齐

    @Override
    public PrdStoreStockSync syncOf(String storeNo) {
        if (storeNo == null) {
            return null;
        }
        return DataScopeContext.executeWithoutScope(() -> syncMapper.selectOne(
                Wrappers.<PrdStoreStockSync>lambdaQuery()
                        .eq(PrdStoreStockSync::getStoreNo, storeNo).last("limit 1")));
    }

    @Override
    public boolean enabled(String storeNo) {
        PrdStoreStockSync s = syncOf(storeNo);
        return s != null && s.getEnabled() != null && s.getEnabled() == 1;
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.PRODUCT_TX_MANAGER)
    public PrdStoreStockSync markAligned(String entityNo, String storeNo, String mode, String operator) {
        if (!PrdStoreStockSync.ALIGN_MALL.equals(mode) && !PrdStoreStockSync.ALIGN_COUNT.equals(mode)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PrdStoreStockSync s = ensure(entityNo, storeNo, operator);
        s.setAlignedAt(LocalDateTime.now());
        s.setAlignedBy(operator);
        s.setAlignMode(mode);
        s.setUpdatedBy(operator);
        DataScopeContext.executeWithoutScope(() -> syncMapper.updateById(s));
        return s;
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.PRODUCT_TX_MANAGER)
    public PrdStoreStockSync setEnabled(String entityNo, String storeNo, boolean enabled, String operator) {
        PrdStoreStockSync s = ensure(entityNo, storeNo, operator);
        if (enabled && s.getAlignedAt() == null) {
            throw BizException.of(ErrorCode.STOCK_SYNC_NOT_ALIGNED);
        }
        s.setEnabled(enabled ? 1 : 0);
        s.setUpdatedBy(operator);
        DataScopeContext.executeWithoutScope(() -> syncMapper.updateById(s));
        return s;
    }

    private PrdStoreStockSync ensure(String entityNo, String storeNo, String operator) {
        PrdStoreStockSync s = syncOf(storeNo);
        if (s != null) {
            return s;
        }
        PrdStoreStockSync n = new PrdStoreStockSync();
        n.setStoreNo(storeNo);
        n.setEntityNo(entityNo);
        n.setEnabled(0);
        n.setCreatedBy(operator);
        n.setUpdatedBy(operator);
        DataScopeContext.executeWithoutScope(() -> syncMapper.insert(n));
        return n;
    }

    // ------------------------------------------------------------------ 写回

    @Override
    public Sellable sellableOf(String storeNo, String skuNo) {
        return sellablesOf(storeNo, java.util.List.of(skuNo)).getOrDefault(skuNo, new Sellable("NONE", 0, 0));
    }

    @Override
    public java.util.Map<String, Sellable> sellablesOf(String storeNo, java.util.Collection<String> skuNos) {
        if (skuNos.isEmpty()) return java.util.Map.of();
        var stockRows = DataScopeContext.executeWithoutScope(() -> storeStockMapper.selectList(
                Wrappers.<PrdStoreSku>lambdaQuery().in(PrdStoreSku::getSkuNo, skuNos)
                        .isNotNull(PrdStoreSku::getSellableQty)));
        var skuRows = DataScopeContext.executeWithoutScope(() -> skuMapper.selectList(
                Wrappers.<PrdSku>lambdaQuery().in(PrdSku::getSkuNo, skuNos)));
        java.util.Set<String> managed = new java.util.HashSet<>();
        java.util.Map<String, Sellable> entity = new java.util.HashMap<>();
        java.util.Map<String, Sellable> result = new java.util.LinkedHashMap<>();
        for (PrdStoreSku row : stockRows) {
            boolean fallback = PrdStoreSku.ENTITY_POOL.equals(row.getStoreNo());
            if (!fallback) managed.add(row.getSkuNo());
            if (fallback || java.util.Objects.equals(storeNo, row.getStoreNo())) {
                var balance = new Sellable(fallback ? "ENTITY" : "STORE",
                        nz(row.getSellableQty()) - nz(row.getLockedQty()), nz(row.getLockedQty()), ver(row.getVersion()));
                (fallback ? entity : result).put(row.getSkuNo(), balance);
            }
        }
        for (PrdSku row : skuRows) {
            result.putIfAbsent(row.getSkuNo(), managed.contains(row.getSkuNo()) ? new Sellable("STORE", 0, 0)
                    : entity.getOrDefault(row.getSkuNo(), new Sellable("ENTITY", 0, 0)));
        }
        return result;
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.PRODUCT_TX_MANAGER)
    public Result applyQuota(String entityNo, String storeNo, String skuNo, int qty, int available,
                             String sourceRef, String operator) {
        return write(entityNo, storeNo, skuNo, Math.max(0, available),
                Math.min(Math.max(0, qty), Math.max(0, available)), false, PrdSellRule.MANUAL, sourceRef, operator);
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.PRODUCT_TX_MANAGER)
    public Result apply(String entityNo, String storeNo, String skuNo, int available, String sourceRef,
                        String operator) {
        PrdSku sku = DataScopeContext.executeWithoutScope(() -> skuMapper.selectOne(
                Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getSkuNo, skuNo).last("limit 1")));
        if (sku == null) {
            return new Result(false, "NO_SKU", null, available, 0, 0);
        }
        Rule rule = ruleOf(storeNo, sku.getGoodsNo(), categoryOf(sku.getGoodsNo()));
        int u = Math.max(0, available);
        boolean manual = PrdSellRule.MANUAL.equals(rule.ruleType());
        // 手动：店主的额度只降不升 —— 可卖超过可用时压到可用（往上写是 applyQuota 的事）
        return write(entityNo, storeNo, skuNo, u, manual ? u : StockSyncService.target(rule, u), manual,
                rule.ruleType(), sourceRef, operator);
    }

    /**
     * 把线上可卖写成 {@code target} 并记一行明细（同一事务）。
     *
     * @param capOnly 只压不抬（手动规则的写回）
     */
    private Result write(String entityNo, String storeNo, String skuNo, int u, int target, boolean capOnly,
                         String ruleType, String sourceRef, String operator) {
        if (done(sourceRef, storeNo, skuNo)) {
            return new Result(false, "DONE_BEFORE", null, u, 0, 0);
        }
        Sellable before = sellableOf(storeNo, skuNo);
        String mode = before.mode();
        if ("NONE".equals(mode)) {
            return new Result(false, "NO_SKU", ruleType, u, 0, 0);
        }
        if ("ENTITY".equals(mode) && merchants.storeNos(entityNo).size() > 1) {
            /*
             * 主体级库存、主体不止一家店：不改。
             * 改主体级 = 把几家店的数混成一个；建本店的按店行 = 这个 SKU 转成按店算，别的店当场变 0。
             * 期初对齐页会把这类 SKU 标出来，让店主先按店设一次库存。
             */
            return new Result(false, "ENTITY_MULTI_STORE", ruleType, u, before.sellable(), before.sellable());
        }
        if ("STORE".equals(mode) && storeRow(storeNo, skuNo) == null && !capOnly) {
            insertStoreRow(entityNo, storeNo, skuNo, target, operator);
        } else if ("STORE".equals(mode)) {
            // 影响 0 行不是错：只压不抬时可卖本来就不超过目标。结果以写完再读为准
            if (capOnly) {
                storeStockMapper.capSellable(storeNo, skuNo, target);
            } else {
                storeStockMapper.syncSellable(storeNo, skuNo, target);
            }
        } else {
            DataScopeContext.executeWithoutScope(() -> capOnly
                    ? skuMapper.capSellable(skuNo, target) : skuMapper.syncSellable(skuNo, target));
        }
        int after = sellableOf(storeNo, skuNo).sellable();

        PrdStockSyncLog log = new PrdStockSyncLog();
        log.setStoreNo(storeNo);
        log.setSkuNo(skuNo);
        log.setSourceRef(sourceRef);
        log.setRuleType(ruleType);
        log.setAvailable(u);
        log.setBeforeQty(before.sellable());
        log.setAfterQty(after);
        log.setCreatedBy(operator);
        log.setUpdatedBy(operator);
        DataScopeContext.executeWithoutScope(() -> logMapper.insert(log));
        return new Result(true, null, ruleType, u, before.sellable(), after);
    }

    private boolean done(String sourceRef, String storeNo, String skuNo) {
        Long n = DataScopeContext.executeWithoutScope(() -> logMapper.selectCount(
                Wrappers.<PrdStockSyncLog>lambdaQuery()
                        .eq(PrdStockSyncLog::getSourceRef, sourceRef)
                        .eq(PrdStockSyncLog::getStoreNo, storeNo)
                        .eq(PrdStockSyncLog::getSkuNo, skuNo)));
        return n != null && n > 0;
    }

    private String categoryOf(String goodsNo) {
        if (goodsNo == null) {
            return null;
        }
        PrdGoods g = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectOne(
                Wrappers.<PrdGoods>lambdaQuery().select(PrdGoods::getCategoryNo)
                        .eq(PrdGoods::getGoodsNo, goodsNo).last("limit 1")));
        return g == null ? null : g.getCategoryNo();
    }

    /** 这家店的店级库存行；只设过门店价（没有店级库存）的行不算。 */
    private PrdStoreSku storeRow(String storeNo, String skuNo) {
        return DataScopeContext.executeWithoutScope(() -> storeStockMapper.selectOne(
                Wrappers.<PrdStoreSku>lambdaQuery()
                        .eq(PrdStoreSku::getStoreNo, storeNo)
                        .eq(PrdStoreSku::getSkuNo, skuNo)
                        .ne(PrdStoreSku::getStoreNo, PrdStoreSku.ENTITY_POOL)
                        .isNotNull(PrdStoreSku::getSellableQty).last("limit 1")));
    }

    private void insertStoreRow(String entityNo, String storeNo, String skuNo, int stock, String operator) {
        // 一店一 SKU 一行：只设过门店价的店补上库存列，再设成目标数
        DataScopeContext.executeWithoutScope(() -> {
            storeStockMapper.startStoreStock(entityNo, storeNo, skuNo, homeMarket);
            storeStockMapper.setStock(storeNo, skuNo, stock);
            return null;
        });
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long ver(Long v) { return v == null ? 0 : v; }
}
