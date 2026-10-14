package ai.neargo.shop.scenario;

import ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentContext;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentEvent;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentMachine;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentStatus;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.transition.Aggregate;
import ai.neargo.shop.trade.order.transition.Command;
import ai.neargo.shop.trade.order.transition.SignalPublisher;
import ai.neargo.shop.trade.order.transition.StateTransitionGate;
import ai.neargo.shop.trade.order.transition.impl.MybatisStatusStore;
import ai.neargo.shop.trade.order.transition.impl.StatusLogStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 转换门落到真库（H2）：CAS 的 SQL、并发只一方成功、V402 流水列（TDD-订单状态机 AC5）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("状态转换门 · 真库")
class StateTransitionStoreTest {

    @Autowired
    private StatusCasMapper casMapper;
    @Autowired
    private StatusLogMapper logMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private MybatisStatusStore store() {
        return new MybatisStatusStore(casMapper);
    }

    private void newShipment(String no) {
        jdbc.update("insert into ord_shipment (shipment_no, sub_order_no, fulfillment, status, version, deleted) "
                + "values (?, 'SUB-T', 'EXPRESS', 'CREATED', 0, 0)", no);
    }

    @Test
    @DisplayName("★★★ 两个线程同时从 CREATED 写：恰好一个成功 —— 关单撞付款就靠这一条")
    void casIsExclusive() throws Exception {
        newShipment("SHP-CAS-1");
        var pool = Executors.newFixedThreadPool(2);
        Callable<Boolean> a = () -> store().cas(Aggregate.SHIPMENT, "SHP-CAS-1", "CREATED", "SHIPPED");
        Callable<Boolean> b = () -> store().cas(Aggregate.SHIPMENT, "SHP-CAS-1", "CREATED", "VOIDED");
        var fa = pool.submit(a);
        var fb = pool.submit(b);
        boolean ra = fa.get(), rb = fb.get();
        pool.shutdown();
        assertThat(ra ^ rb).as("必须恰好一方成功").isTrue();
        String now = store().load(Aggregate.SHIPMENT, "SHP-CAS-1");
        assertThat(now).isEqualTo(ra ? "SHIPPED" : "VOIDED");
        Map<String, Object> row = jdbc.queryForMap("select version, updated_at from ord_shipment where shipment_no = ?",
                "SHP-CAS-1");
        assertThat(((Number) row.get("VERSION")).longValue()).isEqualTo(1L);
        assertThat(row.get("UPDATED_AT")).as("售后超时按 updated_at 计时，CAS 必须刷新它").isNotNull();
    }

    @Test
    @DisplayName("经门走一次：状态落库 + 流水带聚合 / 事件 / from（V402 列）")
    void gateWritesLog() {
        newShipment("SHP-GATE-1");
        var gate = new StateTransitionGate(store(), new StatusLogStore(logMapper), SignalPublisher.NONE);
        gate.apply(ShipmentMachine.MACHINE, ShipmentStatus.class, new Command<>(Aggregate.SHIPMENT, "SHP-GATE-1",
                "SUB-T", ShipmentEvent.PICKED_UP, Actor.CHANNEL, null, "揽收", ShipmentContext.ok()));
        assertThat(store().load(Aggregate.SHIPMENT, "SHP-GATE-1")).isEqualTo("SHIPPED");
        Map<String, Object> log = jdbc.queryForMap("select sub_order_no, aggregate_type, aggregate_no, event, "
                + "from_status, status, operator_type from ord_status_log where aggregate_no = ?", "SHP-GATE-1");
        assertThat(log).containsEntry("SUB_ORDER_NO", "SUB-T").containsEntry("AGGREGATE_TYPE", "SHIPMENT")
                .containsEntry("EVENT", "PICKED_UP").containsEntry("FROM_STATUS", "CREATED")
                .containsEntry("STATUS", "SHIPPED").containsEntry("OPERATOR_TYPE", "SYSTEM");
    }

    @Test
    @DisplayName("四张表的读 / 写 SQL 都可执行（列名写错 H2 会直接报错）；不存在的单 → null / false")
    void allAggregatesSqlRuns() {
        for (Aggregate a : Aggregate.values()) {
            assertThat(store().load(a, "NO-SUCH")).as(a.name()).isNull();
            assertThat(store().cas(a, "NO-SUCH", "X", "Y")).as(a.name()).isFalse();
        }
    }

    @Test
    @DisplayName("V402：售后状态列放得下 WAIT_OFFLINE_REFUND（19 字符）")
    void statusWidth() {
        Integer len = jdbc.queryForObject("select character_maximum_length from information_schema.columns "
                + "where lower(table_name) = 'ord_after_sale' and lower(column_name) = 'status'", Integer.class);
        assertThat(len).isGreaterThanOrEqualTo("WAIT_OFFLINE_REFUND".length());
    }
}
