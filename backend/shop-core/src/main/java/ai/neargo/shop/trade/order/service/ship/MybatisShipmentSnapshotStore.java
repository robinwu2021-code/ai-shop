package ai.neargo.shop.trade.order.service.ship;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentContext;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentEvent;
import ai.neargo.shop.trade.order.mapper.ShipmentMapper;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;

/** Current reads are serialized with shipping corrections and receipt by the same child-row lock. */
@Component
public class MybatisShipmentSnapshotStore implements ShipmentSnapshotStore {
    private final ShipmentMapper shipments;
    private final StatusCasMapper orders;
    private final TradeStatusWriter statuses;

    public MybatisShipmentSnapshotStore(ShipmentMapper shipments, StatusCasMapper orders, TradeStatusWriter statuses) {
        this.shipments = shipments;
        this.orders = orders;
        this.statuses = statuses;
    }

    @Override
    public Facts currentLocked(String shipmentNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            OrdShipment candidate = shipments.selectOne(Wrappers.<OrdShipment>lambdaQuery()
                    .eq(OrdShipment::getShipmentNo, shipmentNo).last("LIMIT 1"));
            if (candidate == null) {
                return null;
            }
            OrdSubOrder order = orders.lockOrder(candidate.getSubOrderNo());
            if (order == null) {
                return null;
            }
            List<OrdShipment> live = shipments.selectList(Wrappers.<OrdShipment>lambdaQuery()
                    .eq(OrdShipment::getSubOrderNo, order.getSubOrderNo())
                    .ne(OrdShipment::getStatus, OrdShipment.VOIDED).last("LIMIT 2"));
            if (live.size() != 1 || !shipmentNo.equals(live.getFirst().getShipmentNo())) {
                return null;
            }
            return new Facts(order, live.getFirst());
        });
    }

    @Override
    public void advance(OrdShipment shipment, ShipmentEvent event, String source) {
        statuses.shipment(shipment, event, Actor.CHANNEL, null,
                "物流状态更新（" + sourceLabel(source) + "）", ShipmentContext.ok());
    }

    private static String sourceLabel(String source) {
        return switch (source == null ? "" : source) {
            case "wx", "wx-follow" -> "微信";
            case "kuaidi100" -> "快递100";
            case "yto" -> "圆通";
            default -> "渠道未标明";
        };
    }

    @Override
    public void signedAt(OrdShipment shipment, long at) {
        if (shipment.getSignedAt() != null) {
            return;
        }
        int rows = DataScopeContext.executeWithoutScope(() -> shipments.update(null,
                Wrappers.<OrdShipment>lambdaUpdate().eq(OrdShipment::getId, shipment.getId())
                        .eq(OrdShipment::getStatus, OrdShipment.DELIVERED).isNull(OrdShipment::getSignedAt)
                        .set(OrdShipment::getSignedAt, at)));
        if (rows != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        shipment.setSignedAt(at);
    }

    @Override
    public void verifiedSignedAt(OrdShipment shipment, long at) {
        if (Long.valueOf(at).equals(shipment.getSignedAt())) {
            return;
        }
        var update = Wrappers.<OrdShipment>lambdaUpdate().eq(OrdShipment::getId, shipment.getId())
                .eq(OrdShipment::getStatus, OrdShipment.DELIVERED).set(OrdShipment::getSignedAt, at);
        if (shipment.getSignedAt() == null) {
            update.isNull(OrdShipment::getSignedAt);
        } else {
            update.eq(OrdShipment::getSignedAt, shipment.getSignedAt());
        }
        int rows = DataScopeContext.executeWithoutScope(() -> shipments.update(null, update));
        if (rows != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        shipment.setSignedAt(at);
    }
}
