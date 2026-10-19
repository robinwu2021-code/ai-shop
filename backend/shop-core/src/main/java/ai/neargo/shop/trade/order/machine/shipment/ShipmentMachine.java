package ai.neargo.shop.trade.order.machine.shipment;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.statemachine.Guard;
import ai.neargo.shop.trade.order.statemachine.StateMachine;

import java.util.Set;

import static ai.neargo.shop.trade.order.machine.shipment.ShipmentEvent.*;
import static ai.neargo.shop.trade.order.machine.shipment.ShipmentStatus.*;
import static ai.neargo.shop.trade.order.statemachine.Actor.*;

/**
 * 发货单状态机（订单状态机-完整方案 §4）。改单号 = 旧单作废 + 新建一张（新单走 CREATE）。
 */
public final class ShipmentMachine {

    private static final Guard<ShipmentContext> AUTHORIZED = Guard.of(ShipmentContext::authorized, ErrorCode.FORBIDDEN);
    private static final Guard<ShipmentContext> DISPATCHED =
            Guard.of(ShipmentContext::dispatched, ErrorCode.ORDER_STATE_ILLEGAL);
    private static final Guard<ShipmentContext> WAYBILL_VALID =
            Guard.of(ShipmentContext::waybillValid, ErrorCode.BAD_REQUEST);
    private static final Guard<ShipmentContext> WAYBILL_FREE =
            Guard.of(ShipmentContext::waybillFree, ErrorCode.WAYBILL_DUPLICATED);

    public static final StateMachine<ShipmentStatus, ShipmentEvent, ShipmentContext> MACHINE =
            StateMachine.<ShipmentStatus, ShipmentEvent, ShipmentContext>builder("shipment")
                    // H1 建发货单（录运单号）
                    .create().on(CREATE).by(MERCHANT).when(AUTHORIZED, WAYBILL_VALID, WAYBILL_FREE).to(CREATED)
                    .emit("ShipmentCreated").add()
                    // H2 揽收（轨迹）
                    .from(CREATED).on(PICKED_UP).by(CHANNEL).to(SHIPPED).emit("ShipmentPickedUp").add()
                    // H3 承运商签收（轨迹）
                    .from(SHIPPED).on(SIGNED).by(CHANNEL).to(DELIVERED).emit("ShipmentSigned").add()
                    // Actual merchant dispatch can be confirmed before a carrier pickup callback arrives.
                    .from(CREATED).on(RECEIPT_CONFIRMED).by(BUYER, SYSTEM).when(AUTHORIZED, DISPATCHED).to(RECEIVED)
                    .emit("ShipmentReceived").add()
                    // H4 买家确认 / 自动确认收货
                    .from(SHIPPED, DELIVERED).on(RECEIPT_CONFIRMED).by(BUYER, SYSTEM).to(RECEIVED)
                    .emit("ShipmentReceived").add()
                    // H5 改单号：旧单作废（新号先过与建单相同的守卫）
                    // A wrongly reused number can arrive already signed; while the trade order
                    // still fulfills, replacing it must void that false carrier receipt too.
                    .from(CREATED, SHIPPED, DELIVERED).on(CORRECT_WAYBILL).by(MERCHANT, PLATFORM)
                    .when(AUTHORIZED, WAYBILL_VALID, WAYBILL_FREE)
                    .to(VOIDED).emit("ShipmentVoided").add()
                    // H6 未揽收前取消
                    .from(CREATED).on(CANCEL).by(MERCHANT).when(AUTHORIZED).to(VOIDED).emit("ShipmentVoided").add()
                    .build();

    public static final Set<ShipmentStatus> TERMINALS = Set.of(RECEIVED, VOIDED);

    private ShipmentMachine() {
    }
}
