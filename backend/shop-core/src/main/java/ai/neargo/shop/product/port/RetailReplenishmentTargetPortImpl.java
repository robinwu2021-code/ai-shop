package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdStoreStockSync;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreStockSyncMapper;
import ai.neargo.shop.product.service.StoreStockReader;
import ai.neargo.shop.spi.product.LocalStockControlPort.QuantityScope;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.product.RetailReplenishmentTargetPort;
import ai.neargo.shop.spi.product.StockDispositionContract.ReservationOwner;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Current PLATFORM target facts only; source admission and stock mutation are deliberately separate. */
@Component
public class RetailReplenishmentTargetPortImpl implements RetailReplenishmentTargetPort {
    private final StoreStockReader stocks;
    private final GoodsMapper goods;
    private final StoreStockSyncMapper syncs;
    private final MerchantQueryPort merchants;
    private final ObjectProvider<StockPort> executors;

    public RetailReplenishmentTargetPortImpl(StoreStockReader stocks, GoodsMapper goods,
            StoreStockSyncMapper syncs, MerchantQueryPort merchants, ObjectProvider<StockPort> executors) {
        this.stocks = stocks; this.goods = goods; this.syncs = syncs;
        this.merchants = merchants; this.executors = executors;
    }

    @Override @Transactional
    public Inspection inspect(Scope scope, Mapping mapping) {
        return inspectCurrent(scope, mapping);
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Inspection requireCurrent(Scope scope, Mapping mapping, Target original) {
        var current = inspectCurrent(scope, mapping);
        if (!current.ready()) return current;
        return Objects.equals(original, current.target()) ? current : rejected(Issue.TARGET_CHANGED);
    }

    private Inspection inspectCurrent(Scope scope, Mapping mapping) {
        if (!"MAIN".equals(scope.tenantNo()) || scope.entityNo() == null || scope.storeNo() == null)
            return rejected(Issue.STORE_SCOPE_MISMATCH);
        var store = merchants.storeProfileOf(scope.storeNo()).orElse(null);
        if (store == null || !scope.entityNo().equals(store.entityNo()) || !scope.storeNo().equals(store.storeNo()))
            return rejected(Issue.STORE_SCOPE_MISMATCH);
        if (!merchants.storeBusinessTypeOf(scope.storeNo()).filter("RETAIL"::equals).isPresent())
            return rejected(Issue.NOT_RETAIL);
        var executor = executors.getIfUnique();
        if (executor == null) return rejected(Issue.EXECUTOR_UNAVAILABLE);
        if (!(executor instanceof ReservationOwner owner) || !"PLATFORM".equals(owner.stockAuthority()))
            return rejected(Issue.EXECUTOR_NOT_PLATFORM);
        Integer quantity = converted(mapping);
        if (quantity == null) return rejected(Issue.INVALID_QUANTITY);
        // Product editing locks goods before SKU. The preliminary read only selects that lock root;
        // the current SKU below must still reference the exact goods row we locked.
        var candidate = stocks.observe(scope.entityNo(), mapping.skuNo(), scope.storeNo());
        if (candidate == null || candidate.goodsNo() == null) return rejected(Issue.ITEM_NOT_FOUND);
        var item = DataScopeContext.executeWithoutScope(() -> goods.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                .eq(PrdGoods::getTenantNo, scope.tenantNo()).eq(PrdGoods::getEntityNo, scope.entityNo())
                .eq(PrdGoods::getGoodsNo, candidate.goodsNo()).last("FOR UPDATE")));
        if (item == null) return rejected(Issue.ITEM_NOT_FOUND);
        var rows = stocks.lockPool(mapping.skuNo(), scope.storeNo());
        if (rows.skus().isEmpty()) return rejected(Issue.ITEM_NOT_FOUND);
        if (rows.skus().size() != 1) return rejected(Issue.AMBIGUOUS_STOCK);
        var sku = rows.skus().getFirst();
        if (!scope.entityNo().equals(sku.getEntityNo()) || rows.stores().stream()
                .anyMatch(row -> !scope.entityNo().equals(row.getEntityNo())))
            return rejected(Issue.STOCK_OWNER_MISMATCH);
        if (sku.getMarket() == null || !sku.getMarket().equals(store.market()))
            return rejected(Issue.AMBIGUOUS_STOCK);
        if (!item.getGoodsNo().equals(sku.getGoodsNo())) return rejected(Issue.TARGET_CHANGED);
        if (!PrdGoods.TYPE_NORMAL.equals(item.getType()) && !PrdGoods.TYPE_FRESH.equals(item.getType()))
            return rejected(Issue.NOT_PHYSICAL_STOCK);
        if (sku.getSaleUnit() == null || sku.getSaleUnit().isBlank() || !sku.getSaleUnit().equals(mapping.baseUnit()))
            return rejected(Issue.UNIT_MISMATCH);
        var selected = rows.stores().stream().filter(row -> scope.storeNo().equals(row.getStoreNo())).toList();
        if (selected.size() > 1) return rejected(Issue.AMBIGUOUS_STOCK);
        if (rows.observation().storeManaged() && selected.isEmpty()) return rejected(Issue.POOL_UNCONFIGURED);
        var state = rows.observation();
        if (state.onHand() == null || state.reserved() == null || state.onHand() < 0 || state.reserved() < 0
                || state.reserved() > state.onHand()) return rejected(Issue.INVALID_BALANCE);
        // Entity pools are shared across stores; any old source writeback within that entity conflicts.
        var old = DataScopeContext.executeWithoutScope(() -> syncs.selectList(Wrappers.<PrdStoreStockSync>lambdaQuery()
                .eq(PrdStoreStockSync::getTenantNo, scope.tenantNo())
                .eq(PrdStoreStockSync::getEntityNo, scope.entityNo())));
        if (old.stream().anyMatch(row -> !Integer.valueOf(0).equals(row.getEnabled())))
            return rejected(Issue.LEGACY_WRITEBACK_ENABLED);
        var kind = state.storeManaged() ? QuantityScope.STORE : QuantityScope.ENTITY;
        String ref = state.storeManaged() ? scope.storeNo() : scope.entityNo();
        Long poolId = state.storeManaged() ? selected.getFirst().getId() : sku.getId();
        if (sku.getId() == null || poolId == null || item.getId() == null) return rejected(Issue.POOL_UNCONFIGURED);
        String identity = fingerprint(scope, sku.getId(), poolId, item, sku.getMarket(), mapping, kind, ref);
        var target = new Target(scope.entityNo(), scope.storeNo(), mapping.skuNo(), sku.getMarket(),
                mapping.baseUnit(), kind, ref, identity);
        return new Inspection(target, state.onHand(), state.reserved(), quantity, List.of());
    }

    private static Inspection rejected(Issue issue) {
        return new Inspection(null, null, null, null, List.of(issue));
    }

    private static Integer converted(Mapping mapping) {
        if (mapping == null || mapping.skuNo() == null || mapping.skuNo().isBlank()
                || mapping.baseUnit() == null || mapping.baseUnit().isBlank()
                || !decimal(mapping.sourceQuantity()) || !decimal(mapping.factor())) return null;
        try {
            int result = mapping.sourceQuantity().multiply(mapping.factor()).intValueExact();
            return result > 0 ? result : null;
        } catch (ArithmeticException invalid) { return null; }
    }

    private static boolean decimal(BigDecimal number) {
        return number != null && number.signum() > 0 && number.scale() <= 6
                && number.precision() - number.scale() <= 18;
    }

    private static String fingerprint(Scope scope, Long skuId, Long poolId, PrdGoods goods, String market, Mapping mapping,
            QuantityScope kind, String ref) {
        // Length framing prevents business keys containing delimiters from creating the same identity.
        var value = new StringBuilder("RETAIL_POOL_V1");
        for (String field : List.of(scope.tenantNo(), scope.entityNo(), scope.storeNo(), mapping.skuNo(),
                goods.getGoodsNo(), goods.getId().toString(), market, mapping.baseUnit(), kind.name(), ref,
                skuId.toString(), poolId.toString()))
            value.append(':').append(field.length()).append(':').append(field);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
