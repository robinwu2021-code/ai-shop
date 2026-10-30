package ai.neargo.shop.trade.order.service.query;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.member.GuestRegistrationPort.GuestRef;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
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
    public StoreOrderMaterial findReadable(String no, CustomerReadScope scope) {
        return DataScopeContext.executeWithoutScope(() -> findReadableLocal(no, scope));
    }

    private StoreOrderMaterial findReadableLocal(String no, CustomerReadScope scope) {
        var row = orders.selectOne(readable(scope).eq(OrdSubOrder::getSubOrderNo, no));
        if (row == null) {
            return null;
        }
        var material = load(List.of(row), null, scope);
        return material.isEmpty() ? null : material.getFirst();
    }

    @Override
    public PageData<StoreOrderMaterial> pageReadable(
            CustomerReadScope scope, StoreOrderQuery query, long page, long size) {
        return DataScopeContext.executeWithoutScope(
                () -> pageReadableLocal(scope, query, page, size));
    }

    private PageData<StoreOrderMaterial> pageReadableLocal(
            CustomerReadScope scope, StoreOrderQuery query, long page, long size) {
        var filter = readable(scope);
        List<String> statuses = OrderStatusView.toStored(query == null ? null : query.status());
        if (!statuses.isEmpty()) {
            filter.in(OrdSubOrder::getStatus, statuses);
        }
        if (query != null && query.fulfillments() != null && !query.fulfillments().isEmpty()) {
            filter.in(OrdSubOrder::getFulfillment, query.fulfillments());
        }
        filter.orderByDesc(OrdSubOrder::getId);
        var result = orders.selectPage(Page.of(page, size), filter);
        return PageData.of(
                load(result.getRecords(), null, scope),
                result.getTotal(),
                result.getCurrent(),
                result.getSize());
    }

    private LambdaQueryWrapper<OrdSubOrder> readable(CustomerReadScope scope) {
        if (scope == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var args = new ArrayList<Object>();
        args.add(scope.userNo());
        var sql =
                new StringBuilder(
                        "EXISTS (SELECT 1 FROM ord_order p WHERE p.tenant_no=ord_sub_order.tenant_no"
                                + " AND p.order_no=ord_sub_order.order_no AND p.deleted=0"
                                + " AND p.customer_kind=ord_sub_order.customer_kind"
                                + " AND (p.identity_revision=ord_sub_order.identity_revision"
                                + " OR (p.identity_revision IS NULL AND ord_sub_order.identity_revision IS NULL))"
                                + " AND ((p.customer_kind='USER' AND p.user_no={0} AND ord_sub_order.user_no={0}"
                                + " AND p.guest_no IS NULL AND ord_sub_order.guest_no IS NULL)"
                                + " OR (p.customer_kind='GUEST' AND p.user_no IS NULL AND ord_sub_order.user_no IS NULL"
                                + " AND p.guest_no=ord_sub_order.guest_no AND p.identity_revision>0"
                                + " AND p.admission_kind='MANUAL_OFFLINE'"
                                + " AND ord_sub_order.admission_kind='MANUAL_OFFLINE'"
                                + " AND p.caller='BEAUTY' AND ord_sub_order.caller='BEAUTY'"
                                + " AND p.operation_no=ord_sub_order.source_operation_no"
                                + " AND p.entity_no=ord_sub_order.entity_no"
                                + " AND p.store_no=ord_sub_order.store_no AND (");
        if (scope.claimedGuests().isEmpty()) {
            sql.append("1=0");
        }
        for (int index = 0; index < scope.claimedGuests().size(); index++) {
            if (index > 0) {
                sql.append(" OR ");
            }
            var claim = scope.claimedGuests().get(index);
            int offset = args.size();
            sql.append("(p.entity_no={")
                    .append(offset)
                    .append("} AND p.store_no={")
                    .append(offset + 1)
                    .append("} AND p.guest_no={")
                    .append(offset + 2)
                    .append("})");
            args.add(claim.entityNo());
            args.add(claim.storeNo());
            args.add(claim.guestNo());
        }
        sql.append("))))");
        return Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getTenantNo, "MAIN")
                .apply(sql.toString(), args.toArray());
    }

    private static boolean readableParent(OrdOrder row, CustomerReadScope scope) {
        return row != null
                && "MAIN".equals(row.getTenantNo())
                && ("USER".equals(row.getCustomerKind())
                                && row.getGuestNo() == null
                                && scope.userNo().equals(row.getUserNo())
                        || "GUEST".equals(row.getCustomerKind())
                                && row.getUserNo() == null
                                && row.getGuestNo() != null
                                && row.getEntityNo() != null
                                && row.getStoreNo() != null
                                && "MANUAL_OFFLINE".equals(row.getAdmissionKind())
                                && row.getIdentityRevision() != null
                                && row.getIdentityRevision() > 0
                                && scope.claimedGuests()
                                        .contains(
                                                new GuestRef(
                                                        row.getEntityNo(),
                                                        row.getStoreNo(),
                                                        row.getGuestNo())));
    }

    private static boolean matchingParent(OrdOrder parent, OrdSubOrder child) {
        return parent != null
                && Objects.equals(parent.getTenantNo(), child.getTenantNo())
                && Objects.equals(parent.getCustomerKind(), child.getCustomerKind())
                && Objects.equals(parent.getUserNo(), child.getUserNo())
                && Objects.equals(parent.getGuestNo(), child.getGuestNo())
                && Objects.equals(parent.getIdentityRevision(), child.getIdentityRevision())
                && (!"GUEST".equals(parent.getCustomerKind())
                        || Objects.equals(parent.getEntityNo(), child.getEntityNo())
                                && Objects.equals(parent.getStoreNo(), child.getStoreNo())
                                && "MANUAL_OFFLINE".equals(child.getAdmissionKind())
                                && "BEAUTY".equals(parent.getCaller())
                                && "BEAUTY".equals(child.getCaller())
                                && Objects.equals(
                                        parent.getOperationNo(), child.getSourceOperationNo()));
    }

    @Override
    public StoreOrderMaterial findOwned(String no, String buyer) {
        if (buyer == null || buyer.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        OrdSubOrder order =
                orders.selectOne(
                        Wrappers.<OrdSubOrder>lambdaQuery()
                                .eq(OrdSubOrder::getSubOrderNo, no)
                                .eq(OrdSubOrder::getUserNo, buyer)
                                .eq(OrdSubOrder::getCustomerKind, "USER")
                                .isNull(OrdSubOrder::getGuestNo)
                                .last("LIMIT 1"));
        if (order == null) {
            return null;
        }
        List<StoreOrderMaterial> material = load(List.of(order), buyer);
        return material.isEmpty() ? null : material.get(0);
    }

    @Override
    public PageData<StoreOrderMaterial> pageOwned(String buyer, StoreOrderQuery query, long page, long size) {
        if (buyer == null || buyer.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var filter =
                Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getUserNo, buyer)
                        .eq(OrdSubOrder::getCustomerKind, "USER")
                        .isNull(OrdSubOrder::getGuestNo)
                        .apply(
                                "EXISTS (SELECT 1 FROM ord_order p WHERE p.order_no=ord_sub_order.order_no"
                                        + " AND p.user_no={0} AND p.deleted=0)",
                                buyer);
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
        return load(rows, buyer, null);
    }

    private List<StoreOrderMaterial> load(
            List<OrdSubOrder> rows, String buyer, CustomerReadScope scope) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> payNos = rows.stream().map(OrdSubOrder::getOrderNo).distinct().toList();
        var parentQuery =
                Wrappers.<OrdOrder>lambdaQuery()
                        .eq(OrdOrder::getTenantNo, "MAIN")
                        .in(OrdOrder::getOrderNo, payNos);
        if (scope == null) {
            parentQuery
                    .eq(OrdOrder::getCustomerKind, "USER")
                    .eq(OrdOrder::getUserNo, buyer)
                    .isNull(OrdOrder::getGuestNo);
        }
        Map<String, OrdOrder> parents =
                payments.selectList(parentQuery).stream()
                        .filter(parent -> scope == null || readableParent(parent, scope))
                        .collect(Collectors.toMap(OrdOrder::getOrderNo, Function.identity()));
        List<OrdSubOrder> owned =
                rows.stream()
                        .filter(row -> matchingParent(parents.get(row.getOrderNo()), row))
                        .toList();
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
