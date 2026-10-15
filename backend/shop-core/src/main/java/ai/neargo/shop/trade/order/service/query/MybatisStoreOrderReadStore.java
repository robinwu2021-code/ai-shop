package ai.neargo.shop.trade.order.service.query;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.contract.StoreOrderQuery;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.ShipmentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.service.OrderStatusView;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/** The buyer owns the child and its payment; malformed historical links never widen the read scope. */
@Repository
public class MybatisStoreOrderReadStore implements StoreOrderReadStore {
    private final StoreOrderMapper orders;
    private final PaymentMapper payments;
    private final OrderItemMapper items;
    private final OrderStatusLogMapper logs;
    private final ShipmentMapper shipments;
    private final MerchantQueryPort merchants;

    public MybatisStoreOrderReadStore(StoreOrderMapper orders, PaymentMapper payments, OrderItemMapper items,
                                     OrderStatusLogMapper logs, ShipmentMapper shipments, MerchantQueryPort merchants) {
        this.orders = orders;
        this.payments = payments;
        this.items = items;
        this.logs = logs;
        this.shipments = shipments;
        this.merchants = merchants;
    }

    @Override
    public StoreOrderMaterial findOwned(String no, String buyer) {
        OrdSubOrder order = orders.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, no).eq(OrdSubOrder::getUserNo, buyer).last("LIMIT 1"));
        if (order == null) {
            return null;
        }
        List<StoreOrderMaterial> material = load(List.of(order), buyer);
        return material.isEmpty() ? null : material.get(0);
    }

    @Override
    public PageData<StoreOrderMaterial> pageOwned(String buyer, StoreOrderQuery query, long page, long size) {
        var filter = Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getUserNo, buyer)
                .apply("EXISTS (SELECT 1 FROM ord_order p WHERE p.order_no=ord_sub_order.order_no"
                        + " AND p.user_no={0} AND p.deleted=0)", buyer);
        List<String> statuses = OrderStatusView.toStored(query == null ? null : query.status());
        if (!statuses.isEmpty()) {
            filter.in(OrdSubOrder::getStatus, statuses);
        }
        if (query != null && query.fulfillments() != null && !query.fulfillments().isEmpty()) {
            filter.in(OrdSubOrder::getFulfillment, query.fulfillments());
        }
        filter.orderByDesc(OrdSubOrder::getId);
        Page<OrdSubOrder> result = orders.selectPage(Page.of(page, size), filter);
        return PageData.of(load(result.getRecords(), buyer), result.getTotal(), result.getCurrent(), result.getSize());
    }

    private List<StoreOrderMaterial> load(List<OrdSubOrder> rows, String buyer) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> payNos = rows.stream().map(OrdSubOrder::getOrderNo).distinct().toList();
        Map<String, OrdOrder> parents = payments.selectList(Wrappers.<OrdOrder>lambdaQuery()
                .in(OrdOrder::getOrderNo, payNos).eq(OrdOrder::getUserNo, buyer)).stream()
                .collect(Collectors.toMap(OrdOrder::getOrderNo, Function.identity()));
        List<OrdSubOrder> owned = rows.stream().filter(row -> parents.containsKey(row.getOrderNo())).toList();
        if (owned.isEmpty()) {
            return List.of();
        }
        List<String> subs = owned.stream().map(OrdSubOrder::getSubOrderNo).toList();
        Map<String, List<OrdItem>> lines = items.selectList(Wrappers.<OrdItem>lambdaQuery()
                .in(OrdItem::getSubOrderNo, subs).orderByAsc(OrdItem::getId)).stream()
                .collect(Collectors.groupingBy(OrdItem::getSubOrderNo));
        Map<String, List<OrdStatusLog>> timeline = logs.selectList(Wrappers.<OrdStatusLog>lambdaQuery()
                .in(OrdStatusLog::getSubOrderNo, subs).orderByAsc(OrdStatusLog::getAt)
                .orderByAsc(OrdStatusLog::getId)).stream().collect(Collectors.groupingBy(OrdStatusLog::getSubOrderNo));
        Map<String, List<OrdShipment>> packages = shipments.selectList(Wrappers.<OrdShipment>lambdaQuery()
                .in(OrdShipment::getSubOrderNo, subs).ne(OrdShipment::getStatus, OrdShipment.VOIDED)
                .orderByAsc(OrdShipment::getId)).stream().collect(Collectors.groupingBy(OrdShipment::getSubOrderNo));
        List<String> stores = owned.stream().map(OrdSubOrder::getStoreNo)
                .filter(Objects::nonNull).filter(no -> !no.isBlank()).distinct().toList();
        Map<String, String> names = stores.isEmpty() ? Map.of()
                : DataScopeContext.executeWithoutScope(() -> merchants.storeNames(stores));
        return owned.stream().map(row -> new StoreOrderMaterial(row, parents.get(row.getOrderNo()),
                lines.getOrDefault(row.getSubOrderNo(), List.of()),
                timeline.getOrDefault(row.getSubOrderNo(), List.of()),
                row.getStoreNo() == null ? null : names.get(row.getStoreNo()),
                packages.getOrDefault(row.getSubOrderNo(), List.of()))).toList();
    }
}
