package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSellRule;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStockSyncLog;
import ai.neargo.shop.product.entity.PrdStoreStock;
import ai.neargo.shop.product.entity.PrdStoreStockSync;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SellRuleMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockSyncLogMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper;
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
    private static final Set<String> RULES = Set.of(
            PrdSellRule.ALL, PrdSellRule.RESERVE, PrdSellRule.RATIO, PrdSellRule.CAP, PrdSellRule.MANUAL);

    private final SellRuleMapper ruleMapper;
    private final StoreStockSyncMapper syncMapper;
    private final StockSyncLogMapper logMapper;
    private final StoreStockMapper storeStockMapper;
    private final SkuMapper skuMapper;
    private final MerchantQueryPort merchants;
    private final GoodsMapper goodsMapper;

    public StockSyncServiceImpl(SellRuleMapper ruleMapper, StoreStockSyncMapper syncMapper,
                                StockSyncLogMapper logMapper, StoreStockMapper storeStockMapper,
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
        List<PrdSellRule> rows = rules(storeNo);
        PrdSellRule hit = find(rows, PrdSellRule.SCOPE_GOODS, goodsNo);
        if (hit == null) {
            hit = find(rows, PrdSellRule.SCOPE_CATEGORY, categoryNo);
        }
        if (hit == null) {
            hit = find(rows, PrdSellRule.SCOPE_STORE, storeNo);
        }
        return hit == null
                ? new Rule(PrdSellRule.SCOPE_STORE, storeNo, PrdSellRule.ALL, 0)
                : new Rule(hit.getScopeType(), hit.getScopeRef(), hit.getRuleType(),
                        hit.getParam() == null ? 0 : hit.getParam());
    }

    private static PrdSellRule find(List<PrdSellRule> rows, String scope, String ref) {
        if (ref == null) {
            return null;
        }
        return rows.stream()
                .filter(r -> scope.equals(r.getScopeType()) && ref.equals(r.getScopeRef()))
                .findFirst().orElse(null);
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
    @Transactional
    public PrdSellRule saveRule(String storeNo, String scopeType, String scopeRef, String ruleType, int param,
                                String operator) {
        if (storeNo == null || !SCOPES.contains(scopeType) || !RULES.contains(ruleType)
                || param < 0 || (PrdSellRule.RATIO.equals(ruleType) && (param < 1 || param > 100))) {
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
    @Transactional
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
    @Transactional
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
        PrdStoreStock row = storeRow(storeNo, skuNo);
        if (row != null) {
            return new Sellable("STORE", nz(row.getStock()) - nz(row.getLockedStock()), nz(row.getLockedStock()));
        }
        if (hasStoreStock(skuNo)) {
            // 这个 SKU 已按店管、本店没设过 —— 本店线上卖 0（StockPortImpl 的口径）
            return new Sellable("STORE", 0, 0);
        }
        PrdSku sku = DataScopeContext.executeWithoutScope(() -> skuMapper.selectOne(
                Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getSkuNo, skuNo).last("limit 1")));
        return sku == null
                ? new Sellable("NONE", 0, 0)
                : new Sellable("ENTITY", nz(sku.getStock()) - nz(sku.getLockedStock()), nz(sku.getLockedStock()));
    }

    @Override
    @Transactional
    public Result apply(String entityNo, String storeNo, String skuNo, int available, String sourceRef,
                        String operator) {
        if (done(sourceRef, storeNo, skuNo)) {
            return new Result(false, "DONE_BEFORE", null, available, 0, 0);
        }
        PrdSku sku = DataScopeContext.executeWithoutScope(() -> skuMapper.selectOne(
                Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getSkuNo, skuNo).last("limit 1")));
        if (sku == null) {
            return new Result(false, "NO_SKU", null, available, 0, 0);
        }
        String categoryNo = categoryOf(sku.getGoodsNo());
        Rule rule = ruleOf(storeNo, sku.getGoodsNo(), categoryNo);
        int u = Math.max(0, available);
        boolean manual = PrdSellRule.MANUAL.equals(rule.ruleType());
        // 手动：店主的额度只降不升 —— 可卖超过可用时压到可用
        int target = manual ? u : StockSyncService.target(rule, u);

        Sellable before = sellableOf(storeNo, skuNo);
        String mode = before.mode();
        if ("ENTITY".equals(mode) && merchants.storeNos(entityNo).size() > 1) {
            /*
             * 主体级库存、主体不止一家店：不改。
             * 改主体级 = 把几家店的数混成一个；建本店的按店行 = 这个 SKU 转成按店算，别的店当场变 0。
             * 期初对齐页会把这类 SKU 标出来，让店主先按店设一次库存。
             */
            return new Result(false, "ENTITY_MULTI_STORE", rule.ruleType(), u, before.sellable(), before.sellable());
        }
        if ("STORE".equals(mode) && storeRow(storeNo, skuNo) == null && !manual) {
            insertStoreRow(entityNo, storeNo, skuNo, target, operator);
        } else if ("STORE".equals(mode)) {
            // 影响 0 行不是错：手动规则下可卖本来就不超过可用。结果以写完再读为准
            if (manual) {
                storeStockMapper.capSellable(storeNo, skuNo, target);
            } else {
                storeStockMapper.syncSellable(storeNo, skuNo, target);
            }
        } else {
            DataScopeContext.executeWithoutScope(() -> manual
                    ? skuMapper.capSellable(skuNo, target) : skuMapper.syncSellable(skuNo, target));
        }
        int after = sellableOf(storeNo, skuNo).sellable();

        PrdStockSyncLog log = new PrdStockSyncLog();
        log.setStoreNo(storeNo);
        log.setSkuNo(skuNo);
        log.setSourceRef(sourceRef);
        log.setRuleType(rule.ruleType());
        log.setAvailable(u);
        log.setBeforeQty(before.sellable());
        log.setAfterQty(after);
        log.setCreatedBy(operator);
        log.setUpdatedBy(operator);
        DataScopeContext.executeWithoutScope(() -> logMapper.insert(log));
        return new Result(true, null, rule.ruleType(), u, before.sellable(), after);
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

    private PrdStoreStock storeRow(String storeNo, String skuNo) {
        return DataScopeContext.executeWithoutScope(() -> storeStockMapper.selectOne(
                Wrappers.<PrdStoreStock>lambdaQuery()
                        .eq(PrdStoreStock::getStoreNo, storeNo)
                        .eq(PrdStoreStock::getSkuNo, skuNo).last("limit 1")));
    }

    private boolean hasStoreStock(String skuNo) {
        Long n = DataScopeContext.executeWithoutScope(() -> storeStockMapper.selectCount(
                Wrappers.<PrdStoreStock>lambdaQuery().eq(PrdStoreStock::getSkuNo, skuNo)));
        return n != null && n > 0;
    }

    private void insertStoreRow(String entityNo, String storeNo, String skuNo, int stock, String operator) {
        PrdStoreStock row = new PrdStoreStock();
        row.setStoreNo(storeNo);
        row.setSkuNo(skuNo);
        row.setEntityNo(entityNo);
        row.setStock(stock);
        row.setLockedStock(0);
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        DataScopeContext.executeWithoutScope(() -> storeStockMapper.insert(row));
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
