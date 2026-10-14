package ai.neargo.shop.trade.order.machine;

import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleEvent;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleMachine;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleStatus;
import ai.neargo.shop.trade.order.machine.order.OrderMachine;
import ai.neargo.shop.trade.order.machine.order.OrderStatus;
import ai.neargo.shop.trade.order.machine.payment.PaymentMachine;
import ai.neargo.shop.trade.order.machine.payment.PaymentStatus;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentMachine;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentStatus;
import ai.neargo.shop.trade.order.statemachine.Invariants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 不变式 I1–I4（TDD-订单状态机 AC4）。I1 就是「退款永不自动」的机器级保证。 */
class MachineInvariantsTest {

    @Test
    @DisplayName("★★★ I1 退款永不自动：系统进入「钱开始走」只有 TRADE_VOID（交易不成立）一条路")
    void systemNeverDecidesRefund() {
        assertThat(Invariants.systemMoneyEntries(AfterSaleMachine.MACHINE, AfterSaleMachine.MONEY_MOVING,
                Set.of(AfterSaleEvent.TRADE_VOID))).isEmpty();
    }

    @Test
    @DisplayName("I3 终态没有改变状态的出边")
    void terminalsAreFinal() {
        assertThat(Invariants.terminalExits(PaymentMachine.MACHINE, PaymentMachine.TERMINALS)).isEmpty();
        assertThat(Invariants.terminalExits(OrderMachine.MACHINE, OrderMachine.TERMINALS)).isEmpty();
        assertThat(Invariants.terminalExits(ShipmentMachine.MACHINE, ShipmentMachine.TERMINALS)).isEmpty();
        assertThat(Invariants.terminalExits(AfterSaleMachine.MACHINE, AfterSaleMachine.TERMINALS)).isEmpty();
    }

    @Test
    @DisplayName("I4 每个状态可达、每个非终态都有出路（不会卡死）")
    void reachableAndNoDeadEnds() {
        var pay = EnumSet.allOf(PaymentStatus.class);
        assertThat(Invariants.unreachable(PaymentMachine.MACHINE, pay, Set.of())).isEmpty();
        assertThat(Invariants.deadEnds(PaymentMachine.MACHINE, pay, PaymentMachine.TERMINALS)).isEmpty();

        var order = EnumSet.allOf(OrderStatus.class);
        assertThat(Invariants.unreachable(OrderMachine.MACHINE, order, Set.of(OrderStatus.WAIT_PAY))).isEmpty();
        assertThat(Invariants.deadEnds(OrderMachine.MACHINE, order, OrderMachine.TERMINALS)).isEmpty();

        var ship = EnumSet.allOf(ShipmentStatus.class);
        assertThat(Invariants.unreachable(ShipmentMachine.MACHINE, ship, Set.of())).isEmpty();
        assertThat(Invariants.deadEnds(ShipmentMachine.MACHINE, ship, ShipmentMachine.TERMINALS)).isEmpty();

        var as = EnumSet.allOf(AfterSaleStatus.class);
        assertThat(Invariants.unreachable(AfterSaleMachine.MACHINE, as, Set.of())).isEmpty();
        assertThat(Invariants.deadEnds(AfterSaleMachine.MACHINE, as, AfterSaleMachine.TERMINALS)).isEmpty();
    }
}
