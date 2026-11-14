package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.service.StoreStockReader;
import ai.neargo.shop.spi.product.LocalStockControlPort.*;
import ai.neargo.shop.spi.product.LocalStockFactsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Product's own stock facts, deliberately independent of inv_* and stock-authority switches. */
@Component
public class PlatformLocalStockFactsPortImpl implements LocalStockFactsPort {
    private static final Set<Action> ACTIONS = Set.of(Action.QUERY, Action.RESERVE, Action.ACCEPT,
            Action.ISSUE_HANDOVER, Action.RELEASE, Action.RETURN);
    private final StoreStockReader stocks;
    private final GoodsMapper goods;
    public PlatformLocalStockFactsPortImpl(StoreStockReader stocks, GoodsMapper goods) {
        this.stocks = stocks; this.goods = goods;
    }
    @Override public String owner() { return "PLATFORM"; }
    @Override public Facts inspect(Scope scope, Request request) {
        var state = stocks.observe(scope.entityNo(), request.itemNo(), scope.storeNo());
        if (state == null || state.variants() == 0) return Facts.unavailable(Issue.ITEM_NOT_FOUND);
        if (state.variants() != 1) return Facts.unavailable(Issue.AMBIGUOUS_STOCK);
        var item = DataScopeContext.executeWithoutScope(() -> goods.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                .eq(PrdGoods::getTenantNo, scope.tenantNo()).eq(PrdGoods::getEntityNo, scope.entityNo())
                .eq(PrdGoods::getGoodsNo, state.goodsNo())));
        if (item == null) return Facts.unavailable(Issue.ITEM_NOT_FOUND);
        if (!PrdGoods.TYPE_NORMAL.equals(item.getType()) && !PrdGoods.TYPE_FRESH.equals(item.getType()))
            return Facts.unavailable(Issue.NOT_PHYSICAL_STOCK);
        if (state.baseUnit() == null || state.baseUnit().isBlank()) return Facts.unavailable(Issue.UNIT_UNCONFIGURED);
        if (!state.baseUnit().equals(request.baseUnit())) return Facts.unavailable(Issue.UNIT_MISMATCH);
        boolean initialized = state.onHand() != null && state.reserved() != null
                && state.onHand() >= 0 && state.reserved() >= 0 && state.reserved() <= state.onHand();
        var observation = new Observation(request.itemNo(), state.baseUnit(),
                state.storeManaged() ? QuantityScope.STORE : QuantityScope.ENTITY,
                state.storeManaged() ? scope.storeNo() : scope.entityNo(),
                initialized ? StockStatus.TRACKED : StockStatus.UNCONFIGURED,
                initialized ? BigDecimal.valueOf(state.onHand().longValue() - state.reserved()) : null);
        return new Facts(observation, 0, ACTIONS, initialized ? List.of() : List.of(Issue.ITEM_NOT_READY));
    }
}
