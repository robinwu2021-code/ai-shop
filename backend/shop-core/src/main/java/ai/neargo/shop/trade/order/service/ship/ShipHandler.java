package ai.neargo.shop.trade.order.service.ship;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.transition.Operator;
import ai.neargo.shop.trade.order.transition.OrderTransitions;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Whole-order shipping binds physical row IDs, with exact replay and audited package replacement. */
public class ShipHandler {
    private final ShipmentReadStore read;
    private final ShipmentWriteStore write;
    private final OrderTransitions transitions;
    private final Supplier<String> shipmentNoGen;

    public ShipHandler(ShipmentReadStore read, ShipmentWriteStore write,
                       OrderTransitions transitions, Supplier<String> shipmentNoGen) {
        this.read = read;
        this.write = write;
        this.transitions = transitions;
        this.shipmentNoGen = shipmentNoGen;
    }

    /** Caller must hold the child lock and validate paid/authorized/after-sale facts in the same transaction. */
    public String ship(String subOrderNo, String carrier, String rawWaybillNo, List<Long> itemIds, Operator op) {
        OrdSubOrder order = read.findOrder(subOrderNo);
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        String waybill = WaybillNormalizer.normalize(rawWaybillNo);
        if (!WaybillFormat.isValid(waybill)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<OrdItem> items = read.itemsOf(subOrderNo);
        OrdShipment duplicate = read.findActiveWaybill(carrier, waybill);
        if (duplicate != null) {
            return replay(subOrderNo, duplicate, items, itemIds);
        }
        List<OrdShipment> active = read.activeOf(subOrderNo);
        if (active.size() > 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        OrdShipment old = active.isEmpty() ? null : active.getFirst();
        List<OrdItem> rows = items.stream().filter(i -> i.getShipmentNo() == null
                || old != null && old.getShipmentNo().equals(i.getShipmentNo())).toList();
        requireWholeContent(rows, itemIds);
        return create(order, carrier, waybill, rows, old, op, "商家更正运单");
    }

    /** Ops correction reuses whole-order binding and uniqueness without impersonating a merchant. */
    public String correct(String subOrderNo, String carrier, String waybillNo, Operator operator, String reason) {
        OrdSubOrder order = read.findOrder(subOrderNo);
        List<OrdShipment> active = read.activeOf(subOrderNo);
        if (order == null || active.size() != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        String normalized = WaybillNormalizer.normalize(waybillNo);
        if (!WaybillFormat.isValid(normalized)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        OrdShipment duplicate = read.findActiveWaybill(carrier, normalized);
        if (duplicate != null) {
            return replay(subOrderNo, duplicate, read.itemsOf(subOrderNo), List.of());
        }
        OrdShipment old = active.getFirst();
        List<OrdItem> rows = read.itemsOf(subOrderNo).stream()
                .filter(i -> old.getShipmentNo().equals(i.getShipmentNo())).toList();
        requireWholeContent(rows, List.of());
        return create(order, carrier, normalized, rows, old, operator, reason);
    }

    private String replay(String subOrderNo, OrdShipment duplicate, List<OrdItem> items, List<Long> ids) {
        if (!subOrderNo.equals(duplicate.getSubOrderNo())) {
            throw BizException.of(ErrorCode.WAYBILL_DUPLICATED);
        }
        List<OrdItem> bound = items.stream().filter(i -> duplicate.getShipmentNo().equals(i.getShipmentNo())).toList();
        // A whole-order retry cannot silently ignore new unbound content.
        if (items.stream().anyMatch(i -> i.getShipmentNo() == null)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        requireWholeContent(bound, ids);
        return duplicate.getShipmentNo();
    }

    private String create(OrdSubOrder order, String carrier, String waybill, List<OrdItem> rows,
                          OrdShipment old, Operator operator, String reason) {
        String no = shipmentNoGen.get();
        OrdShipment shipment = ShipmentDraftFactory.express(no, order.getSubOrderNo(), order.getStoreNo(),
                carrier, waybill);
        shipment.setTenantNo("MAIN");
        shipment.setVersion(0L);
        write.saveShipment(shipment);
        if (old != null) {
            write.supersede(old, no, operator, reason);
        }
        rows.forEach(item -> ShipmentDraftFactory.bind(item, no));
        write.bindItems(rows);
        if (!OrdSubOrder.FULFILLING.equals(order.getStatus())) {
            transitions.applyMerchantSub(order, OrdSubOrder.FULFILLING, operator, "商家发货：" + waybill);
        }
        write.saveLegacyFields(order, carrier, waybill);
        return no;
    }

    private static void requireWholeContent(List<OrdItem> rows, List<Long> ids) {
        if (rows.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<Long> expected = rows.stream().map(OrdItem::getId).collect(Collectors.toSet());
        if (ids.size() != expected.size() || !expected.equals(Set.copyOf(ids))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
}
