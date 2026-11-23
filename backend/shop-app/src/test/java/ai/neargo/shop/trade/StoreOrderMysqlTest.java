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
import ai.neargo.shop.idem.IdempotencyService;
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
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.product.GoodsQueryPort.SkuSnapshot;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.InvoiceDataFillMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.PriceQuoteLineMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
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
import ai.neargo.shop.trade.service.impl.StoreOrderServiceImpl;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
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
 * 门店零售单在真实 MySQL 9 上的验收（TDD-统一订单与开票资料 §5，P1：AC1–6、AC9、AC10、AC15）。
 *
 * <p><b>表结构从哪来</b>：平台迁移不能在空 MySQL 上从零重放（V1–V574 是 MariaDB 方言，见 run-local），所以这里
 * 从本机开发库 {@code ai_shop}（{@code SHOW CREATE TABLE}，只读）复制本次涉及的几张表的现行结构到一个随机库，
 * 再执行本次的 V692 / V693 / V694 —— 迁移在生产同形的表与 CHECK 名上真跑一遍，H2 证明不了这些。
 *
 * <p>连接串沿用 {@code COMMERCE_MYSQL_URL/USER/PASSWORD}（只认 127.0.0.1），没配或本机没有 {@code ai_shop} 时跳过。
 * 商品快照与发票域用替身：前者不是本次改动，后者在 shop-app 只能经 spi 使用（InvoiceOptionalTest）。
 */
@SpringJUnitConfig(StoreOrderMysqlTest.Config.class)
class StoreOrderMysqlTest {

    static final Scope CLERK = new Scope("E1", "S1", "U1", false, false);
    static final Scope MANAGER = new Scope("E1", "S1", "U2", true, true);
    static final BuyerCmd CARRIER = new BuyerCmd("CARRIER", null, null, "3J0002", "/ABC+123", null);

    @Autowired StoreOrderService orders;
    @Autowired SubOrderInvoiceSourcePortImpl invoiceSource;
    @Autowired FakeInvoices invoices;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        invoices.buyers.clear();
        invoices.active.clear();
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
        assertCode(ErrorCode.STORE_ORDER_STORE_ITEM_UNSUPPORTED, () -> create(CLERK, "POS-AC2-I", "K-AC2-I",
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
                List.of(new FillLineCmd(2, "清潔用品", "TAXABLE")),
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
                List.of(new FillLineCmd(2, "清潔用品", "TAXABLE")), null, "retry"));
        assertEquals(3, count("SELECT COUNT(*) FROM ord_invoice_data_fill WHERE sub_order_no='" + view.subOrderNo() + "'"));
        // 已补过（或 SKU 冻结）的字段给不同值：冻结
        assertCode(ErrorCode.INVOICE_DATA_FROZEN, () -> orders.fillInvoiceData(CLERK, view.orderNo(),
                new FillCmd("F-AC5-2", List.of(new FillLineCmd(2, "別的名字", null)), null, null)));
        assertCode(ErrorCode.INVOICE_DATA_FROZEN, () -> orders.fillInvoiceData(CLERK, view.orderNo(),
                new FillCmd("F-AC5-3", List.of(new FillLineCmd(1, null, "EXEMPT")), null, null)));
        assertCode(ErrorCode.INVOICE_DATA_FROZEN, () -> orders.fillInvoiceData(CLERK, view.orderNo(),
                new FillCmd("F-AC5-4", List.of(), CARRIER, null)));
    }

    @Test
    void ac6ZeroRatedOrExemptOnACustomLineNeedsTheOverridePermission() {
        assertCode(ErrorCode.BIZ_ROLE_FORBIDDEN, () -> create(CLERK, "POS-AC6", "K-AC6",
                List.of(custom(1, 100, "外銷品", "ZERO_RATED")), List.of(), null));
        var view = create(CLERK, "POS-AC6", "K-AC6-2", List.of(custom(1, 100, "書籍", null)), List.of(), null);
        assertCode(ErrorCode.BIZ_ROLE_FORBIDDEN, () -> orders.fillInvoiceData(CLERK, view.orderNo(),
                new FillCmd("F-AC6", List.of(new FillLineCmd(1, null, "EXEMPT")), null, null)));
        assertEquals("EXEMPT", orders.fillInvoiceData(MANAGER, view.orderNo(),
                new FillCmd("F-AC6-M", List.of(new FillLineCmd(1, null, "EXEMPT")), null, null)).lines().getFirst().taxType());
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
        return orders.create(scope, new CreateCmd(key, clientNo, "POS", "TWD", lines, adjustments, buyer));
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
        static final String TEMPLATE = "ai_shop";
        static final String URL = System.getenv("COMMERCE_MYSQL_URL");
        static final String USER = System.getenv("COMMERCE_MYSQL_USER");
        static final String PASS = System.getenv("COMMERCE_MYSQL_PASSWORD");
        static final List<String> TABLES = List.of("ord_order", "ord_sub_order", "ord_item",
                "ord_industry_order_source", "ord_price_quote_line", "prd_sku_pricing_policy", "mch_role");
        static DataSource ds;
        static boolean created;

        static synchronized DataSource dataSource() throws Exception {
            if (ds != null) return ds;
            assumeTrue(URL != null && URL.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/\\?.*"),
                    "COMMERCE_MYSQL_URL not set — native MySQL 9 test skipped");
            var root = new JdbcTemplate(new DriverManagerDataSource(URL, USER, PASS));
            assumeTrue(root.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?"
                    + " AND table_name IN ('" + String.join("','", TABLES) + "')", Long.class, TEMPLATE)
                    == TABLES.size(), "local ai_shop template schema missing — native MySQL 9 test skipped");
            root.execute("CREATE DATABASE " + SCHEMA);
            created = true;
            ds = new DriverManagerDataSource(URL.replace("/?", "/" + SCHEMA + "?"), USER, PASS);
            var jdbc = new JdbcTemplate(ds);
            for (var table : TABLES) {
                // SHOW CREATE TABLE 保留 CHECK 原名（CREATE TABLE … LIKE 会改成 _chk_N，V693 的 DROP CHECK 就找不到）
                String ddl = root.queryForMap("SHOW CREATE TABLE " + TEMPLATE + "." + table).get("Create Table")
                        .toString();
                jdbc.execute(ddl.replaceAll(" AUTO_INCREMENT=[0-9]+", ""));
            }
            jdbc.update("INSERT INTO mch_role(entity_no,role_code,name,perms,builtin,tenant_no,created_at,updated_at)"
                    + " VALUES ('*','MANAGER','店长','[\"biz:order:view\"]',1,'MAIN',NOW(),NOW()),"
                    + " ('*','CLERK','店员','[\"biz:invoice:read\"]',1,'MAIN',NOW(),NOW())");
            for (var migration : List.of("V692__store_order_invoice_data.sql", "V693__store_order_walk_in_party.sql",
                    "V694__store_order_role_permissions.sql", "V694__store_order_role_permissions.sql")) {
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

        @Override
        public InvoiceReceipt issue(Scope scope, String sourceType, String sourceNo, Buyer buyer) {
            throw new UnsupportedOperationException();
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

    @Configuration
    @MapperScan(basePackageClasses = {OrderMapper.class, GoodsMapper.class}, markerInterface = BaseMapper.class,
            sqlSessionFactoryRef = "sqlSessionFactory")
    static class Config {
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
                IndustryOrderSourceMapper bindings, PriceQuoteLineMapper quoteLines) {
            return new SubOrderInvoiceSourcePortImpl(subs, orders, items, bindings, quoteLines,
                    tools.jackson.databind.json.JsonMapper.builder().build());
        }

        @Bean
        StoreOrderService storeOrders(OrderMapper orders, SubOrderMapper subs, OrderItemMapper items,
                InvoiceDataFillMapper fills, GoodsMapper goodsRows, SkuMapper skuRows, SkuPricingPolicyMapper policies,
                PlatformTransactionManager tx, FakeInvoices invoices) {
            var policyPort = new GoodsPricingPolicyPortImpl(new GoodsPricingPolicyServiceImpl(goodsRows, skuRows,
                    policies, mock(IdempotencyService.class), tx));
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
                    provider, tx);
        }

        private static SkuSnapshot snapshot(String skuNo, String goodsNo, String storeNo, String title, long price) {
            return new SkuSnapshot(skuNo, goodsNo, "E1", title, null, null, "RETAIL", null, price, 10, true,
                    new ArrayList<>(), null, null, null, null, null, storeNo);
        }
    }
}
