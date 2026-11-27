package ai.neargo.shop.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizDetailedException;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.OutboxPump;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.SysOutboxMapper;
import ai.neargo.shop.idem.port.RequestIdempotencyPortImpl;
import ai.neargo.shop.invoicebridge.InvoiceAutoIssueConsumer;
import ai.neargo.shop.invoicebridge.port.SubOrderInvoiceSourcePortImpl;
import ai.neargo.shop.numbering.DocumentNumberFormatter;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.ReservedDocumentNumber;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuPricingPolicyMapper;
import ai.neargo.shop.product.port.GoodsPricingPolicyPortImpl;
import ai.neargo.shop.product.service.impl.GoodsPricingPolicyServiceImpl;
import ai.neargo.shop.spi.invoice.InvoicePort;
import ai.neargo.shop.spi.invoice.InvoiceSourcePort.Line;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.pay.PaidCollectionPort;
import ai.neargo.shop.spi.pay.PaidCollectionPort.PaidCollection;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.product.GoodsQueryPort.SkuSnapshot;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.spi.marketing.PosActivityPort;
import ai.neargo.shop.spi.member.PosSaleMemberPort;
import ai.neargo.shop.trade.mapper.PosActivitySaleMapper;
import ai.neargo.shop.trade.mapper.OfflineRecordMappers.EventMapper;
import ai.neargo.shop.trade.mapper.OfflineRecordMappers.RecordMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.InvoiceDataFillMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.PriceQuoteLineMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StoreCollectionMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.service.OfflineRecordService;
import ai.neargo.shop.trade.service.StoreOrderService;
import ai.neargo.shop.trade.service.StoreOrderService.AdjustmentCmd;
import ai.neargo.shop.trade.service.StoreOrderService.BuyerCmd;
import ai.neargo.shop.trade.service.StoreOrderService.CreateCmd;
import ai.neargo.shop.trade.service.StoreOrderService.FillCmd;
import ai.neargo.shop.trade.service.StoreOrderService.FillLineCmd;
import ai.neargo.shop.trade.service.StoreOrderService.LineCmd;
import ai.neargo.shop.trade.service.StoreOrderService.MissingView;
import ai.neargo.shop.trade.service.StoreOrderService.Scope;
import ai.neargo.shop.trade.service.StoreOrderService.StoreSaleView;
import ai.neargo.shop.trade.service.StoreOrderSettlementService;
import ai.neargo.shop.trade.service.impl.OfflineRecordServiceImpl;
import ai.neargo.shop.trade.service.impl.StoreOrderServiceImpl;
import ai.neargo.shop.trade.service.impl.StoreOrderSettlementServiceImpl;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 门店零售单在真实 MySQL 9 上的验收（TDD-统一订单与开票资料 §5，P1：AC1–6、AC9、AC10、AC15；
 * P2：AC6 门店默认税别、AC7 / AC16 收款挂单与付清、AC8 付清自动开票、AC11 常用品项）。
 *
 * <p><b>表结构从哪来</b>：平台迁移不能在空 MySQL 上从零重放（V1–V574 是 MariaDB 方言，见 run-local），所以这里
 * 从本机模板库（默认 {@code ai_shop}，可用 {@code STORE_ORDER_MYSQL_TEMPLATE} 指定 V691 临时库）只读复制表结构到随机库，
 * 再执行本次的 V692 / V693 / V694 —— 迁移在生产同形的表与 CHECK 名上真跑一遍，H2 证明不了这些。
 *
 * <p>连接串沿用 {@code COMMERCE_MYSQL_URL/USER/PASSWORD}（只认 127.0.0.1），没配或本机没有模板库时跳过。
 * 商品快照与发票域用替身：前者不是本次改动，后者在 shop-app 只能经 spi 使用（InvoiceOptionalTest）。
 * 现金记录走真实 {@code OfflineRecordServiceImpl}，事件走真实 {@code OutboxEventBus}（写 {@code sys_outbox}），
 * 自动开票走真实 {@code InvoiceAutoIssueConsumer}；支付域的「已成功支付单」用替身（{@link FakeCollections}）。
 */
@SpringJUnitConfig(StoreOrderMysqlTest.Config.class)
class StoreOrderMysqlTest {

    static final Scope CLERK = new Scope("E1", "S1", "U1", false, false);
    static final Scope MANAGER = new Scope("E1", "S1", "U2", true, true);
    static final BuyerCmd CARRIER = new BuyerCmd("CARRIER", null, null, "3J0002", "/ABC+123", null);

    @Autowired PosActivityPort activities;
    @Autowired PosSaleMemberPort members;
    @Autowired PosActivitySaleMapper saleFacts;
    @Autowired StoreOrderService orders;
    @Autowired SubOrderInvoiceSourcePortImpl invoiceSource;
    @Autowired FakeInvoices invoices;
    @Autowired JdbcTemplate jdbc;
    @Autowired OfflineRecordService cash;
    @Autowired StoreOrderSettlementService settlements;
    @Autowired FakeCollections payOrders;
    @Autowired InvoiceAutoIssueConsumer autoIssue;
    @Autowired SysOutboxMapper outbox;

    @BeforeEach
    void reset() {
        invoices.buyers.clear();
        invoices.active.clear();
        invoices.items.clear();
        invoices.settings = new InvoicePort.StoreSettings(null, "MANUAL");
        payOrders.byNo.clear();
    }

    // ------------------------------------------------------------------ AC1 / AC2 / AC3 / AC4

    @Test
    void ac1MixedOrderFreezesSkuDataAndListsExactlyWhatTheCustomLineMisses() {
        var view = create(CLERK, "POS-AC1", "K-AC1", List.of(sku("SKU-A", 1, 2500), custom(1, 1000, null, null)),
                List.of(new AdjustmentCmd("DISCOUNT", "整單 9 折", -350)), CARRIER);
        assertEquals("AWAITING_PAYMENT", view.status());
        assertEquals(3150, view.totalMinor());
        assertEquals(0, view.paidMinor());
        assertTrue(view.orderNo().startsWith("OD") && view.subOrderNo().startsWith("OS"));
        var l1 = view.lines().get(0);
        assertEquals(List.of(1, "Test Tissue", "TAXABLE", 2500L, true),
                List.of(l1.lineNo(), l1.name(), l1.taxType(), l1.amountMinor(), l1.frozen()));
        var l2 = view.lines().get(1);
        assertEquals(List.of(2, 1000L, false), List.of(l2.lineNo(), l2.amountMinor(), l2.frozen()));
        assertNull(l2.name());
        assertNull(l2.taxType());
        assertEquals("NEEDS_INFO", view.invoiceReadiness().state());
        assertEquals(List.of(new MissingView(2, List.of("name", "taxType"))), view.invoiceReadiness().missing());
        assertTrue(view.invoiceReadiness().buyerSet());
        assertNull(view.invoiceReadiness().invoiceNo());
        // 落库口径：新的一支当事人组合 + 整单优惠落子单 discount_amount + 行实付分摊快照
        var row = jdbc.queryForMap("SELECT customer_kind,admission_kind,caller,user_no,client_order_no,channel,"
                + "goods_amount,discount_amount,pay_amount,status FROM ord_order WHERE order_no=?", view.orderNo());
        assertEquals(List.of("WALK_IN", "STORE_SALE", "POS", "POS-AC1", "POS", 3500L, 350L, 3150L, "WAIT_PAY"),
                List.of(row.get("customer_kind"), row.get("admission_kind"), row.get("caller"),
                        row.get("client_order_no"), row.get("channel"), row.get("goods_amount"),
                        row.get("discount_amount"), row.get("pay_amount"), row.get("status")));
        assertNull(row.get("user_no"));
        assertEquals(List.of("SKU|STANDARD|Test Tissue|GOODS|2250", "CUSTOM|null|null|null|900"),
                jdbc.queryForList("SELECT CONCAT_WS('|',line_kind,IFNULL(tax_class,'null'),IFNULL(invoice_name,'null'),"
                        + "IFNULL(invoice_data_source,'null'),paid_share_minor) FROM ord_item WHERE sub_order_no=? "
                        + "ORDER BY id", String.class, view.subOrderNo()));
        assertEquals("NEEDS_INFO", orders.get(CLERK, view.orderNo()).invoiceReadiness().state(), "详情同口径");
    }

    @Test
    void ac2SkuWithoutStoreTaxClassIsPendingAndRepriceNeedsPermission() {
        var view = create(CLERK, "POS-AC2", "K-AC2", List.of(sku("SKU-X", 2, 600)), List.of(), null);
        assertEquals(List.of(new MissingView(1, List.of("taxType"))), view.invoiceReadiness().missing(),
                "未设课税别（或 OUT_OF_SCOPE）不猜，按待补");
        assertFalse(view.invoiceReadiness().buyerSet());
        assertCode(ErrorCode.BIZ_ROLE_FORBIDDEN,
                () -> create(CLERK, "POS-AC2-R", "K-AC2-R", List.of(sku("SKU-A", 1, 2000)), List.of(), null));
        assertEquals(0, count("SELECT COUNT(*) FROM ord_order WHERE client_order_no='POS-AC2-R'"));
        assertEquals(2000, create(MANAGER, "POS-AC2-R", "K-AC2-R2", List.of(sku("SKU-A", 1, 2000)), List.of(), null)
                .totalMinor());
        assertCode(ErrorCode.NOT_FOUND,
                () -> create(CLERK, "POS-AC2-N", "K-AC2-N", List.of(sku("SKU-OTHER-STORE", 1, 100)), List.of(), null));
        assertCode(ErrorCode.STORE_ORDER_TOTAL_INVALID, () -> create(CLERK, "POS-AC2-T", "K-AC2-T",
                List.of(custom(1, 100, "x", "TAXABLE")), List.of(new AdjustmentCmd("DISCOUNT", null, -101)), null));
        assertCode(ErrorCode.STORE_ORDER_STORE_ITEM_UNAVAILABLE, () -> create(CLERK, "POS-AC2-I", "K-AC2-I",
                List.of(new LineCmd("CUSTOM", null, 1, 100, null, null, "SI-1")), List.of(), null));
    }

    @Test
    void ac3CustomLineWithNameAndTaxTypeIsFrozenAtOnce() {
        var view = create(CLERK, "POS-AC3", "K-AC3", List.of(custom(2, 500, "清潔用品", "TAXABLE")), List.of(), null);
        assertEquals("NOT_PAID", view.invoiceReadiness().state());
        assertTrue(view.invoiceReadiness().missing().isEmpty());
        assertTrue(view.lines().getFirst().frozen());
        assertEquals("ENTERED", jdbc.queryForObject("SELECT invoice_data_source FROM ord_item WHERE sub_order_no=?",
                String.class, view.subOrderNo()));
    }

    // ------------------------------------------------------------------ AC15 幂等

    @Test
    void ac15SameClientOrderNoReturnsTheOriginalEvenWithANewKey() {
        var first = create(CLERK, "POS-AC15", "K-AC15", List.of(custom(1, 700, null, null)), List.of(), null);
        var offline = create(CLERK, "POS-AC15", "K-AC15-UPLOAD", List.of(custom(1, 700, null, null)), List.of(), null);
        assertEquals(first.orderNo(), offline.orderNo());
        assertEquals(1, count("SELECT COUNT(*) FROM ord_order WHERE client_order_no='POS-AC15'"));
        assertCode(ErrorCode.CONFLICT,
                () -> create(CLERK, "POS-AC15-OTHER", "K-AC15", List.of(custom(1, 1, null, null)), List.of(), null));
    }

    @Test
    void invalidBuyerRollsBackTheWholeOrder() {
        assertCode(ErrorCode.INVOICE_BUYER_INVALID, () -> create(CLERK, "POS-BUYER", "K-BUYER",
                List.of(custom(1, 100, null, null)), List.of(), new BuyerCmd("CARRIER", null, null, "3J0002", "bad", null)));
        assertEquals(0, count("SELECT COUNT(*) FROM ord_order WHERE client_order_no='POS-BUYER'"));
    }

    // ------------------------------------------------------------------ AC5 / AC6 补资料

    @Test
    void ac5FillCompletesTheOrderButUnpaidStaysNotPaidAndEveryFieldIsAudited() {
        var view = create(CLERK, "POS-AC5", "K-AC5", List.of(sku("SKU-A", 1, 2500), custom(1, 1000, null, null)),
                List.of(), null);
        var filled = orders.fillInvoiceData(CLERK, view.orderNo(), new FillCmd("F-AC5",
                List.of(new FillLineCmd(2, "清潔用品", "TAXABLE", null)),
                new BuyerCmd("COMPANY", "50766768", "創科系統有限公司", null, null, null), "輸入金額補品名"));
        assertEquals("NOT_PAID", filled.invoiceReadiness().state());
        assertTrue(filled.invoiceReadiness().missing().isEmpty());
        assertTrue(filled.invoiceReadiness().buyerSet());
        assertTrue(filled.lines().get(1).frozen());
        assertEquals(List.of("2|NAME|清潔用品|U1|輸入金額補品名", "2|TAX_CLASS|STANDARD|U1|輸入金額補品名",
                        "0|BUYER|COMPANY|U1|輸入金額補品名"),
                jdbc.queryForList("SELECT CONCAT_WS('|',line_no,field_code,new_value,operator_no,reason) "
                        + "FROM ord_invoice_data_fill WHERE sub_order_no=? ORDER BY id", String.class, view.subOrderNo()));
        assertEquals("FILLED", jdbc.queryForObject("SELECT invoice_data_source FROM ord_item WHERE sub_order_no=? "
                + "ORDER BY id DESC LIMIT 1", String.class, view.subOrderNo()));
        // 同一幂等键再送：原样返回，不再记稽核
        orders.fillInvoiceData(CLERK, view.orderNo(), new FillCmd("F-AC5",
                List.of(new FillLineCmd(2, "清潔用品", "TAXABLE", null)), null, "retry"));
        assertEquals(3, count("SELECT COUNT(*) FROM ord_invoice_data_fill WHERE sub_order_no='" + view.subOrderNo() + "'"));
        // 已补过（或 SKU 冻结）的字段给不同值：冻结
        assertCode(ErrorCode.INVOICE_DATA_FROZEN, () -> orders.fillInvoiceData(CLERK, view.orderNo(),
                new FillCmd("F-AC5-2", List.of(new FillLineCmd(2, "別的名字", null, null)), null, null)));
        assertCode(ErrorCode.INVOICE_DATA_FROZEN, () -> orders.fillInvoiceData(CLERK, view.orderNo(),
                new FillCmd("F-AC5-3", List.of(new FillLineCmd(1, null, "EXEMPT", null)), null, null)));
        assertCode(ErrorCode.INVOICE_DATA_FROZEN, () -> orders.fillInvoiceData(CLERK, view.orderNo(),
                new FillCmd("F-AC5-4", List.of(), CARRIER, null)));
    }

    @Test
    void ac6ZeroRatedOrExemptOnACustomLineNeedsTheOverridePermission() {
        assertCode(ErrorCode.BIZ_ROLE_FORBIDDEN, () -> create(CLERK, "POS-AC6", "K-AC6",
                List.of(custom(1, 100, "外銷品", "ZERO_RATED")), List.of(), null));
        var view = create(CLERK, "POS-AC6", "K-AC6-2", List.of(custom(1, 100, "書籍", null)), List.of(), null);
        assertCode(ErrorCode.BIZ_ROLE_FORBIDDEN, () -> orders.fillInvoiceData(CLERK, view.orderNo(),
                new FillCmd("F-AC6", List.of(new FillLineCmd(1, null, "EXEMPT", null)), null, null)));
        assertEquals("EXEMPT", orders.fillInvoiceData(MANAGER, view.orderNo(),
                new FillCmd("F-AC6-M", List.of(new FillLineCmd(1, null, "EXEMPT", null)), null, null)).lines().getFirst().taxType());
    }

    // ------------------------------------------------------------------ 取消

    @Test
    void cancelOnlyUnpaidAndIsRepeatable() {
        var view = create(CLERK, "POS-CANCEL", "K-CANCEL", List.of(custom(1, 100, null, null)), List.of(), null);
        assertEquals("CANCELLED", orders.cancel(CLERK, view.orderNo(), "客人不要了").status());
        assertEquals("CANCELLED", orders.cancel(CLERK, view.orderNo(), null).status());
        assertEquals("CANCELLED|客人不要了", jdbc.queryForObject("SELECT CONCAT_WS('|',status,cancel_reason) FROM ord_order "
                + "WHERE order_no=?", String.class, view.orderNo()));
        var paid = create(CLERK, "POS-CANCEL-PAID", "K-CANCEL-PAID", List.of(custom(1, 100, null, null)), List.of(), null);
        markPaid(paid);
        assertCode(ErrorCode.STORE_ORDER_STATE_INVALID, () -> orders.cancel(CLERK, paid.orderNo(), null));
        assertCode(ErrorCode.NOT_FOUND, () -> orders.get(new Scope("E1", "S2", "U1", false, false), view.orderNo()));
    }

    // ------------------------------------------------------------------ AC9 / AC10 发票来源

    @Test
    void ac10PaidMixedTaxOrderSplitsTheDiscountByLargestRemainder() {
        var view = create(MANAGER, "POS-AC10", "K-AC10",
                List.of(sku("SKU-A", 1, 2500), custom(1, 1000, "外銷品", "ZERO_RATED")),
                List.of(new AdjustmentCmd("DISCOUNT", "整單折扣", -333)), null);
        markPaid(view);
        assertEquals("READY", orders.get(CLERK, view.orderNo()).invoiceReadiness().state());
        var source = invoiceSource.load(new InvoicePort.Scope("MAIN", "E1", "S1", "U1"), "SUB_ORDER", view.subOrderNo());
        assertEquals(List.of("Test Tissue|2500|TAXABLE", "外銷品|1000|ZERO", "折扣|-238|TAXABLE", "折扣|-95|ZERO"),
                source.lines().stream().map(l -> l.name() + "|" + l.amountMinor() + "|" + l.taxType()).toList());
        assertEquals(3167, source.totalMinor());
        assertEquals(source.totalMinor(), source.lines().stream().mapToLong(Line::amountMinor).sum());
        assertNull(source.buyerUserNo());
        // 已开票：准备度 ISSUED 并带发票号，开票资料锁死
        invoices.active.put(view.subOrderNo(), new InvoicePort.InvoiceReceipt("IV-1", "TW_EGUI", "AB00000001",
                "11512", "1234", "ISSUED", false, "TWD", 3167));
        var issued = orders.get(CLERK, view.orderNo()).invoiceReadiness();
        assertEquals(List.of("ISSUED", "IV-1"), List.of(issued.state(), issued.invoiceNo()));
        assertCode(ErrorCode.INVOICE_DATA_LOCKED, () -> orders.fillInvoiceData(CLERK, view.orderNo(),
                new FillCmd("F-AC10", List.of(), CARRIER, null)));
    }

    @Test
    void ac9NotReadySourceCarriesTheSameMissingListIn26002() {
        var view = create(CLERK, "POS-AC9", "K-AC9", List.of(sku("SKU-A", 1, 2500), custom(1, 1000, "未定稅別", null)),
                List.of(), null);
        markPaid(view);
        assertEquals("NEEDS_INFO", orders.get(CLERK, view.orderNo()).invoiceReadiness().state());
        var e = assertThrows(BizDetailedException.class, () -> invoiceSource.load(
                new InvoicePort.Scope("MAIN", "E1", "S1", "U1"), "SUB_ORDER", view.subOrderNo()));
        assertEquals(ErrorCode.INVOICE_SOURCE_NOT_READY, e.errorCode());
        var expected = List.of(Map.of("lineNo", 2, "fields", List.of("taxType")));
        assertEquals(expected, e.details().get("missing"));
        var rendered = new GlobalExceptionHandler().onBizDetailed(e);
        assertEquals(26002, rendered.code());
        assertEquals(expected, rendered.data().get("missing"));
    }


    // ------------------------------------------------------------------ P2 · AC7 / AC16 收款挂单与付清

    @Test
    void ac7CashForTheFullTotalMarksPaidPublishesOrderPaidAndTurnsReady() {
        var view = create(CLERK, "POS-P2-CASH", "K-P2-CASH", List.of(custom(1, 1000, "清潔用品", "TAXABLE")), List.of(),
                null);
        assertEquals("NOT_PAID", view.invoiceReadiness().state());
        var record = pay(view, 1000);
        assertEquals(view.subOrderNo(), record.orderNo());
        var paid = orders.get(CLERK, view.orderNo());
        assertEquals(List.of("PAID", 1000L, "READY"),
                List.of(paid.status(), paid.paidMinor(), paid.invoiceReadiness().state()));
        var row = jdbc.queryForMap("SELECT o.status,o.pay_channel,o.paid_at,s.status sub_status,s.completed_at "
                + "FROM ord_order o JOIN ord_sub_order s ON s.order_no=o.order_no WHERE o.order_no=?", view.orderNo());
        assertEquals(List.of("PAID", "OFFLINE", "COMPLETED"),
                List.of(row.get("status"), row.get("pay_channel"), row.get("sub_status")));
        assertTrue(row.get("paid_at") != null && row.get("completed_at") != null);
        assertEquals(List.of("ORDER:" + view.orderNo() + ":ORDER_PAID", "SUB_ORDER:" + view.subOrderNo()
                + ":SUB_ORDER_PAID"), events(view));
        String payload = orderPaid(view).getPayload();
        assertTrue(payload.contains("\"payAmount\":1000") && payload.contains("\"payChannel\":\"OFFLINE\""), payload);
    }

    @Test
    void ac16SplitCashAndCollectionSettlesOnlyWhenTheSumReachesTheTotal() {
        var view = create(CLERK, "POS-P2-SPLIT", "K-P2-SPLIT", List.of(custom(1, 1000, "清潔用品", "TAXABLE")),
                List.of(), null);
        pay(view, 400);
        var partial = orders.get(CLERK, view.orderNo());
        assertEquals(List.of("AWAITING_PAYMENT", 400L, "NOT_PAID"),
                List.of(partial.status(), partial.paidMinor(), partial.invoiceReadiness().state()));
        assertEquals(List.of(), events(view), "部分付款不发事件");
        assertCode(ErrorCode.STORE_ORDER_STATE_INVALID, () -> orders.cancel(CLERK, view.orderNo(), null));
        assertTrue(settlements.attachCollection(collection("PYO-SPLIT-1", "S1", view.subOrderNo(), "TWD", 600)));
        var paid = orders.get(CLERK, view.orderNo());
        assertEquals(List.of("PAID", 1000L), List.of(paid.status(), paid.paidMinor()));
        assertEquals("MIXED", jdbc.queryForObject("SELECT pay_channel FROM ord_order WHERE order_no=?", String.class,
                view.orderNo()));
        assertEquals(2, events(view).size());
    }

    @Test
    void overpaymentIsAcceptedAndRecordedAsPaidMinor() {
        var view = create(CLERK, "POS-P2-OVER", "K-P2-OVER", List.of(custom(1, 1000, "清潔用品", "TAXABLE")), List.of(),
                null);
        pay(view, 1200);
        var paid = orders.get(CLERK, view.orderNo());
        assertEquals(List.of("PAID", 1000L, 1200L), List.of(paid.status(), paid.totalMinor(), paid.paidMinor()));
        // 付清后再来的现金不挂（挂上就作废不掉）；后到的收款码照记（钱已经收了），不再转状态、不再发事件
        assertCode(ErrorCode.STORE_ORDER_STATE_INVALID, () -> pay(view, 100));
        assertTrue(settlements.attachCollection(collection("PYO-LATE", "S1", view.subOrderNo(), "TWD", 50)));
        assertEquals(1250, orders.get(CLERK, view.orderNo()).paidMinor());
        assertEquals(2, events(view).size());
    }

    @Test
    void voidingCashOnAnUnpaidOrderRecomputesButOnAPaidOrderIsRejected() {
        var view = create(CLERK, "POS-P2-VOID", "K-P2-VOID", List.of(custom(1, 1000, "清潔用品", "TAXABLE")), List.of(),
                null);
        var partial = pay(view, 300);
        cash.voidRecord(cashScope(), partial.recordNo(), new OfflineRecordService.VoidCmd("輸錯", partial.version()));
        assertEquals(0, orders.get(CLERK, view.orderNo()).paidMinor());
        // 更正成更大的金额：就此付清
        var small = pay(view, 500);
        var corrected = cash.correct(cashScope(), small.recordNo(), new OfflineRecordService.CorrectCmd(1000, "TWD",
                "CASH", null, "少打一個零", small.version()));
        assertEquals("PAID", orders.get(CLERK, view.orderNo()).status());
        assertCode(ErrorCode.STORE_ORDER_PAYMENT_LOCKED, () -> cash.voidRecord(cashScope(), small.recordNo(),
                new OfflineRecordService.VoidCmd("想作廢", corrected.version())));
        assertCode(ErrorCode.STORE_ORDER_PAYMENT_LOCKED, () -> cash.correct(cashScope(), small.recordNo(),
                new OfflineRecordService.CorrectCmd(900, "TWD", "CASH", null, "改", corrected.version())));
        assertEquals("RECORDED|1000", jdbc.queryForObject("SELECT CONCAT_WS('|',status,amount_minor) "
                + "FROM ord_offline_record WHERE record_no=?", String.class, small.recordNo()));
    }

    @Test
    void posQuoteLinesPaymentMembershipAndRepeatedCashRefundUseOriginalOrder() {
        when(members.requireUser("MAIN","E1","S1","M1")).thenReturn("U-M1");
        when(members.active("MAIN","E1","S1","M1")).thenReturn(true);
        when(activities.apply(any(),any(),any(),any(),any(),any(),any(),any(),org.mockito.ArgumentMatchers.anyLong(),any()))
                .thenReturn(new PosActivityPort.Use("USE-POS","AV1",new long[]{2500,0},true));
        var cmd=new CreateCmd("K-EFFECT","POS-EFFECT","POS","TWD",
                List.of(sku("SKU-A",1,2500),custom(1,500,"包裝","TAXABLE")),
                List.of(new AdjustmentCmd("ACTIVITY",null,-200,"ACT-POS")),null,null,1,"M1");
        var order=orders.create(CLERK,cmd);
        assertEquals(order.orderNo(),orders.create(CLERK,cmd).orderNo());
        assertEquals(1,count("SELECT COUNT(*) FROM ord_pos_activity_line WHERE apply_no='USE-POS' AND activity_version_no='AV1' AND discount_minor=200"));
        var receipt=pay(order,2800);
        var facts=saleFacts.lines("MAIN","E1","S1","ACT-POS",0,Long.MAX_VALUE,java.time.LocalDateTime.now().plusYears(1))
                .stream().filter(l->order.subOrderNo().equals(l.subNo())).toList();
        assertEquals(2,facts.size());
        assertTrue(facts.getFirst().memberAtPayment());
        assertEquals(2800L,facts.stream().mapToLong(PosActivitySaleMapper.Line::paid).sum());
        assertEquals(200L,facts.stream().mapToLong(PosActivitySaleMapper.Line::discount).sum());
        var refund=new OfflineRecordService.RefundCmd(2800,"整單退",receipt.version());
        cash.refundCash(cashScope(),receipt.recordNo(),refund);
        cash.refundCash(cashScope(),receipt.recordNo(),refund);
        assertTrue(saleFacts.lines("MAIN","E1","S1","ACT-POS",0,Long.MAX_VALUE,java.time.LocalDateTime.now().plusYears(1))
                .stream().filter(l->order.subOrderNo().equals(l.subNo())).allMatch(PosActivitySaleMapper.Line::cashReturned));
    }

    @Test
    void cashRefundIsFullAuditedAndIdempotent() {
        var order = create(CLERK, "POS-FULL-REFUND", "K-FULL-REFUND",
                List.of(custom(1, 1000, "清潔用品", "TAXABLE")), List.of(), null);
        var received = pay(order, 1000);
        var partial = new OfflineRecordService.RefundCmd(500, "測試", received.version());
        var full = new OfflineRecordService.RefundCmd(1000, "測試", received.version());
        assertCode(ErrorCode.STORE_ORDER_STATE_INVALID, () -> cash.refundCash(cashScope(), received.recordNo(), partial));
        invoices.active.put(order.subOrderNo(), new InvoicePort.InvoiceReceipt("IV-REFUND", "TW_EGUI",
                "AB00000001", "11512", "1234", "ISSUED", false, "TWD", 1000));
        assertCode(ErrorCode.INVOICE_DATA_LOCKED, () -> cash.refundCash(cashScope(), received.recordNo(), full));
        invoices.active.remove(order.subOrderNo());

        var refunded = cash.refundCash(cashScope(), received.recordNo(), full);
        var replay = cash.refundCash(cashScope(), received.recordNo(), full);
        assertEquals("REFUNDED", refunded.status());
        assertEquals(1000, refunded.amountMinor());
        assertEquals(refunded, replay);
        assertEquals(1, count("SELECT COUNT(*) FROM ord_offline_record_event WHERE action='REFUND' AND record_no='"
                + received.recordNo() + "'"));
        var after = orders.get(CLERK, order.orderNo());
        assertEquals("REFUNDED", after.status());
        assertEquals(0, after.paidMinor());
        assertEquals("REFUNDED|1000|FULL", jdbc.queryForObject(
                "SELECT CONCAT_WS('|',status,refunded_minor,refund_progress) FROM ord_sub_order WHERE sub_order_no=?",
                String.class, order.subOrderNo()));
        assertCode(ErrorCode.INVOICE_SOURCE_NOT_READY, () -> invoices.source.load(
                new InvoicePort.Scope("MAIN", "E1", "S1", "U1"), "SUB_ORDER", order.subOrderNo()));
    }

    @Test
    void cashInAnotherCurrencyOrOnACancelledOrderIsRejectedAndNothingIsRecorded() {
        var usd = orders.create(CLERK, new CreateCmd("K-P2-USD", "POS-P2-USD", "POS", "USD",
                List.of(custom(1, 1000, "x", "TAXABLE")), List.of(), null, null));
        assertCode(ErrorCode.STORE_ORDER_CURRENCY_MISMATCH, () -> pay(usd, 1000));
        var cancelled = create(CLERK, "POS-P2-CNL", "K-P2-CNL", List.of(custom(1, 1000, "x", "TAXABLE")), List.of(),
                null);
        orders.cancel(CLERK, cancelled.orderNo(), null);
        assertCode(ErrorCode.STORE_ORDER_STATE_INVALID, () -> pay(cancelled, 1000));
        assertEquals(0, count("SELECT COUNT(*) FROM ord_offline_record WHERE order_no IN ('" + usd.subOrderNo() + "','"
                + cancelled.subOrderNo() + "')"));
    }

    @Test
    void collectionAttachesByBizNoOfTheSameStoreOnlyOnceAndViaThePaidPayOrder() {
        var view = create(CLERK, "POS-P2-COL", "K-P2-COL", List.of(custom(1, 1000, "清潔用品", "TAXABLE")), List.of(),
                null);
        assertFalse(settlements.attachCollection(collection("PYO-OTHER", "S2", view.subOrderNo(), "TWD", 1000)),
                "别店的收款不挂");
        assertFalse(settlements.attachCollection(collection("PYO-JPY", "S1", view.subOrderNo(), "JPY", 1000)),
                "币别不符不挂");
        assertFalse(settlements.attachCollection(collection("PYO-NOTSTORE", "S1", "OS-NOT-A-STORE-ORDER", "TWD", 1)));
        assertEquals(0, orders.get(CLERK, view.orderNo()).paidMinor());
        // 回调 / 对账补单交来的是支付单号：按支付域确认的事实挂（金额取支付单实收），重复到达只挂一次
        payOrders.byNo.put("PYO-PAID-1", collection("PYO-PAID-1", "S1", view.subOrderNo(), "TWD", 1000));
        settlements.onPayOrderSucceeded("PYO-PAID-1");
        settlements.onPayOrderSucceeded("PYO-PAID-1");
        settlements.onPayOrderSucceeded("PYO-UNKNOWN");
        var paid = orders.get(CLERK, view.orderNo());
        assertEquals(List.of("PAID", 1000L), List.of(paid.status(), paid.paidMinor()));
        assertEquals(1, count("SELECT COUNT(*) FROM ord_store_collection WHERE sub_order_no='" + view.subOrderNo() + "'"));
        assertEquals("WX_NATIVE", jdbc.queryForObject("SELECT pay_channel FROM ord_order WHERE order_no=?",
                String.class, view.orderNo()));
        assertEquals(2, events(view).size());
    }

    @Test
    void concurrentPaymentsSettleExactlyOnceAndPublishOneEvent() throws Exception {
        for (int round = 0; round < 4; round++) {
            var view = create(CLERK, "POS-P2-RACE-" + round, "K-P2-RACE-" + round,
                    List.of(custom(1, 1000, "清潔用品", "TAXABLE")), List.of(), null);
            int r = round;
            var outcomes = race(() -> pay(view, 600), () -> settlements.attachCollection(
                    collection("PYO-RACE-" + r, "S1", view.subOrderNo(), "TWD", 600)));
            assertTrue(outcomes.stream().noneMatch(o -> o instanceof Throwable), outcomes.toString());
            var paid = orders.get(CLERK, view.orderNo());
            assertEquals(List.of("PAID", 1200L), List.of(paid.status(), paid.paidMinor()));
            assertEquals(2, events(view).size(), "一次付清、一组事件");
        }
        // 两笔各自足额的现金同时到：一笔付清，另一笔回 20024 且不落库
        var view = create(CLERK, "POS-P2-RACE-CASH", "K-P2-RACE-CASH", List.of(custom(1, 1000, "x", "TAXABLE")),
                List.of(), null);
        var outcomes = race(() -> pay(view, 1000), () -> pay(view, 1000));
        assertEquals(1, outcomes.stream().filter(o -> o instanceof BizException e
                && e.errorCode() == ErrorCode.STORE_ORDER_STATE_INVALID).count(), outcomes.toString());
        assertEquals(1, count("SELECT COUNT(*) FROM ord_offline_record WHERE order_no='" + view.subOrderNo() + "'"));
        assertEquals(2, events(view).size());
    }

    // ------------------------------------------------------------------ P2 · AC6 / AC11 门店默认税别与常用品项

    @Test
    void ac6StoreDefaultTaxTypeFillsACustomLineWithoutOne() {
        invoices.settings = new InvoicePort.StoreSettings("ZERO_RATED", "MANUAL");
        var view = create(CLERK, "POS-P2-DEF", "K-P2-DEF", List.of(custom(1, 100, null, null),
                custom(1, 200, "外銷品", null), custom(1, 300, "一般", "TAXABLE")), List.of(), null);
        assertEquals(List.of("ZERO_RATED", "ZERO_RATED", "TAXABLE"),
                view.lines().stream().map(StoreOrderService.LineView::taxType).toList(),
                "门店默认是店长定的边界：收银员沿用零税率不需另授权");
        assertEquals(List.of(new MissingView(1, List.of("name"))), view.invoiceReadiness().missing());
        assertEquals(List.of("STORE_DEFAULT", "ENTERED", "ENTERED"), jdbc.queryForList("SELECT invoice_data_source "
                + "FROM ord_item WHERE sub_order_no=? ORDER BY id", String.class, view.subOrderNo()));
    }

    @Test
    void ac6SkuWithoutGoodsTaxClassTakesTheStoreDefaultOrStaysPending() {
        // 无门店默认：同 P1，待补
        var pending = create(CLERK, "POS-P2-SKU-ND", "K-P2-SKU-ND", List.of(sku("SKU-X", 1, 600)), List.of(), null);
        assertEquals(List.of(new MissingView(1, List.of("taxType"))), pending.invoiceReadiness().missing());
        // 门店默认 TAXABLE：商品没设课税别（OUT_OF_SCOPE）的 SKU 行取它，来源 STORE_DEFAULT；有课税别的商品不变
        invoices.settings = new InvoicePort.StoreSettings("TAXABLE", "MANUAL");
        var view = create(CLERK, "POS-P2-SKU-D", "K-P2-SKU-D", List.of(sku("SKU-X", 1, 600), sku("SKU-A", 1, 2500)),
                List.of(), null);
        assertEquals(List.of("TAXABLE", "TAXABLE"), view.lines().stream().map(StoreOrderService.LineView::taxType).toList());
        assertTrue(view.invoiceReadiness().missing().isEmpty());
        assertEquals(List.of("STORE_DEFAULT|STANDARD", "GOODS|STANDARD"), jdbc.queryForList("SELECT CONCAT_WS('|',"
                + "invoice_data_source,tax_class) FROM ord_item WHERE sub_order_no=? ORDER BY id", String.class,
                view.subOrderNo()));
        // 门店默认零税率：收银员（无 tax-override）照样能建
        invoices.settings = new InvoicePort.StoreSettings("ZERO_RATED", "MANUAL");
        assertEquals("ZERO_RATED", create(CLERK, "POS-P2-SKU-Z", "K-P2-SKU-Z", List.of(sku("SKU-X", 1, 600)), List.of(),
                null).lines().getFirst().taxType());
    }

    @Test
    void ac11StoreItemSuppliesNameAndTaxTypeEvenZeroRatedWithoutOverride() {
        invoices.items.put("IVS-1", new InvoicePort.StoreItem("IVS-1", "外銷清潔劑", "ZERO_RATED", true));
        invoices.items.put("IVS-2", new InvoicePort.StoreItem("IVS-2", "書籍", "EXEMPT", true));
        invoices.items.put("IVS-OFF", new InvoicePort.StoreItem("IVS-OFF", "停用", "TAXABLE", false));
        var view = create(CLERK, "POS-P2-ITEM", "K-P2-ITEM",
                List.of(new LineCmd("CUSTOM", null, 2, 150, null, null, "IVS-1"), custom(1, 80, null, null)),
                List.of(), null);
        var line = view.lines().getFirst();
        assertEquals(List.of("外銷清潔劑", "ZERO_RATED", true), List.of(line.name(), line.taxType(), line.frozen()));
        assertEquals("STORE_ITEM", jdbc.queryForObject("SELECT invoice_data_source FROM ord_item WHERE sub_order_no=? "
                + "ORDER BY id LIMIT 1", String.class, view.subOrderNo()));
        assertCode(ErrorCode.STORE_ORDER_STORE_ITEM_UNAVAILABLE, () -> create(CLERK, "POS-P2-ITEM-OFF", "K-P2-ITEM-OFF",
                List.of(new LineCmd("CUSTOM", null, 1, 1, null, null, "IVS-OFF")), List.of(), null));
        // 补资料也能选品项：免税同样不需另授权
        var filled = orders.fillInvoiceData(CLERK, view.orderNo(), new FillCmd("F-P2-ITEM",
                List.of(new FillLineCmd(2, null, null, "IVS-2")), null, "選品項"));
        assertEquals(List.of("書籍", "EXEMPT"), List.of(filled.lines().get(1).name(), filled.lines().get(1).taxType()));
        assertTrue(filled.invoiceReadiness().missing().isEmpty());
    }

    // ------------------------------------------------------------------ P2 · AC8 付清自动开票

    @Test
    void ac8OnPaidIssuesWithTheRememberedBuyerAndManualDoesNot() {
        // 本单 ON_PAID（门店默认 MANUAL）：付清 → ORDER_PAID → 自动开，买受人用建单时预存的载具
        var onPaid = create(CLERK, "POS-P2-AUTO", "K-P2-AUTO", List.of(custom(1, 1000, "清潔用品", "TAXABLE")),
                List.of(), CARRIER, "ON_PAID");
        assertEquals("ON_PAID", onPaid.invoiceTiming());
        pay(onPaid, 1000);
        autoIssue.consume(orderPaid(onPaid));
        assertEquals(CARRIER.carrierId(), invoices.issuedBuyers.get(onPaid.subOrderNo()).carrierId());
        assertEquals("ISSUED", orders.get(CLERK, onPaid.orderNo()).invoiceReadiness().state());
        // 门店 MANUAL、本单没指定：不开
        var manual = create(CLERK, "POS-P2-MANUAL", "K-P2-MANUAL", List.of(custom(1, 1000, "清潔用品", "TAXABLE")),
                List.of(), null);
        pay(manual, 1000);
        autoIssue.consume(orderPaid(manual));
        assertEquals("READY", orders.get(CLERK, manual.orderNo()).invoiceReadiness().state());
        // 门店 ON_PAID：本单 MANUAL 覆盖它不开；本单没指定跟门店开（一般消费者）
        invoices.settings = new InvoicePort.StoreSettings(null, "ON_PAID");
        var override = create(CLERK, "POS-P2-OVR", "K-P2-OVR", List.of(custom(1, 1000, "x", "TAXABLE")), List.of(),
                null, "MANUAL");
        pay(override, 1000);
        autoIssue.consume(orderPaid(override));
        assertFalse(invoices.active.containsKey(override.subOrderNo()));
        var follow = create(CLERK, "POS-P2-FOLLOW", "K-P2-FOLLOW", List.of(custom(1, 1000, "x", "TAXABLE")), List.of(),
                null);
        pay(follow, 1000);
        autoIssue.consume(orderPaid(follow));
        assertEquals(InvoicePort.Buyer.consumer(), invoices.issuedBuyers.get(follow.subOrderNo()));
        // ON_PAID 但资料不齐：26002 跳过（留给补资料后手动开），不交回 outbox 重试
        var pending = create(CLERK, "POS-P2-PEND", "K-P2-PEND", List.of(custom(1, 1000, null, "TAXABLE")), List.of(),
                null);
        pay(pending, 1000);
        autoIssue.consume(orderPaid(pending));
        assertEquals("NEEDS_INFO", orders.get(CLERK, pending.orderNo()).invoiceReadiness().state());
    }

    // ------------------------------------------------------------------ 迁移本身

    @Test
    void migrationsBindWalkInToStoreSaleAndClientOrderNoAndSeedRoles() {
        var insert = "INSERT INTO ord_order(order_no,customer_kind,identity_revision,admission_kind,caller,entity_no,"
                + "store_no,operation_no,client_order_no,currency,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,'WALK_IN',NULL,?,'POS','E1','S1','op',?,'TWD','WAIT_PAY','MAIN',NOW(),NOW())";
        assertThrows(DataAccessException.class, () -> jdbc.update(insert, "OD-MIG-1", "STORE_SALE", null));
        assertThrows(DataAccessException.class, () -> jdbc.update(insert, "OD-MIG-2", "LEGACY_FUNDED", "POS-MIG"));
        assertEquals(1, jdbc.update(insert, "OD-MIG-3", "STORE_SALE", "POS-MIG"));
        assertEquals(List.of("[\"biz:invoice:read\", \"biz:order:create\"]",
                        "[\"biz:order:view\", \"biz:order:create\", \"biz:order:reprice\", \"biz:invoice:tax-override\"]"),
                jdbc.queryForList("SELECT perms FROM mch_role ORDER BY role_code", String.class));
    }

    // ------------------------------------------------------------------ helpers

    private StoreSaleView create(Scope scope, String clientNo, String key, List<LineCmd> lines,
            List<AdjustmentCmd> adjustments, BuyerCmd buyer) {
        return create(scope, clientNo, key, lines, adjustments, buyer, null);
    }

    private StoreSaleView create(Scope scope, String clientNo, String key, List<LineCmd> lines,
            List<AdjustmentCmd> adjustments, BuyerCmd buyer, String invoiceTiming) {
        return orders.create(scope, new CreateCmd(key, clientNo, "POS", "TWD", lines, adjustments, buyer, invoiceTiming));
    }

    private static LineCmd sku(String skuNo, int qty, long price) {
        return new LineCmd("SKU", skuNo, qty, price, null, null, null);
    }

    private static LineCmd custom(int qty, long price, String name, String taxType) {
        return new LineCmd("CUSTOM", null, qty, price, name, taxType, null);
    }

    /** 收款挂单是 P2；这里直接把子单置为已付，只为驱动发票来源与准备度 */
    private void markPaid(StoreSaleView view) {
        jdbc.update("UPDATE ord_order SET status='PAID',paid_at=? WHERE order_no=?", System.currentTimeMillis(),
                view.orderNo());
        jdbc.update("UPDATE ord_sub_order SET status='WAIT_FULFILL' WHERE sub_order_no=?", view.subOrderNo());
    }

    private OfflineRecordService.Scope cashScope() {
        return new OfflineRecordService.Scope("E1", "S1", "U1");
    }

    private OfflineRecordService.OfflineRecordView pay(StoreSaleView view, long amount) {
        return cash.record(cashScope(), new OfflineRecordService.RecordCmd("CASH-" + UUID.randomUUID(), amount, "TWD",
                "CASH", view.subOrderNo(), null, null, null));
    }

    private static PaidCollection collection(String payOrderNo, String storeNo, String bizNo, String currency,
            long amount) {
        return new PaidCollection(payOrderNo, storeNo, bizNo, currency, amount, "WX_NATIVE");
    }

    /** 这张单的 outbox 事件，按写入顺序：聚合类型:聚合号:事件类型 */
    private List<String> events(StoreSaleView view) {
        return jdbc.queryForList("SELECT CONCAT_WS(':',aggregate_type,aggregate_id,event_type) FROM sys_outbox "
                + "WHERE aggregate_id IN (?,?) ORDER BY id", String.class, view.orderNo(), view.subOrderNo());
    }

    /** 这张单的 ORDER_PAID（生产上由同进程的 outbox 投递泵交给自动开票消费者） */
    private SysOutbox orderPaid(StoreSaleView view) {
        return outbox.selectList(null).stream().filter(e -> "ORDER_PAID".equals(e.getEventType())
                && view.orderNo().equals(e.getAggregateId())).findFirst().orElseThrow();
    }

    /** 两个动作同时起跑；返回各自的结果或异常 */
    private static List<Object> race(Supplier<?> a, Supplier<?> b) throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<Future<Object>>();
            for (Supplier<?> action : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return (Object) action.get();
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            var out = new ArrayList<Object>();
            for (var f : futures) out.add(f.get());
            return out;
        } finally {
            pool.shutdown();
        }
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static void assertCode(ErrorCode code, Runnable action) {
        var e = assertThrows(BizException.class, action::run);
        assertEquals(code, e.errorCode());
    }

    // ------------------------------------------------------------------ fixture

    static final class Db {
        static final String SCHEMA = "store_order_test_" + UUID.randomUUID().toString().replace("-", "");
        static final String TEMPLATE = System.getenv().getOrDefault("STORE_ORDER_MYSQL_TEMPLATE", "ai_shop");
        static final String URL = System.getenv("COMMERCE_MYSQL_URL");
        static final String USER = System.getenv("COMMERCE_MYSQL_USER");
        static final String PASS = System.getenv("COMMERCE_MYSQL_PASSWORD");
        static final List<String> TABLES = List.of("ord_order", "ord_sub_order", "ord_item",
                "ord_industry_order_source", "ord_price_quote_line", "prd_sku_pricing_policy", "mch_role",
                "ord_offline_record", "ord_offline_record_event", "sys_outbox", "pmt_apply", "ord_after_sale",
                "ord_after_sale_item", "ord_legacy_coupon_checkout");
        static DataSource ds;
        static boolean created;

        static synchronized DataSource dataSource() throws Exception {
            if (ds != null) return ds;
            assumeTrue(URL != null && URL.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/\\?.*"),
                    "COMMERCE_MYSQL_URL not set — native MySQL 9 test skipped");
            if (!TEMPLATE.matches("[a-zA-Z0-9_]{1,64}")) {
                throw new IllegalArgumentException("Invalid STORE_ORDER_MYSQL_TEMPLATE schema name");
            }
            var root = new JdbcTemplate(new DriverManagerDataSource(URL, USER, PASS));
            assumeTrue(root.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?"
                    + " AND table_name IN ('" + String.join("','", TABLES) + "')", Long.class, TEMPLATE)
                    == TABLES.size(), "local StoreOrder template schema missing — native MySQL 9 test skipped");
            root.execute("CREATE DATABASE " + SCHEMA);
            created = true;
            ds = new DriverManagerDataSource(URL.replace("/?", "/" + SCHEMA + "?"), USER, PASS);
            var jdbc = new JdbcTemplate(ds);
            var tables = new ArrayList<>(TABLES);
            boolean hasInvoiceLines = root.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='ord_invoice_data_fill'",Long.class,TEMPLATE) == 1;
            boolean hasCollections = root.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='ord_store_collection'",Long.class,TEMPLATE) == 1;
            if (hasInvoiceLines) tables.add("ord_invoice_data_fill");
            if (hasCollections) tables.add("ord_store_collection");
            for (var table : tables) {
                // SHOW CREATE TABLE 保留 CHECK 原名（CREATE TABLE … LIKE 会改成 _chk_N，V693 的 DROP CHECK 就找不到）
                String ddl = root.queryForMap("SHOW CREATE TABLE " + TEMPLATE + "." + table).get("Create Table")
                        .toString();
                jdbc.execute(ddl.replaceAll(" AUTO_INCREMENT=[0-9]+", ""));
            }
            jdbc.update("INSERT INTO mch_role(entity_no,role_code,name,perms,builtin,tenant_no,created_at,updated_at)"
                    + " VALUES ('*','MANAGER','店长','[\"biz:order:view\"]',1,'MAIN',NOW(),NOW()),"
                    + " ('*','CLERK','店员','[\"biz:invoice:read\"]',1,'MAIN',NOW(),NOW())");
            for (var migration : List.of("V692__store_order_invoice_data.sql", "V693__store_order_walk_in_party.sql",
                    "V694__store_order_role_permissions.sql", "V694__store_order_role_permissions.sql",
                    "V695__store_order_settlement.sql", "V715__pos_activity_sale_facts.sql", "V718__coupon_order_line_refund_share.sql")) {
                if (hasInvoiceLines && migration.startsWith("V692") || hasCollections && migration.startsWith("V695")) continue;
                String sql = new ClassPathResource("db/migration/" + migration).getContentAsString(StandardCharsets.UTF_8);
                for (var statement : sql.replaceAll("(?m)^--.*$", "").split(";"))
                    if (!statement.isBlank()) jdbc.execute(statement);
            }
            jdbc.update("INSERT INTO prd_sku_pricing_policy(policy_no,tenant_no,entity_no,store_no,goods_no,sku_no,"
                    + "promotion_eligible,tax_class,created_at,created_by,updated_at,updated_by,version,deleted) VALUES"
                    + " ('P-A','MAIN','E1','S1','G-A','SKU-A',1,'STANDARD',NOW(),'T',NOW(),'T',1,0),"
                    + " ('P-X','MAIN','E1','S1','G-X','SKU-X',1,'OUT_OF_SCOPE',NOW(),'T',NOW(),'T',1,0),"
                    + " ('P-A2','MAIN','E1','S2','G-A','SKU-A',1,'EXEMPT',NOW(),'T',NOW(),'T',1,0)");
            return ds;
        }
    }

    @AfterAll
    static void dropSchema() {
        if (Db.created)
            new JdbcTemplate(new DriverManagerDataSource(Db.URL, Db.USER, Db.PASS)).execute("DROP DATABASE " + Db.SCHEMA);
    }

    /** 发票域替身：预存买受人与有效发票；载具号格式不对按发票域的口径回 26004（真实校验在 TwBuyerRules） */
    static final class FakeInvoices implements InvoicePort {
        final Map<String, Buyer> buyers = new ConcurrentHashMap<>();
        final Map<String, InvoiceReceipt> active = new ConcurrentHashMap<>();
        final Map<String, Buyer> issuedBuyers = new ConcurrentHashMap<>();
        final Map<String, StoreItem> items = new ConcurrentHashMap<>();
        volatile StoreSettings settings = new StoreSettings(null, "MANUAL");
        /** 开票前按真实发票来源读一遍：不 READY 抛 26002，与发票域同口径 */
        volatile SubOrderInvoiceSourcePortImpl source;

        @Override
        public InvoiceReceipt issue(Scope scope, String sourceType, String sourceNo, Buyer buyer) {
            var loaded = source.load(scope, sourceType, sourceNo);
            var receipt = new InvoiceReceipt("IV-" + sourceNo, "TW_EGUI", "AB00000002", "11512", "0000", "ISSUED",
                    false, loaded.currency(), loaded.totalMinor());
            active.put(sourceNo, receipt);
            issuedBuyers.put(sourceNo, buyer);
            return receipt;
        }

        @Override
        public StoreSettings storeSettings(Scope scope) {
            return settings;
        }

        @Override
        public Optional<StoreItem> storeItem(Scope scope, String storeItemNo) {
            return Optional.ofNullable(items.get(storeItemNo));
        }

        @Override
        public Optional<InvoiceReceipt> activeOf(Scope scope, String sourceType, String sourceNo) {
            return Optional.ofNullable(active.get(sourceNo));
        }

        @Override
        public void rememberBuyer(Scope scope, String sourceType, String sourceNo, Buyer buyer) {
            if (buyer.carrierId() != null && !buyer.carrierId().matches("/[0-9A-Z.+-]{7}"))
                throw BizException.of(ErrorCode.INVOICE_BUYER_INVALID);
            buyers.put(sourceNo, buyer);
        }

        @Override
        public Optional<Buyer> rememberedBuyer(Scope scope, String sourceType, String sourceNo) {
            return Optional.ofNullable(buyers.get(sourceNo));
        }

        @Override
        public Optional<InvoiceReceipt> issueIfAutomatic(Scope scope, String sourceType, String sourceNo) {
            return Optional.empty();
        }
    }

    /** 支付域替身：已成功的收款码 / 付款链接支付单（内嵌形态由 PaidCollectionPortImpl 读 pay_order） */
    static final class FakeCollections implements PaidCollectionPort {
        final Map<String, PaidCollection> byNo = new ConcurrentHashMap<>();

        @Override
        public Optional<PaidCollection> paidCollection(String payOrderNo) {
            return Optional.ofNullable(byNo.get(payOrderNo));
        }
    }

    @Configuration
    @MapperScan(basePackageClasses = {OrderMapper.class, GoodsMapper.class, SysOutboxMapper.class},
            markerInterface = BaseMapper.class,
            sqlSessionFactoryRef = "sqlSessionFactory")
    @MapperScan(basePackageClasses = PosActivitySaleMapper.class, annotationClass = org.apache.ibatis.annotations.Mapper.class,
            sqlSessionFactoryRef = "sqlSessionFactory")
    static class Config {
        @Bean PosActivityPort posActivityPort() { return mock(PosActivityPort.class); }
        @Bean PosSaleMemberPort posSaleMemberPort() { return mock(PosSaleMemberPort.class); }
        @Bean
        DataSource dataSource() throws Exception {
            return Db.dataSource();
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            var global = new GlobalConfig();
            global.setMetaObjectHandler(new AuditMetaObjectHandler());
            factory.setGlobalConfig(global);
            // 与生产 PrimaryPersistenceConfiguration 同一个乐观锁插件：现金记录作废 / 更正靠 @Version
            var interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            factory.setPlugins(interceptor);
            return factory.getObject();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean
        FakeInvoices invoices() {
            return new FakeInvoices();
        }

        @Bean
        SubOrderInvoiceSourcePortImpl invoiceSource(SubOrderMapper subs, OrderMapper orders, OrderItemMapper items,
                IndustryOrderSourceMapper bindings, PriceQuoteLineMapper quoteLines, FakeInvoices invoices) {
            var source = new SubOrderInvoiceSourcePortImpl(subs, orders, items, bindings, quoteLines,
                    tools.jackson.databind.json.JsonMapper.builder().build());
            invoices.source = source;
            return source;
        }

        @Bean
        FakeCollections payOrders() {
            return new FakeCollections();
        }

        @Bean
        OutboxEventBus outboxEventBus(SysOutboxMapper rows) {
            return new OutboxEventBus(rows, tools.jackson.databind.json.JsonMapper.builder().build(),
                    new StaticListableBeanFactory().getBeanProvider(OutboxPump.class));
        }

        @Bean
        StoreOrderSettlementService settlements(OrderMapper orders, SubOrderMapper subs, RecordMapper records,
                StoreCollectionMapper collections, OutboxEventBus events, FakeCollections payOrders,
                ObjectProvider<InvoicePort> invoicePort, PlatformTransactionManager tx) {
            return new StoreOrderSettlementServiceImpl(orders, subs, records, collections, events,
                    new StaticListableBeanFactory(Map.of("payOrders", payOrders)).getBeanProvider(PaidCollectionPort.class),
                    invoicePort, tx);
        }

        @Bean
        OfflineRecordService cash(RecordMapper records, EventMapper events, SubOrderMapper subs,
                PlatformTransactionManager tx) {
            var merchants = mock(MerchantQueryPort.class);
            when(merchants.marketOf("E1")).thenReturn("TW");
            var markets = mock(MarketPort.class);
            when(markets.find("TW")).thenReturn(Optional.of(
                    new MarketPort.MarketBrief("TW", "台灣", "TWD", 0, "Asia/Taipei", 1, true)));
            // setOwnershipWrites / setStoreSettlements 由容器按 @Autowired 注入
            return new OfflineRecordServiceImpl(records, events, subs, merchants, markets, mock(UserQueryPort.class), tx);
        }

        @Bean
        StoreOwnershipWritePort ownershipWrites() {
            return new StoreOwnershipWritePort() {
                @Override public void lockAndCheck(String t, String e, String s) { }
                @Override public void lockEntityAndCheck(String t, String e) { }
            };
        }

        @Bean
        InvoiceAutoIssueConsumer autoIssue(FakeInvoices invoices, SubOrderMapper subs, OrderMapper orders) {
            return new InvoiceAutoIssueConsumer(invoices, subs, orders,
                    tools.jackson.databind.json.JsonMapper.builder().build());
        }

        @Bean
        StoreOrderService storeOrders(OrderMapper orders, SubOrderMapper subs, OrderItemMapper items,
                InvoiceDataFillMapper fills, GoodsMapper goodsRows, SkuMapper skuRows, SkuPricingPolicyMapper policies,
                PlatformTransactionManager tx, FakeInvoices invoices, StoreOrderSettlementService settlements) {
            var policyPort = new GoodsPricingPolicyPortImpl(new GoodsPricingPolicyServiceImpl(goodsRows, skuRows,
                    policies, mock(RequestIdempotencyPortImpl.class), tx));
            var goods = mock(GoodsQueryPort.class);
            when(goods.snapshot(anyList(), anyMap())).thenAnswer(inv -> {
                var out = new java.util.HashMap<String, SkuSnapshot>();
                for (String no : inv.<List<String>>getArgument(0)) {
                    switch (no) {
                        case "SKU-A" -> out.put(no, snapshot(no, "G-A", "S1", "Test Tissue", 2500));
                        case "SKU-X" -> out.put(no, snapshot(no, "G-X", "S1", "Imported Tea", 600));
                        case "SKU-OTHER-STORE" -> out.put(no, snapshot(no, "G-O", "S9", "Elsewhere", 100));
                        default -> { }
                    }
                }
                return out;
            });
            StoreOwnershipWritePort ownership = new StoreOwnershipWritePort() {
                @Override public void lockAndCheck(String t, String e, String s) { }
                @Override public void lockEntityAndCheck(String t, String e) { }
            };
            var template = new TransactionTemplate(tx);
            var numbers = mock(DocumentNumberCoordinator.class);
            var seq = new AtomicLong();
            when(numbers.transaction(any(), any())).thenAnswer(inv ->
                    template.execute(status -> inv.<Supplier<?>>getArgument(1).get()));
            when(numbers.takeReserved(any())).thenAnswer(inv -> {
                DocumentType type = inv.getArgument(0);
                var today = LocalDate.now();
                return new ReservedDocumentNumber(type, today,
                        DocumentNumberFormatter.format(type, today, seq.incrementAndGet()));
            });
            var provider = new StaticListableBeanFactory(Map.of("invoices", invoices)).getBeanProvider(InvoicePort.class);
            return new StoreOrderServiceImpl(orders, subs, items, fills, goods, policyPort, ownership, numbers,
                    provider, tx, settlements);
        }

        private static SkuSnapshot snapshot(String skuNo, String goodsNo, String storeNo, String title, long price) {
            return new SkuSnapshot(skuNo, goodsNo, "E1", title, null, null, "RETAIL", null, price, 10, true,
                    new ArrayList<>(), null, null, null, null, null, storeNo);
        }
    }
}
