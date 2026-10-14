package ai.neargo.shop.trade.order.transition;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleContext;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleEvent;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleMachine;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleStatus;
import ai.neargo.shop.trade.order.machine.order.OrderContext;
import ai.neargo.shop.trade.order.machine.order.OrderEvent;
import ai.neargo.shop.trade.order.machine.order.OrderMachine;
import ai.neargo.shop.trade.order.machine.order.OrderStatus;
import ai.neargo.shop.trade.order.statemachine.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 转换门（TDD-订单状态机 AC5）：读 → 算 → CAS → 流水 + 信号。 */
class StateTransitionGateTest {

    /** 内存存储：可预设「CAS 时状态已被别人改走」 */
    static class FakeStore implements StatusStore {
        final Map<String, String> rows = new HashMap<>();
        String stolenTo;
        int casCalls;

        public String load(Aggregate a, String no) {
            return rows.get(no);
        }

        public boolean cas(Aggregate a, String no, String from, String to) {
            casCalls++;
            if (stolenTo != null) {
                rows.put(no, stolenTo);
            }
            if (!from.equals(rows.get(no))) {
                return false;
            }
            rows.put(no, to);
            return true;
        }
    }

    private FakeStore store;
    private List<TransitionLog.Entry> logs;
    private List<String> published;
    private StateTransitionGate gate;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        logs = new ArrayList<>();
        published = new ArrayList<>();
        gate = new StateTransitionGate(store, logs::add, (a, no, s) -> published.addAll(s));
    }

    private Command<AfterSaleEvent, AfterSaleContext> approve() {
        return new Command<>(Aggregate.AFTER_SALE, "AS1", "SUB1", AfterSaleEvent.APPROVE, Actor.MERCHANT,
                "M1", "同意", AfterSaleContext.builder().build());
    }

    @Test
    @DisplayName("正常：CAS 落库、流水一行（含事件 / 从 / 到 / 执行者 / 锚点）、信号发出")
    void applies() {
        store.rows.put("AS1", "APPLIED");
        var fired = gate.apply(AfterSaleMachine.MACHINE, AfterSaleStatus.class, approve());
        assertThat(fired.to()).isEqualTo(AfterSaleStatus.REFUNDING);
        assertThat(store.rows.get("AS1")).isEqualTo("REFUNDING");
        assertThat(logs).singleElement().satisfies(e -> {
            assertThat(e.event()).isEqualTo("APPROVE");
            assertThat(e.from()).isEqualTo("APPLIED");
            assertThat(e.to()).isEqualTo("REFUNDING");
            assertThat(e.anchorNo()).isEqualTo("SUB1");
            assertThat(e.actor()).isEqualTo(Actor.MERCHANT);
        });
        assertThat(published).containsExactly("AfterSaleApproved");
    }

    @Test
    @DisplayName("★★★ 并发被抢先（CAS 0 行）→ CONFLICT，不覆盖、不记流水、不发信号")
    void conflict() {
        store.rows.put("AS1", "APPLIED");
        store.stolenTo = "CLOSED";   // 买家在这一刻撤销了
        assertThatThrownBy(() -> gate.apply(AfterSaleMachine.MACHINE, AfterSaleStatus.class, approve()))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(store.rows.get("AS1")).isEqualTo("CLOSED");
        assertThat(logs).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("单不存在 → NOT_FOUND")
    void notFound() {
        assertThatThrownBy(() -> gate.apply(AfterSaleMachine.MACHINE, AfterSaleStatus.class, approve()))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("状态机拒绝 → 它的码；不写库")
    void machineRejects() {
        store.rows.put("AS1", "REFUNDED");
        assertThatThrownBy(() -> gate.apply(AfterSaleMachine.MACHINE, AfterSaleStatus.class, approve()))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.ORDER_STATE_ILLEGAL);
        assertThat(store.casCalls).isZero();
    }

    @Test
    @DisplayName("★ 原地动作（开始备货）：不 CAS 状态，但记一行流水、发信号")
    void stayRecordsWithoutCas() {
        store.rows.put("SUB1", "WAIT_FULFILL");
        var cmd = new Command<>(Aggregate.ORDER, "SUB1", "SUB1", OrderEvent.PREPARE, Actor.MERCHANT, "M1",
                "商家开始备货", OrderContext.authorizedOnly());
        var fired = gate.apply(OrderMachine.MACHINE, OrderStatus.class, cmd);
        assertThat(fired.changed()).isFalse();
        assertThat(store.casCalls).isZero();
        assertThat(logs).singleElement().satisfies(e -> assertThat(e.event()).isEqualTo("PREPARE"));
        assertThat(published).containsExactly("OrderPrepared");
    }

    @Test
    @DisplayName("创建迁移：只算与记账（from 为空），插入由调用方做")
    void create() {
        var cmd = new Command<>(Aggregate.AFTER_SALE, "AS9", "SUB1", AfterSaleEvent.APPLY, Actor.BUYER, "U1",
                null, AfterSaleContext.builder().build());
        var fired = gate.create(AfterSaleMachine.MACHINE, cmd);
        assertThat(fired.to()).isEqualTo(AfterSaleStatus.APPLIED);
        assertThat(logs).singleElement().satisfies(e -> assertThat(e.from()).isNull());
    }
}
