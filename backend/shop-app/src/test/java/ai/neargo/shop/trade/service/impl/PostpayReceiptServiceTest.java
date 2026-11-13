package ai.neargo.shop.trade.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Result;
import ai.neargo.shop.spi.trade.PostpayReceiptPort.Confirm;
import ai.neargo.shop.spi.trade.PostpayReceiptPort.Receipt;
import ai.neargo.shop.spi.trade.PostpayReceiptPort.Root;
import ai.neargo.shop.trade.mapper.PostpayReceiptMapper;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.service.IndustryOrderService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 行业后付单人工实收（TDD-餐饮柜台后付结账 AC2–AC4、AC6）：真实库表与守卫 SQL，只把「按门店读订单」换成给定的应收与版本。
 */
@SpringBootTest
@ActiveProfiles("test")
class PostpayReceiptServiceTest {
    @Autowired PostpayReceiptMapper receipts;
    @Autowired OrderStatusLogMapper logs;
    @Autowired OutboxEventBus events;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tx;
    @Autowired SettleSourcePort settleSources;

    final IndustryOrderService industry = mock(IndustryOrderService.class);
    PostpayReceiptServiceImpl service;
    String run;

    @BeforeEach
    void setup() {
        service = new PostpayReceiptServiceImpl(receipts, industry, logs, events,
                Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC));
        run = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    String no(String prefix) {
        return prefix + run;
    }

    /** 一张后付核心单：订单 WAIT_PAY + 行业来源 POSTPAY；核心读出的应收与版本由 Mock 给出。 */
    String order(String suffix, long due, String store, String mode) {
        String order = no("SO" + suffix);
        jdbc.update("INSERT INTO ord_order(order_no,user_no,customer_kind,identity_revision,pay_amount,goods_amount,"
                + "currency,status,entity_no,store_no,admission_kind,created_at,updated_at,version,deleted,tenant_no)"
                + " VALUES (?,?,'USER',1,?,?,'CNY','WAIT_PAY','E1',?,'LEGACY_FUNDED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,"
                + "0,0,'MAIN')", order, "U9", due, due, store);
        jdbc.update("INSERT INTO ord_industry_order_source(tenant_no,order_no,sub_order_no,entity_no,store_no,buyer_no,"
                + "actor_no,source_system,source_type,source_no,source_version,source_hash,quote_no,confirmation_digest,"
                + "request_hash,order_mode,status,created_at,updated_at) VALUES ('MAIN',?,?,'E1',?,'U9','STAFF','FOOD',"
                + "'BATCH',?,1,?,?,?,?,?,'ACCEPTED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", order, "SUB" + order, store,
                "B" + order, "a".repeat(64), "Q" + order, "b".repeat(64), "c".repeat(64), mode);
        Result r = mock(Result.class);
        when(r.effectiveReceivableMinor()).thenReturn(due);
        when(r.amendmentSourceHash()).thenReturn("v1-" + order);
        when(industry.getForStore(any(), eq("STAFF1"), eq(order))).thenReturn(r);
        return order;
    }

    String record(String suffix, long amount, String method, String biz, String store, String currency, String status) {
        String record = no("OFR" + suffix);
        jdbc.update("INSERT INTO ord_offline_record(record_no,entity_no,store_no,amount_minor,currency,method,biz_no,"
                + "received_at,status,corrected,recorded_by,idem_key_hash,request_hash,created_at,updated_at,version,"
                + "deleted,tenant_no) VALUES (?,'E1',?,?,?,?,?,TIMESTAMP '2026-11-12 11:59:00',?,FALSE,'STAFF1',?,?,"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0,'MAIN')", record, store, amount, currency, method, biz,
                status, ("k" + record + "0".repeat(64)).substring(0, 64), ("r" + record + "0".repeat(64)).substring(0, 64));
        return record;
    }

    String record(String suffix, long amount, String method) {
        return record(suffix, amount, method, no("FS"), "S01", "CNY", "RECORDED");
    }

    Confirm confirm(List<String> orders, long[] dues, List<String> records) {
        var roots = new ArrayList<Root>();
        for (int i = 0; i < orders.size(); i++) {
            roots.add(new Root(orders.get(i), dues[i], "v1-" + orders.get(i)));
        }
        return new Confirm("MAIN", "E1", "S01", no("FS"), "CNY", roots, records, "STAFF1", "key-" + run);
    }

    Receipt confirm(Confirm c) {
        return new TransactionTemplate(tx).execute(s -> service.confirm(c));
    }

    String status(String order) {
        return jdbc.queryForObject("SELECT status FROM ord_order WHERE order_no=?", String.class, order);
    }

    void rejects(Supplier<?> call, ErrorCode code) {
        assertThatThrownBy(call::get).isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(code));
    }

    int receiptsOf(String biz) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ord_postpay_receipt WHERE biz_no=?", Integer.class, biz);
    }

    @Test
    void whole_table_is_paid_in_one_transaction_with_cash_and_external_card() {
        String a = order("A", 3000, "S01", "POSTPAY");
        String b = order("B", 2000, "S01", "POSTPAY");
        var receipt = confirm(confirm(List.of(a, b), new long[] {3000, 2000},
                List.of(record("1", 3000, "CASH"), record("2", 2000, "EXTERNAL_POS"))));
        assertThat(receipt.replayed()).isFalse();
        assertThat(receipt.dueMinor()).isEqualTo(5000);
        assertThat(receipt.changeMinor()).isZero();
        assertThat(receipt.paidOrderNos()).containsExactly(a, b);
        assertThat(status(a)).isEqualTo("PAID");
        assertThat(status(b)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT offline_confirmed_by FROM ord_order WHERE order_no=?", String.class, a))
                .isEqualTo("STAFF1");
        assertThat(jdbc.queryForObject("SELECT receipt_provenance FROM ord_postpay_receipt WHERE biz_no=?",
                String.class, no("FS"))).isEqualTo("POSTPAY_RECEIPT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_status_log WHERE aggregate_no IN (?,?)"
                + " AND event='POSTPAY_RECEIPT'", Integer.class, a, b)).isEqualTo(2);
    }

    /**
     * 钱没经过平台：资金巡检（I1「已付子单都有结算单」）与出账都不能把后付人工实收当成线上收款，
     * 否则每小时会给它补一张带佣金、待分账的结算单（ADR-059 决定 2）。对照组是同形状的普通已付单。
     */
    @Test
    void receipted_orders_never_become_platform_settlement_sources() {
        String a = order("A", 3000, "S01", "POSTPAY");
        String plain = order("P", 1000, "S01", "POSTPAY");
        for (String o : List.of(a, plain)) {
            jdbc.update("INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,fulfillment,"
                    + "pay_amount,goods_amount,freight_amount,created_at,updated_at) VALUES(?,?,'U9','E1','S01',"
                    + "'FULFILLING','STORE_PICKUP',0,0,0,NOW(),NOW())", "SUB" + o, o);
        }
        confirm(confirm(List.of(a), new long[] {3000}, List.of(record("1", 3000, "CASH"))));
        long paidAt = jdbc.queryForObject("SELECT paid_at FROM ord_order WHERE order_no=?", Long.class, a);
        jdbc.update("UPDATE ord_order SET status='PAID',paid_at=? WHERE order_no=?", paidAt, plain);

        var paid = settleSources.paidSubOrdersSince(paidAt, 1000).stream()
                .map(SettleSourcePort.PaidSubOrder::orderNo).toList();
        assertThat(paid).contains(plain).doesNotContain(a);
        assertThat(settleSources.settleSourcesOf(a)).isEmpty();
        assertThat(settleSources.settleSourcesOf(plain)).isNotEmpty();
    }

    @Test
    void replay_returns_the_same_receipt_and_different_content_conflicts() {
        String a = order("A", 3000, "S01", "POSTPAY");
        String cash = record("1", 3000, "CASH");
        var first = confirm(confirm(List.of(a), new long[] {3000}, List.of(cash)));
        var again = confirm(confirm(List.of(a), new long[] {3000}, List.of(cash)));
        assertThat(again.replayed()).isTrue();
        assertThat(again.receiptNo()).isEqualTo(first.receiptNo());
        String extra = record("2", 100, "CASH");
        rejects(() -> confirm(confirm(List.of(a), new long[] {3000}, List.of(cash, extra))), ErrorCode.CONFLICT);
        assertThat(receiptsOf(no("FS"))).isEqualTo(1);
    }

    @Test
    void underpaid_is_rejected_with_the_difference_and_changes_nothing() {
        String a = order("A", 3000, "S01", "POSTPAY");
        assertThatThrownBy(() -> confirm(confirm(List.of(a), new long[] {3000}, List.of(record("1", 2000, "CASH")))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.POSTPAY_RECEIPT_UNDERPAID);
                    assertThat(((BizException) e).args()).containsExactly("10.00 CNY");
                });
        assertThat(status(a)).isEqualTo("WAIT_PAY");
        assertThat(receiptsOf(no("FS"))).isZero();
    }

    @Test
    void cash_may_exceed_as_change_but_card_may_not() {
        String a = order("A", 3000, "S01", "POSTPAY");
        rejects(() -> confirm(confirm(List.of(a), new long[] {3000}, List.of(record("1", 3500, "EXTERNAL_POS")))),
                ErrorCode.POSTPAY_RECEIPT_OVERPAID_NON_CASH);
        var receipt = confirm(confirm(List.of(a), new long[] {3000}, List.of(record("2", 5000, "CASH"))));
        assertThat(receipt.changeMinor()).isEqualTo(2000);
        assertThat(receipt.receivedMinor()).isEqualTo(5000);
    }

    @Test
    void invalid_records_are_rejected() {
        String a = order("A", 3000, "S01", "POSTPAY");
        long[] due = {3000};
        rejects(() -> confirm(confirm(List.of(a), due, List.of(record("1", 3000, "CASH", no("FS"), "S02", "CNY",
                "RECORDED")))), ErrorCode.POSTPAY_RECEIPT_RECORD_INVALID);
        rejects(() -> confirm(confirm(List.of(a), due, List.of(record("2", 3000, "CASH", no("FS"), "S01", "CNY",
                "VOIDED")))), ErrorCode.POSTPAY_RECEIPT_RECORD_INVALID);
        rejects(() -> confirm(confirm(List.of(a), due, List.of(record("3", 3000, "CASH", "FS-OTHER", "S01", "CNY",
                "RECORDED")))), ErrorCode.POSTPAY_RECEIPT_RECORD_INVALID);
        rejects(() -> confirm(confirm(List.of(a), due, List.of(record("4", 3000, "CASH", no("FS"), "S01", "USD",
                "RECORDED")))), ErrorCode.POSTPAY_RECEIPT_RECORD_INVALID);
        rejects(() -> confirm(confirm(List.of(a), due, List.of(no("OFR-MISSING")))),
                ErrorCode.POSTPAY_RECEIPT_RECORD_INVALID);
        assertThat(status(a)).isEqualTo("WAIT_PAY");
    }

    @Test
    void a_record_already_used_by_another_receipt_cannot_be_reused() {
        String a = order("A", 3000, "S01", "POSTPAY");
        String cash = record("1", 3000, "CASH");
        confirm(confirm(List.of(a), new long[] {3000}, List.of(cash)));
        String b = order("B", 3000, "S01", "POSTPAY");
        var other = new Confirm("MAIN", "E1", "S01", no("FS"), "CNY",
                List.of(new Root(b, 3000, "v1-" + b)), List.of(cash), "STAFF1", "key2-" + run);
        // 同一结算单号已有实收、内容不同 → 冲突；换一个结算单号则记账的结算单号对不上 → 无效
        rejects(() -> confirm(other), ErrorCode.CONFLICT);
        var foreign = new Confirm("MAIN", "E1", "S01", no("FS2"), "CNY",
                List.of(new Root(b, 3000, "v1-" + b)), List.of(cash), "STAFF1", "key3-" + run);
        rejects(() -> confirm(foreign), ErrorCode.POSTPAY_RECEIPT_RECORD_INVALID);
        assertThat(status(b)).isEqualTo("WAIT_PAY");
    }

    @Test
    void changed_bill_is_rejected() {
        String a = order("A", 3000, "S01", "POSTPAY");
        var stale = new Confirm("MAIN", "E1", "S01", no("FS"), "CNY", List.of(new Root(a, 3000, "v0-stale")),
                List.of(record("1", 3000, "CASH")), "STAFF1", "key-" + run);
        rejects(() -> confirm(stale), ErrorCode.POSTPAY_RECEIPT_ORDER_CHANGED);
        rejects(() -> confirm(confirm(List.of(a), new long[] {2500}, List.of(record("2", 2500, "CASH")))),
                ErrorCode.POSTPAY_RECEIPT_ORDER_CHANGED);
        assertThat(status(a)).isEqualTo("WAIT_PAY");
    }

    @Test
    void if_any_order_cannot_be_paid_the_whole_table_is_rolled_back() {
        String a = order("A", 3000, "S01", "POSTPAY");
        String b = order("B", 2000, "S01", "POSTPAY");
        // 第二张单在确认前被发起了线上支付：守卫不让它推到已付
        jdbc.update("UPDATE ord_order SET pay_channel='WX' WHERE order_no=?", b);
        rejects(() -> confirm(confirm(List.of(a, b), new long[] {3000, 2000},
                List.of(record("1", 5000, "CASH")))), ErrorCode.POSTPAY_RECEIPT_ORDER_CHANGED);
        assertThat(status(a)).isEqualTo("WAIT_PAY");
        assertThat(status(b)).isEqualTo("WAIT_PAY");
        assertThat(receiptsOf(no("FS"))).isZero();
    }

    @Test
    void another_store_or_a_prepaid_order_is_forbidden() {
        String other = order("X", 3000, "S02", "POSTPAY");
        String prepaid = order("P", 3000, "S01", "PREPAY");
        rejects(() -> confirm(confirm(List.of(other), new long[] {3000}, List.of(record("1", 3000, "CASH")))),
                ErrorCode.FORBIDDEN);
        rejects(() -> confirm(confirm(List.of(prepaid), new long[] {3000}, List.of(record("2", 3000, "CASH")))),
                ErrorCode.FORBIDDEN);
    }
}
