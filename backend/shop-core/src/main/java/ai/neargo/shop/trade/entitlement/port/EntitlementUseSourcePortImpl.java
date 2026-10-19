package ai.neargo.shop.trade.entitlement.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.promotion.CouponCalculation;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.marketing.LegacyCouponOrderFactsPort;
import ai.neargo.shop.spi.product.ProductScopeFactsPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import ai.neargo.shop.trade.entitlement.EntitlementOrderSnapshotPolicy;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Core is the only authority for order identity and post-activity line amounts. No caller-supplied prices. */
@Component
public class EntitlementUseSourcePortImpl implements EntitlementUseSourcePort {
    private final OrderMapper orders;
    private final SubOrderMapper subs;
    private final OrderItemMapper items;
    private final MerchantScopeFactsPort merchants;
    private final StoreDirectoryPort stores;
    private final UserScopeFactsPort users;
    private final ProductScopeFactsPort products;
    private final LegacyCouponOrderFactsPort legacyCoupons;
    private final ObjectMapper json;

    public EntitlementUseSourcePortImpl(OrderMapper orders, SubOrderMapper subs, OrderItemMapper items,
            MerchantScopeFactsPort merchants, StoreDirectoryPort stores, UserScopeFactsPort users,
            ProductScopeFactsPort products, LegacyCouponOrderFactsPort legacyCoupons, ObjectMapper json) {
        this.orders = orders;
        this.subs = subs;
        this.items = items;
        this.merchants = merchants;
        this.stores = stores;
        this.users = users;
        this.products = products;
        this.legacyCoupons = legacyCoupons;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public UseSourceFact resolve(EntitlementContext context, SourceRef source) {
        if (source == null || !"CORE".equals(source.sourceSystem())
                || !Set.of("ORDER_USE", "STORE_USE").contains(source.sourceType())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var sub = DataScopeContext.executeWithoutScope(() -> subs.selectOne(
                Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getTenantNo, context.tenantNo())
                        .eq(OrdSubOrder::getSubOrderNo, source.sourceNo())));
        if (sub == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var order = DataScopeContext.executeWithoutScope(() -> orders.selectOne(
                Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getTenantNo, context.tenantNo())
                        .eq(OrdOrder::getOrderNo, sub.getOrderNo())));
        if (order == null || !order.getUserNo().equals(sub.getUserNo())
                || order.getVersion() == null || order.getVersion() != source.sourceRevision()
                || !List.of(OrdOrder.WAIT_PAY, OrdOrder.WAIT_OFFLINE_PAY).contains(order.getStatus())
                || !OrdSubOrder.WAIT_PAY.equals(sub.getStatus()) || sub.getEntityNo() == null || sub.getStoreNo() == null) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (sub.getPointsDeductMinor() != null && sub.getPointsDeductMinor() > 0) {
            // Repricing existing point allocations requires their own atomic reallocation protocol (020/049).
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
        if (users.findActive(context.tenantNo(), order.getUserNo()).isEmpty()
                || !merchants.entityActive(context.tenantNo(), sub.getEntityNo())
                || !merchants.storesOwned(context.tenantNo(), sub.getEntityNo(), Set.of(sub.getStoreNo()))) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        var card = stores.cards(List.of(sub.getStoreNo())).get(sub.getStoreNo());
        if (card == null || !card.active() || !sub.getEntityNo().equals(card.entityNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (legacyCoupons.hasActiveBinding(context.tenantNo(), order.getOrderNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var allSubs = DataScopeContext.executeWithoutScope(() -> subs.selectList(
                Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getTenantNo, context.tenantNo())
                        .eq(OrdSubOrder::getOrderNo, order.getOrderNo()).orderByAsc(OrdSubOrder::getId)));
        var allItems = DataScopeContext.executeWithoutScope(() -> items.selectList(
                Wrappers.<OrdItem>lambdaQuery().eq(OrdItem::getTenantNo, context.tenantNo())
                        .eq(OrdItem::getOrderNo, order.getOrderNo()).orderByAsc(OrdItem::getId)));
        var targetItems = allItems.stream().filter(i -> sub.getSubOrderNo().equals(i.getSubOrderNo())).toList();
        if (targetItems.isEmpty() || targetItems.stream().anyMatch(i -> i.getAmount() == null || i.getAmount() < 0
                || i.getQty() == null || i.getQty() < 1 || i.getGoodsNo() == null || i.getTitle() == null)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        long goods = 0;
        for (var item : targetItems) goods = Math.addExact(goods, item.getAmount());
        long activityDiscount = sub.getDiscountAmount() == null ? 0 : sub.getDiscountAmount();
        if (sub.getGoodsAmount() == null || goods != sub.getGoodsAmount() || activityDiscount < 0
                || activityDiscount > goods || sub.getFreightAmount() == null || sub.getFreightAmount() < 0) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var amounts = CouponCalculation.allocate(goods - activityDiscount,
                targetItems.stream().map(OrdItem::getAmount).toList());
        var lines = new ArrayList<UseItem>();
        Map<String, List<String>> categories = new HashMap<>();
        for (int i = 0; i < targetItems.size(); i++) {
            var item = targetItems.get(i);
            String lineNo = "OL" + item.getId();
            lines.add(new UseItem(lineNo, item.getGoodsNo(), item.getTitle(), item.getQty(), amounts.get(i)));
            categories.put(lineNo, products.categoryLineage(context.tenantNo(), item.getCategoryNo()));
        }
        return new UseSourceFact(context.tenantNo(), source, order.getUserNo(), sub.getEntityNo(), sub.getStoreNo(),
                order.getCurrency(), lines, EntitlementOrderSnapshotPolicy.hash(json, order, allSubs, allItems),
                card.storeName(), null, null, sub.getFreightAmount(), categories,
                sub.getAppointmentAt() == null ? null : Instant.ofEpochMilli(sub.getAppointmentAt()));
    }
}
