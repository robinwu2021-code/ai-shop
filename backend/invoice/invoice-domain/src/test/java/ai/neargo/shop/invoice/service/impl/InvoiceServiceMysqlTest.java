package ai.neargo.shop.invoice.service.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.SecretRef;
import ai.neargo.secret.api.SecretVault;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceChoiceReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceQuery;
import ai.neargo.shop.invoice.dto.InvoiceDtos.SaveCarrierReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.NumberRangeReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.RegimeProfileReq;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.gateway.InvoiceGateway;
import ai.neargo.shop.invoice.gateway.InvoiceProviderGateway;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.InvoiceMapper;
import ai.neargo.shop.invoice.port.InvoicePrintSourceFactsPortImpl;
import ai.neargo.shop.invoice.regime.InvoiceRegimes;
import ai.neargo.shop.invoice.service.InvoiceBuyerService;
import ai.neargo.shop.invoice.service.InvoiceService;
import ai.neargo.shop.invoice.service.InvoiceSetupService;
import ai.neargo.shop.invoice.service.InvoiceTransmissionService;
import ai.neargo.shop.invoice.regime.tw.TwEguiRegime;
import ai.neargo.shop.invoice.regime.tw.TwPeriods;
import ai.neargo.shop.print.api.BusinessPrintFactReady;
import ai.neargo.shop.print.api.GuardDecision;
import ai.neargo.shop.print.api.PrintScope;
import ai.neargo.shop.print.api.PrintSourceReference;
import ai.neargo.shop.print.api.Printability;
import ai.neargo.shop.spi.invoice.InvoicePort.Buyer;
import ai.neargo.shop.spi.invoice.InvoicePort.InvoiceReceipt;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import ai.neargo.shop.spi.invoice.InvoiceSourcePort;
import ai.neargo.shop.spi.invoice.InvoiceSourcePort.BuyerSource;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 发票域在真实 MySQL 9 上的验收（PRD-发票开立与打印 §4，TDD-发票模块 §5）：号段行锁、唯一键、CHECK、
 * 条件更新与 V1/V2 迁移都要真库才算数 —— H2 证明不了这些。
 *
 * <p>连接串沿用仓库原生库测试的环境变量 {@code COMMERCE_MYSQL_URL/USER/PASSWORD}（形如
 * {@code jdbc:mysql://127.0.0.1:3308/?…}），每次建一个随机库、跑完删掉；没配置时跳过。
 */
@SpringJUnitConfig(InvoiceServiceMysqlTest.Config.class)
class InvoiceServiceMysqlTest {

    static final Scope SCOPE = new Scope("T1", "E1", "S1", "U1");
    static final String KEY_HEX = "0123456789ABCDEF0123456789ABCDEF";

    @Autowired InvoiceService invoices;
    @Autowired InvoiceSetupService setup;
    @Autowired InvoiceTransmissionService transmissions;
    @Autowired InvoiceBuyerService buyers;
    @Autowired InvoicePrintSourceFactsPortImpl printSource;
    @Autowired Sources sources;
    @Autowired FlakyGateway gateway;
    @Autowired FakeProvider provider;
    @Autowired OutboxEventBus events;
    @Autowired JdbcTemplate jdbc;
    @Autowired InvoiceMapper invoiceRows;
    @Autowired ai.neargo.shop.invoice.mapper.InvoiceMappers.AllowanceMapper allowanceRows;

    String period;

    @BeforeEach
    void reset() {
        for (var t : List.of("ivc_invoice_line", "ivc_allowance", "ivc_transmission", "ivc_invoice", "ivc_buyer_preference",
                "ivc_user_carrier",
                "ivc_number_range", "ivc_regime_profile")) jdbc.update("DELETE FROM " + t);
        sources.byNo.clear();
        sources.orders.clear();
        gateway.fail.set(false);
        gateway.payloads.clear();
        provider.seen.clear();
        provider.taxId = "12345675";
        provider.failCode = null;
        provider.sendCode = null;
        provider.sends.set(0);
        clearInvocations(events);
        period = TwPeriods.periodOf(Instant.now());
        setup.saveProfile(SCOPE, new RegimeProfileReq("TW_EGUI", "12345675", "示例營業人", "TEST", KEY_HEX, null, null, null, null, null, null, null));
        setup.addRange(SCOPE, new NumberRangeReq("TW_EGUI", period, "AB", 1, 50, null));
    }

    @Test
    void ac1NumbersAreSequentialAndConcurrentIssuesNeverDuplicate() throws Exception {
        for (int i = 1; i <= 3; i++) sources.paid("SO" + i, 105_000);
        assertEquals("AB00000001", issue("SO1").fiscalNumber());
        assertEquals("AB00000002", issue("SO2").fiscalNumber());
        assertEquals("AB00000003", issue("SO3").fiscalNumber());
        int n = 12;
        for (int i = 0; i < n; i++) sources.paid("C" + i, 10_000);
        var numbers = concurrently(n, i -> issue("C" + i).fiscalNumber());
        assertEquals(n, new HashSet<>(numbers).size());
        var all = jdbc.queryForList("SELECT fiscal_number FROM ivc_invoice ORDER BY fiscal_number", String.class);
        assertEquals(n + 3, all.size());
        assertEquals("AB00000015", all.getLast(), "no gaps: 15 numbers, 15 invoices");
        assertEquals(16, jdbc.queryForObject("SELECT next_no FROM ivc_number_range", Integer.class));
    }

    @Test
    void ac2ExhaustedRangeRejectsWithoutCreatingAnInvoice() {
        jdbc.update("UPDATE ivc_number_range SET next_no=50");
        sources.paid("SO1", 1_000);
        sources.paid("SO2", 1_000);
        assertEquals("AB00000050", issue("SO1").fiscalNumber());
        assertEquals("EXHAUSTED", jdbc.queryForObject("SELECT status FROM ivc_number_range", String.class));
        assertCode(ErrorCode.INVOICE_NUMBER_EXHAUSTED, () -> issue("SO2"));
        assertEquals(1, count("ivc_invoice"));
    }

    @Test
    void ac3SameSourceReturnsTheOriginalInvoiceEvenUnderConcurrency() throws Exception {
        sources.paid("SO1", 105_000);
        var results = concurrently(6, i -> issue("SO1").invoiceNo());
        assertEquals(1, new HashSet<>(results).size());
        assertEquals(1, count("ivc_invoice"));
        assertEquals(2, jdbc.queryForObject("SELECT next_no FROM ivc_number_range", Integer.class));
        assertEquals(1, count("ivc_transmission"));
    }

    @Test
    void ac4InvalidBuyerRejectedBeforeANumberIsTaken() {
        sources.paid("SO1", 1_000);
        assertCode(ErrorCode.INVOICE_BUYER_INVALID,
                () -> invoices.issue(SCOPE, "SUB_ORDER", "SO1", new Buyer("12345678", null, null, null, null)));
        assertCode(ErrorCode.INVOICE_BUYER_INVALID,
                () -> invoices.issue(SCOPE, "SUB_ORDER", "SO1", new Buyer(null, null, "3J0002", "/ABC+123", "8585")));
        assertEquals(1, jdbc.queryForObject("SELECT next_no FROM ivc_number_range", Integer.class));
    }

    @Test
    void ac5AmountsAgreeWithTheSourceAndB2bSplitsTax() {
        sources.byNo.put("BAD", new InvoiceSourcePort.InvoiceSource("RETAIL", "TWD", Instant.now(), 10_000,
                List.of(line("x", 9_000)), null));
        assertCode(ErrorCode.INVOICE_SOURCE_NOT_READY, () -> issue("BAD"));
        sources.paid("SO1", 105_000);
        var r = invoices.issue(SCOPE, "SUB_ORDER", "SO1", new Buyer("22099131", "台積", null, null, null));
        var row = jdbc.queryForMap("SELECT sales_minor, tax_minor, total_minor, buyer_type FROM ivc_invoice"
                + " WHERE invoice_no=?", r.invoiceNo());
        assertEquals(100_000L, ((Number) row.get("sales_minor")).longValue());
        assertEquals(5_000L, ((Number) row.get("tax_minor")).longValue());
        assertEquals(105_000L, ((Number) row.get("total_minor")).longValue());
        assertEquals("B2B", row.get("buyer_type"));
        assertEquals(105_000L, jdbc.queryForObject(
                "SELECT SUM(amount_minor) FROM ivc_invoice_line WHERE invoice_no=?", Long.class, r.invoiceNo()));
    }

    @Test
    void ac5MixedTaxTypesArePersistedPerLineAndInTheUploadMessage() {
        sources.byNo.put("MIX", new InvoiceSourcePort.InvoiceSource("FOOD", "TWD", Instant.now(), 155_000,
                List.of(line("餐點", 105_000, InvoiceSourcePort.Line.TAXABLE),
                        line("外銷", 20_000, InvoiceSourcePort.Line.ZERO),
                        line("書籍", 30_000, InvoiceSourcePort.Line.EXEMPT)), null));
        var r = invoices.issue(SCOPE, "SUB_ORDER", "MIX", new Buyer("22099131", null, null, null, null));
        var vo = invoices.get(SCOPE, r.invoiceNo());
        assertEquals(100_000, vo.salesMinor());
        assertEquals(5_000, vo.taxMinor());
        assertEquals(20_000, vo.zeroTaxSalesMinor());
        assertEquals(30_000, vo.freeTaxSalesMinor());
        assertEquals(List.of("TAXABLE", "ZERO", "EXEMPT"),
                vo.lines().stream().map(l -> l.taxType()).toList());
        String payload = jdbc.queryForObject("SELECT payload FROM ivc_transmission WHERE ref_no=?", String.class,
                r.invoiceNo());
        assertTrue(payload.matches("(?s).*\"TaxType\":\\s*\"9\".*"), payload);
        assertTrue(payload.matches("(?s).*\"ZeroTaxSalesAmount\":\\s*200.*"), payload);
    }

    @Test
    void ac6ProofSnapshotCarriesCodesAndPrintCenterIsWoken() {
        sources.paid("SO1", 105_000);
        var r = issue("SO1");
        assertTrue(r.printMark());
        var ref = InvoicePrintSourceFactsPortImpl.ref(row(r.invoiceNo()));
        var fact = printSource.snapshot(ref);
        assertEquals(Printability.READY, fact.printability());
        String json = fact.immutablePayload().canonicalJson();
        assertTrue(json.contains("\"barcode\":\"" + period + r.fiscalNumber() + r.randomCode() + "\""));
        assertTrue(json.contains("\"qrLeft\":\"" + r.fiscalNumber()));
        assertTrue(json.contains("\"periodLabel\":\"" + TwPeriods.label(period) + "\""));
        verify(events, times(2)).publish(any(BusinessPrintFactReady.class));
        assertEquals(GuardDecision.ALLOW_HISTORICAL_RECEIPT, printSource.executionGuard(ref).decision());
    }

    @Test
    void ac7CarrierOrDonationMeansNoProof() {
        sources.paid("SO1", 1_000);
        var r = invoices.issue(SCOPE, "SUB_ORDER", "SO1", new Buyer(null, null, "3J0002", "/ABC+123", null));
        assertFalse(r.printMark());
        // 不出证明联，只唤醒交易明细（顾客手上的收据）
        verify(events, times(1)).publish(any(BusinessPrintFactReady.class));
        verify(events).publish(argThat((DomainEvent e) -> e instanceof BusinessPrintFactReady ready
                && InvoicePrintSourceFactsPortImpl.DETAIL_SCENE.equals(ready.factRef().scene())));
        var fact = printSource.snapshot(InvoicePrintSourceFactsPortImpl.ref(row(r.invoiceNo())));
        assertEquals(Printability.BLOCKED, fact.printability());
        assertEquals("invoice.proofNotPrintable", fact.blockCode());
    }

    @Test
    void ac9VoidInPeriodFreesTheSourceForANewNumber() {
        sources.paid("SO1", 1_000);
        var first = issue("SO1");
        var voided = invoices.voidInvoice(SCOPE, first.invoiceNo(), "開錯統編");
        assertEquals("VOIDED", voided.status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ivc_transmission WHERE message_type='VOID' AND ref_no=?", Integer.class,
                first.invoiceNo()));
        var ref = InvoicePrintSourceFactsPortImpl.ref(row(first.invoiceNo()));
        assertEquals(GuardDecision.CANCEL_UNSUBMITTED_EXECUTION, printSource.executionGuard(ref).decision());
        var second = issue("SO1");
        assertNotEquals(first.fiscalNumber(), second.fiscalNumber());
        assertCode(ErrorCode.INVOICE_STATE_INVALID, () -> invoices.voidInvoice(SCOPE, first.invoiceNo(), "again"));
    }

    @Test
    void ac10PreviousPeriodCannotBeVoided() {
        sources.paid("SO1", 1_000);
        var r = issue("SO1");
        jdbc.update("UPDATE ivc_invoice SET period_code='11410' WHERE invoice_no=?", r.invoiceNo());
        assertCode(ErrorCode.INVOICE_VOID_PERIOD_CLOSED, () -> invoices.voidInvoice(SCOPE, r.invoiceNo(), "x"));
    }

    @Test
    void ac11AllowancesNeverExceedTheInvoice() {
        sources.paid("SO1", 105_000);
        var r = issue("SO1");
        var after = invoices.allowance(SCOPE, r.invoiceNo(), 30_000, "退一杯");
        assertEquals(30_000, after.allowedMinor());
        assertEquals(1_400, after.allowances().getFirst().taxMinor(), "300 元含稅中的稅額 = 300 − round(285.71)");
        assertCode(ErrorCode.INVOICE_ALLOWANCE_EXCEEDED, () -> invoices.allowance(SCOPE, r.invoiceNo(), 80_000, "x"));
        assertEquals(1, count("ivc_allowance"));
        assertEquals(r.fiscalNumber() + "01", jdbc.queryForObject(
                "SELECT fiscal_allowance_number FROM ivc_allowance", String.class), "对外折让单号 = 发票号码 + 序号");
        assertCode(ErrorCode.INVOICE_STATE_INVALID, () -> invoices.voidInvoice(SCOPE, r.invoiceNo(), "x"));
    }

    @Test
    void ac28ProviderAccountIsSealedVerifiedAndAllocatesRanges() {
        var profile = setup.saveProfile(SCOPE, new RegimeProfileReq("TW_EGUI", "12345675", "示例營業人", "PROV", null,
                null, null, "OFFICE-1", "pos-user", "pos-secret", null, null));
        assertEquals("OFFICE-1", profile.providerAccountCode());
        assertTrue(profile.providerCredentialConfigured());
        var row = jdbc.queryForMap("SELECT * FROM ivc_regime_profile WHERE entity_no='E1'");
        assertFalse(row.values().stream().anyMatch(v -> String.valueOf(v).contains("pos-secret")), "密码不落业务表");
        jdbc.update("UPDATE ivc_regime_profile SET qr_key_ref=NULL");

        var office = setup.verifyProvider(SCOPE);
        assertTrue(office.qrKeyStored(), "服务商回的 QR 金钥存进 secret");
        assertTrue(setup.profile(SCOPE).orElseThrow().qrKeyConfigured());
        assertEquals(new InvoiceGateway.ProviderAccount("OFFICE-1", "pos-user", "pos-secret"), provider.seen.getFirst());

        var range = setup.fetchProviderRange(SCOPE, period);
        assertEquals("PROVIDER", range.allocationOwner());
        assertEquals(12_345_600, range.startNo());
        assertEquals(range.rangeNo(), setup.fetchProviderRange(SCOPE, period).rangeNo(), "同一段取回两次只入库一次");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ivc_number_range WHERE allocation_owner='PROVIDER' AND apply_id='APPLY-1'",
                Integer.class));

        sources.paid("SO1", 1_000);
        var r = issue("SO1");
        provider.seen.clear();
        assertEquals(1, transmissions.transmitDue(10));
        assertEquals("pos-secret", provider.seen.getFirst().password(), "上传时现解账号");
        assertEquals("P-" + r.invoiceNo(), jdbc.queryForObject(
                "SELECT gateway_ref FROM ivc_transmission WHERE ref_no=?", String.class, r.invoiceNo()));
    }

    @Test
    void ac38ManualRangesWarnOnceWhenLowOnceWhenEmptyAndOnceForAMissingNextPeriod() {
        setup.saveProfile(SCOPE, new RegimeProfileReq("TW_EGUI", "12345675", "示例營業人", "TEST", null, null, null,
                null, null, null, 60, null));
        var view = setup.profile(SCOPE).orElseThrow();
        assertEquals(50, view.currentRemaining());
        assertEquals(60, view.rangeLowWater());
        var now = Instant.now();
        assertArrayEquals(new int[] {1, 0}, setup.watchRanges(now, Duration.ZERO), "50 < 60：提醒");
        assertArrayEquals(new int[] {0, 0}, setup.watchRanges(now, Duration.ZERO), "同期同级只提醒一次");

        jdbc.update("UPDATE ivc_number_range SET next_no = end_no + 1, status = 'EXHAUSTED'");
        assertArrayEquals(new int[] {1, 0}, setup.watchRanges(now, Duration.ZERO), "用尽再提醒一次");
        assertArrayEquals(new int[] {0, 0}, setup.watchRanges(now, Duration.ZERO));

        setup.addRange(SCOPE, new NumberRangeReq("TW_EGUI", period, "AB", 51, 200, null));
        assertArrayEquals(new int[] {0, 0}, setup.watchRanges(now, Duration.ZERO));
        assertEquals(null, jdbc.queryForObject("SELECT range_alert_key FROM ivc_regime_profile", String.class),
                "补足后清掉，下次跌破重新提醒");

        var end = TwPeriods.periodEnd(period);
        assertArrayEquals(new int[] {0, 0}, setup.watchRanges(end.minus(Duration.ofDays(6)), Duration.ofDays(5)),
                "离期末还远：不查下期");
        assertArrayEquals(new int[] {1, 0}, setup.watchRanges(end.minus(Duration.ofHours(1)), Duration.ofDays(5)),
                "期末前下期没号段：提醒");
        assertArrayEquals(new int[] {0, 0}, setup.watchRanges(end.minus(Duration.ofMinutes(30)), Duration.ofDays(5)));
    }

    @Test
    void ac38AutoFetchAsksTheProviderOnceAndNeverWhileTheAccountIsHeld() {
        setup.saveProfile(SCOPE, new RegimeProfileReq("TW_EGUI", "12345675", "示例營業人", "PROV", null, null, null,
                "OFFICE-1", "pos-user", "pos-secret", 60, true));
        var now = Instant.now();
        assertArrayEquals(new int[] {0, 1}, setup.watchRanges(now, Duration.ZERO), "余量不足先自动领号，领到即不提醒");
        assertEquals(100, setup.profile(SCOPE).orElseThrow().currentRemaining());

        var end = TwPeriods.periodEnd(period);
        assertArrayEquals(new int[] {0, 1}, setup.watchRanges(end.minus(Duration.ofHours(1)), Duration.ofDays(5)),
                "期末前自动领下期");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ivc_number_range WHERE period_code = ?"
                + " AND allocation_owner = 'PROVIDER'", Integer.class, TwPeriods.periodOf(end)), "下期号段已入库");

        int calls = provider.seen.size();
        jdbc.update("UPDATE ivc_number_range SET next_no = end_no + 1, status = 'EXHAUSTED' WHERE period_code = ?",
                period);
        jdbc.update("UPDATE ivc_regime_profile SET provider_held_at = UTC_TIMESTAMP(3), range_alert_key = NULL");
        assertArrayEquals(new int[] {1, 0}, setup.watchRanges(now, Duration.ZERO), "账号暂停中：只提醒");
        assertEquals(calls, provider.seen.size(), "暂停中不打服务商");

        jdbc.update("UPDATE ivc_regime_profile SET provider_held_at = NULL, range_alert_key = NULL");
        provider.failCode = "exist";
        assertArrayEquals(new int[] {1, 0}, setup.watchRanges(now, Duration.ZERO), "领号失败转提醒");
        assertEquals(calls + 1, provider.seen.size());
        setup.watchRanges(now, Duration.ZERO);
        assertEquals(calls + 1, provider.seen.size(), "同期同级失败过就不再重复领号");
    }

    @Test
    void ac37DeadlinesWarnOnceEscalateOnceAndBacklogIsPerStore() {
        sources.paid("SO1", 1_000);
        var r = issue("SO1");
        assertEquals(48, hoursToDeadline(r.invoiceNo()), "B2C 48 小时");
        assertTrue(invoices.get(SCOPE, r.invoiceNo()).uploadDeadlineAt() != null);
        assertArrayEquals(new int[] {0, 0}, transmissions.checkDeadlines(100));

        jdbc.update("UPDATE ivc_transmission SET report_warn_at = UTC_TIMESTAMP(3) - INTERVAL 1 MINUTE");
        assertArrayEquals(new int[] {1, 0}, transmissions.checkDeadlines(100));
        assertArrayEquals(new int[] {0, 0}, transmissions.checkDeadlines(100), "同一级只报一次");
        var soon = transmissions.backlog(SCOPE);
        assertEquals(1, soon.pending());
        assertEquals(1, soon.dueSoon());

        jdbc.update("UPDATE ivc_transmission SET report_deadline_at = UTC_TIMESTAMP(3) - INTERVAL 1 MINUTE");
        assertArrayEquals(new int[] {0, 1}, transmissions.checkDeadlines(100));
        assertArrayEquals(new int[] {0, 0}, transmissions.checkDeadlines(100));
        var late = transmissions.backlog(SCOPE);
        assertEquals(1, late.overdue());
        assertEquals(0, late.dueSoon());
        assertTrue(late.oldestDeadlineAt() != null);
        assertEquals(0, transmissions.backlog(new Scope("T1", "E1", "S2", "U1")).pending(), "积压按门店");

        assertEquals(1, transmissions.transmitDue(10));
        var clear = transmissions.backlog(SCOPE);
        assertEquals(0, clear.pending() + clear.overdue() + clear.dueSoon(), "送达后退出积压");
    }

    @Test
    void ac37BusinessBuyersGetSevenDaysAndJumpingStraightToOverdueReportsOnlyOnce() {
        sources.paid("SO1", 1_000);
        var b2b = invoices.issue(SCOPE, "SUB_ORDER", "SO1", new Buyer("12345675", "示例買方", null, null, null));
        assertEquals(168, hoursToDeadline(b2b.invoiceNo()), "B2B 7 日");
        jdbc.update("UPDATE ivc_transmission SET report_warn_at = UTC_TIMESTAMP(3) - INTERVAL 2 DAY,"
                + " report_deadline_at = UTC_TIMESTAMP(3) - INTERVAL 1 DAY");
        assertArrayEquals(new int[] {0, 1}, transmissions.checkDeadlines(100), "直接跨过两级只报逾期");
    }

    private long hoursToDeadline(String invoiceNo) {
        return jdbc.queryForObject("SELECT TIMESTAMPDIFF(HOUR, created_at, report_deadline_at) FROM ivc_transmission"
                + " WHERE ref_no=?", Long.class, invoiceNo);
    }

    @Test
    void ac36AllowanceVoidReleasesTheAmountStopsPrintingAndQueuesG0501() {
        sources.paid("SO1", 105_000);
        var r = issue("SO1");
        var withAllowance = invoices.allowance(SCOPE, r.invoiceNo(), 30_000, "退一杯");
        String allowanceNo = withAllowance.allowances().getFirst().allowanceNo();
        assertCode(ErrorCode.INVOICE_STATE_INVALID, () -> invoices.voidInvoice(SCOPE, r.invoiceNo(), "x"));

        var after = invoices.voidAllowance(SCOPE, r.invoiceNo(), allowanceNo, "開錯");
        assertEquals(0, after.allowedMinor(), "累计折让回退");
        assertEquals("VOIDED", after.allowances().getFirst().status());
        assertEquals("開錯", after.allowances().getFirst().voidReason());
        String payload = jdbc.queryForObject("SELECT payload FROM ivc_transmission WHERE message_type='ALLOWANCE_VOID'"
                + " AND ref_no=?", String.class, allowanceNo);
        var g0501 = JsonMapper.builder().build().readTree(payload);
        assertEquals("G0501", g0501.path("mig").asText());
        assertEquals(r.fiscalNumber() + "01", g0501.path("CancelAllowanceNumber").asText());
        assertEquals("開錯", g0501.path("CancelReason").asText());

        var inv = invoiceRows.selectOne(new QueryWrapper<IvcInvoice>().eq("invoice_no", r.invoiceNo()));
        var a = allowanceRows.selectOne(new QueryWrapper<ai.neargo.shop.invoice.entity.IvcAllowance>()
                .eq("allowance_no", allowanceNo));
        assertEquals(GuardDecision.CANCEL_UNSUBMITTED_EXECUTION,
                printSource.executionGuard(InvoicePrintSourceFactsPortImpl.ref(inv, a)).decision(), "作废的折让单不再出纸");

        assertCode(ErrorCode.INVOICE_STATE_INVALID,
                () -> invoices.voidAllowance(SCOPE, r.invoiceNo(), allowanceNo, "again"));
        assertEquals("VOIDED", invoices.voidInvoice(SCOPE, r.invoiceNo(), "整张作废").status(),
                "折让都作废后发票本身可以作废");
    }

    @Test
    void ac36PreviousPeriodAllowanceCannotBeVoided() {
        sources.paid("SO1", 105_000);
        var r = issue("SO1");
        String allowanceNo = invoices.allowance(SCOPE, r.invoiceNo(), 30_000, "退一杯").allowances().getFirst()
                .allowanceNo();
        jdbc.update("UPDATE ivc_allowance SET created_at = DATE_SUB(created_at, INTERVAL 70 DAY)");
        assertCode(ErrorCode.INVOICE_VOID_PERIOD_CLOSED,
                () -> invoices.voidAllowance(SCOPE, r.invoiceNo(), allowanceNo, "x"));
        assertCode(ErrorCode.INVOICE_NOT_FOUND, () -> invoices.voidAllowance(SCOPE, r.invoiceNo(), "NOPE", "x"));
    }

    @Test
    void ac35AccountProblemHoldsTheEntityUntilVerified() {
        useProvider();
        sources.paid("SO1", 1_000);
        sources.paid("SO2", 1_000);
        var first = issue("SO1");
        issue("SO2");
        provider.sendCode = "invalid";
        assertEquals(0, transmissions.transmitDue(10));
        assertEquals(1, provider.sends.get(), "第一张认证失败后，同主体的第二张不再打到服务商");
        assertEquals(2, countStatus("HELD"));
        assertEquals("prov invalid", jdbc.queryForObject(
                "SELECT provider_hold_reason FROM ivc_regime_profile WHERE entity_no='E1'", String.class));
        assertEquals("HELD", invoices.get(SCOPE, first.invoiceNo()).uploadStatus());

        sources.paid("SO3", 1_000);
        issue("SO3");
        assertEquals(0, transmissions.transmitDue(10));
        assertEquals(1, provider.sends.get(), "暂停期间新开的票也直接转 HELD");
        assertEquals(3, countStatus("HELD"));
        assertTrue(setup.profile(SCOPE).orElseThrow().providerHoldReason() != null, "B 端能看到暂停原因");

        provider.sendCode = null;
        setup.verifyProvider(SCOPE);
        assertEquals(null, setup.profile(SCOPE).orElseThrow().providerHoldReason(), "核验通过即解除暂停");
        assertEquals(3, transmissions.transmitDue(10), "积压的 3 张一次补发");
    }

    @Test
    void ac35RejectedUploadWaitsForManualRetryAndSystemErrorsBackOff() {
        useProvider();
        sources.paid("SO1", 1_000);
        var r = issue("SO1");
        provider.sendCode = "M1";
        transmissions.transmitDue(10);
        assertEquals("NEEDS_REVIEW", invoices.get(SCOPE, r.invoiceNo()).uploadStatus());
        transmissions.transmitDue(10);
        assertEquals(1, provider.sends.get(), "被拒的报文不自动重发");

        provider.sendCode = null;
        assertEquals(1, transmissions.retry(SCOPE, r.invoiceNo()));
        assertEquals(1, transmissions.transmitDue(10));
        assertEquals("SENT", invoices.get(SCOPE, r.invoiceNo()).uploadStatus());
        assertCode(ErrorCode.INVOICE_STATE_INVALID, () -> transmissions.retry(SCOPE, r.invoiceNo()));

        sources.paid("SO2", 1_000);
        var r2 = issue("SO2");
        provider.sendCode = "error";
        transmissions.transmitDue(10);
        var row = jdbc.queryForMap("SELECT status, next_attempt_at > UTC_TIMESTAMP(3) AS later FROM ivc_transmission"
                + " WHERE ref_no=?", r2.invoiceNo());
        assertEquals("FAILED", row.get("status"), "服务商系统错误仍按退避重试");
        assertEquals(1L, ((Number) row.get("later")).longValue());
        assertEquals(null, setup.profile(SCOPE).orElseThrow().providerHoldReason(), "可重试错误不暂停主体");
    }

    private void useProvider() {
        setup.saveProfile(SCOPE, new RegimeProfileReq("TW_EGUI", "12345675", "示例營業人", "PROV", null, null, null,
                "OFFICE-1", "pos-user", "pos-secret", null, null));
    }

    private int countStatus(String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ivc_transmission WHERE status=?", Integer.class, status);
    }

    @Test
    void ac28ProviderRejectionsSurfaceTheProviderCode() {
        assertCode(ErrorCode.INVOICE_STATE_INVALID, () -> setup.verifyProvider(SCOPE));
        setup.saveProfile(SCOPE, new RegimeProfileReq("TW_EGUI", "12345675", "示例營業人", "PROV", null, null, null,
                "OFFICE-1", null, null, null, null));
        var noAccount = assertThrows(BizException.class, () -> setup.fetchProviderRange(SCOPE, null));
        assertEquals(ErrorCode.INVOICE_PROVIDER_REJECTED, noAccount.errorCode());
        setup.saveProfile(SCOPE, new RegimeProfileReq("TW_EGUI", "12345675", "示例營業人", "PROV", null, null, null,
                "OFFICE-1", "u", "p", null, null));
        provider.taxId = "87654321";
        assertCode(ErrorCode.INVOICE_PROVIDER_REJECTED, () -> setup.verifyProvider(SCOPE));
        provider.failCode = "invalid";
        var e = assertThrows(BizException.class, () -> setup.fetchProviderRange(SCOPE, null));
        assertEquals(ErrorCode.INVOICE_PROVIDER_REJECTED, e.errorCode());
        assertEquals(List.of("invalid"), List.of(e.args()));
    }

    @Test
    void ac12TransmissionSendsAndRetriesTheSamePayload() {
        sources.paid("SO1", 1_000);
        var r = issue("SO1");
        gateway.fail.set(true);
        assertEquals(0, transmissions.transmitDue(10));
        var failed = jdbc.queryForMap("SELECT status, attempts, next_attempt_at > UTC_TIMESTAMP(3) AS later,"
                + " last_error FROM ivc_transmission WHERE ref_no=?", r.invoiceNo());
        assertEquals("FAILED", failed.get("status"));
        assertEquals(1, ((Number) failed.get("attempts")).intValue());
        assertEquals(1L, ((Number) failed.get("later")).longValue());
        gateway.fail.set(false);
        jdbc.update("UPDATE ivc_transmission SET next_attempt_at=UTC_TIMESTAMP(3) - INTERVAL 1 SECOND");
        assertEquals(1, transmissions.transmitDue(10));
        assertEquals("SENT", invoices.get(SCOPE, r.invoiceNo()).uploadStatus());
        assertEquals(1, gateway.payloads.size(), "same payload, sent once successfully");
        assertTrue(gateway.payloads.getFirst().matches("(?s).*\"mig\":\\s*\"F0401\".*"), gateway.payloads.getFirst());
    }

    @Test
    void ac14NoRegimeMeansNoInvoice() {
        jdbc.update("UPDATE ivc_regime_profile SET status='SUSPENDED'");
        sources.paid("SO1", 1_000);
        assertCode(ErrorCode.INVOICE_REGIME_NOT_ENABLED, () -> issue("SO1"));
    }

    @Test
    void ac15ListByPeriodAndStatusInTheCurrentStore() {
        sources.paid("SO1", 1_000);
        sources.paid("SO2", 1_000);
        var a = issue("SO1");
        issue("SO2");
        invoices.voidInvoice(SCOPE, a.invoiceNo(), "x");
        assertEquals(2, invoices.list(SCOPE, new InvoiceQuery(period, null, 1, 20)).total());
        assertEquals(1, invoices.list(SCOPE, new InvoiceQuery(period, "VOIDED", 1, 20)).total());
        var other = new Scope("T1", "E1", "S2", "U1");
        assertEquals(0, invoices.list(other, new InvoiceQuery(null, null, 1, 20)).total());
        assertCode(ErrorCode.INVOICE_NOT_FOUND, () -> invoices.get(other, a.invoiceNo()));
    }

    @Test
    void autoIssueUsesTheRememberedBuyerOnlyWhenTheEntityTurnedItOn() {
        sources.paid("SO1", 1_000);
        assertTrue(invoices.issueIfAutomatic(SCOPE, "SUB_ORDER", "SO1").isEmpty(), "auto issue is off by default");
        assertCode(ErrorCode.INVOICE_BUYER_INVALID, () -> invoices.rememberBuyer(SCOPE, "SUB_ORDER", "SO1",
                new Buyer(null, null, "3J0002", "bad", null)));
        invoices.rememberBuyer(SCOPE, "SUB_ORDER", "SO1", new Buyer("22099131", "台積", null, null, null));
        invoices.rememberBuyer(SCOPE, "SUB_ORDER", "SO1", new Buyer(null, null, "3J0002", "/ABC+123", null));
        setup.saveProfile(SCOPE, new RegimeProfileReq("TW_EGUI", "12345675", "示例營業人", "TEST", null, null, true, null, null, null, null, null));
        var r = invoices.issueIfAutomatic(SCOPE, "SUB_ORDER", "SO1").orElseThrow();
        var vo = invoices.get(SCOPE, r.invoiceNo());
        assertEquals("/ABC+123", vo.carrierId(), "last remembered choice wins");
        assertEquals(null, vo.buyerTaxId(), "the earlier tax id was cleared, not kept");
        assertFalse(vo.printMark());
        assertEquals(r.invoiceNo(), invoices.issueIfAutomatic(SCOPE, "SUB_ORDER", "SO1").orElseThrow().invoiceNo());
        assertTrue(setup.profile(SCOPE).orElseThrow().autoIssue());
        assertTrue(setup.profile(SCOPE).orElseThrow().qrKeyConfigured(), "omitting the key keeps the stored one");
    }

    @Test
    void setupRejectsOverlappingRangesAndRoleSeedGotInvoicePermissions() {
        assertCode(ErrorCode.INVOICE_RANGE_INVALID,
                () -> setup.addRange(SCOPE, new NumberRangeReq("TW_EGUI", period, "AB", 40, 60, null)));
        setup.addRange(SCOPE, new NumberRangeReq("TW_EGUI", period, "AB", 51, 100, "S1"));
        assertEquals(2, setup.ranges(SCOPE, period).size());
        String manager = jdbc.queryForObject("SELECT perms FROM mch_role WHERE role_code='MANAGER'", String.class);
        String clerk = jdbc.queryForObject("SELECT perms FROM mch_role WHERE role_code='CLERK'", String.class);
        assertTrue(manager.contains("biz:invoice:setup") && manager.contains("biz:invoice:void"));
        assertTrue(clerk.contains("biz:invoice:issue") && !clerk.contains("biz:invoice:void"));
    }

    // ---------------------------------------------------------------- 载具优先（§8）

    private void memberCarrier(String type, boolean auto) {
        setup.saveProfile(SCOPE, new RegimeProfileReq("TW_EGUI", "12345675", "示例營業人", "TEST", null, type, auto, null, null, null, null, null));
    }

    @Test
    void ac20BuyerKeepsCarriersAndTheFirstOrChosenOneIsDefault() {
        var phone = buyers.saveCarrier("T1", "B1", new SaveCarrierReq("3J0002", "/ABC+123", "我的手機", null));
        assertTrue(phone.isDefault(), "the first carrier becomes default");
        var cert = buyers.saveCarrier("T1", "B1", new SaveCarrierReq("CQ0001", "AB12345678901234", null, true));
        assertTrue(cert.isDefault());
        assertEquals(List.of("CQ0001", "3J0002"), buyers.carriers("T1", "B1").stream().map(c -> c.carrierType()).toList());
        assertEquals(1, buyers.carriers("T1", "B1").stream().filter(c -> c.isDefault()).count());
        assertCode(ErrorCode.INVOICE_BUYER_INVALID,
                () -> buyers.saveCarrier("T1", "B1", new SaveCarrierReq("3J0002", "/abc1234", null, null)));
        assertTrue(buyers.carriers("T1", "B2").isEmpty(), "carriers belong to their buyer");
        assertCode(ErrorCode.INVOICE_NOT_FOUND, () -> buyers.deleteCarrier("T1", "B2", phone.carrierNo()));
        buyers.deleteCarrier("T1", "B1", phone.carrierNo());
        assertEquals(1, buyers.carriers("T1", "B1").size());
    }

    @Test
    void ac21MemberCarrierNumberIsTheLoggedInBuyerAndIssuesWithoutPaper() {
        memberCarrier("EJ0113", true);
        sources.paid("SUB1", 1_000, "B1");
        sources.orders.put("B1/ORD1", List.of(new BuyerSource("SUB_ORDER", "SUB1", "E1", "S1", false)));
        var saved = buyers.choose("T1", "B1", new InvoiceChoiceReq("ORD1", "MEMBER", null, "SOMEONE-ELSE", null, null, null));
        assertEquals("SAVED", saved.getFirst().outcome(), "unpaid: only remembered");
        var r = invoices.issueIfAutomatic(SCOPE, "SUB_ORDER", "SUB1").orElseThrow();
        var vo = invoices.getForBuyer("T1", "B1", r.invoiceNo());
        assertEquals("EJ0113", vo.carrierType());
        assertEquals("B1", vo.carrierId(), "server fills the member number; a client-sent id is ignored");
        assertFalse(vo.printMark());
        assertEquals("示例營業人", vo.sellerName());
        assertEquals(1, invoices.listForBuyer("T1", "B1", 1, 20).total());
        assertEquals(0, invoices.listForBuyer("T1", "B2", 1, 20).total());
        assertCode(ErrorCode.INVOICE_NOT_FOUND, () -> invoices.getForBuyer("T1", "B2", r.invoiceNo()));
    }

    @Test
    void ac22AutoIssueDefaultsToMemberCarrierWhenThereIsABuyerAndNoChoice() {
        memberCarrier("EJ0113", true);
        sources.paid("APP", 1_000, "B1");
        sources.paid("POS", 1_000);
        assertFalse(invoices.issueIfAutomatic(SCOPE, "SUB_ORDER", "APP").orElseThrow().printMark(),
                "app order with a buyer: member carrier, no paper");
        assertTrue(invoices.issueIfAutomatic(SCOPE, "SUB_ORDER", "POS").orElseThrow().printMark(),
                "walk-in without an account: ordinary consumer with a proof");
        memberCarrier(null, true);
        sources.paid("APP2", 1_000, "B1");
        assertTrue(invoices.issueIfAutomatic(SCOPE, "SUB_ORDER", "APP2").orElseThrow().printMark(),
                "no member carrier type: falls back to consumer");
    }

    @Test
    void ac23ChoosingOnAPaidOrderIssuesNowAndEachShopAnswersForItself() {
        memberCarrier(null, true);
        sources.paid("SUB1", 1_000, "B1");
        sources.paid("SUB2", 1_000, "B1");
        sources.orders.put("B1/ORD1", List.of(new BuyerSource("SUB_ORDER", "SUB1", "E1", "S1", true),
                new BuyerSource("SUB_ORDER", "SUB2", "E9", "S9", true)));
        var first = buyers.choose("T1", "B1", new InvoiceChoiceReq("ORD1", "CARRIER", "3J0002", "/ABC+123", null, null, null));
        assertEquals("ISSUED", first.get(0).outcome());
        assertTrue(first.get(0).fiscalNumber().startsWith("AB"));
        assertEquals("NOT_ENABLED", first.get(1).outcome(), "the other shop never enabled e-invoices");
        var again = buyers.choose("T1", "B1", new InvoiceChoiceReq("ORD1", "CONSUMER", null, null, null, null, null));
        assertEquals("ALREADY_ISSUED", again.get(0).outcome());
        var member = buyers.choose("T1", "B1", new InvoiceChoiceReq("ORD1", "MEMBER", null, null, null, null, null));
        assertEquals("ALREADY_ISSUED", member.get(0).outcome());
        sources.orders.put("B1/ORD2", List.of(new BuyerSource("SUB_ORDER", "SUB3", "E1", "S1", false)));
        assertEquals("MEMBER_CARRIER_UNAVAILABLE", buyers.choose("T1", "B1",
                new InvoiceChoiceReq("ORD2", "MEMBER", null, null, null, null, null)).getFirst().outcome());
        assertCode(ErrorCode.INVOICE_SOURCE_NOT_READY, () -> buyers.choose("T1", "B2",
                new InvoiceChoiceReq("ORD1", "CONSUMER", null, null, null, null, null)));
    }

    // ---------------------------------------------------------------- 打印（§9）

    @Test
    void ac25EveryInvoiceHasAFrozenDetailSlipAndCarrierOnesSayWhereTheInvoiceWent() {
        sources.paid("SO1", 105_000);
        var paper = issue("SO1");
        var inv = row(paper.invoiceNo());
        var detail = printSource.snapshot(InvoicePrintSourceFactsPortImpl.detailRef(inv));
        assertEquals(Printability.READY, detail.printability());
        assertEquals("SO1", detail.subOrderNo(), "invoice prints hang on the source sub-order in the print centre");
        String json = detail.immutablePayload().canonicalJson();
        assertTrue(json.contains("\"unitPrice\":\"1049\""), json);
        assertTrue(json.contains("本明細非發票"), json);
        verify(events, times(2)).publish(any(BusinessPrintFactReady.class));

        clearInvocations(events);
        sources.paid("SO2", 1_000);
        var carrier = invoices.issue(SCOPE, "SUB_ORDER", "SO2", new Buyer(null, null, "3J0002", "/ABC+123", null));
        var carrierRow = row(carrier.invoiceNo());
        assertTrue(printSource.snapshot(InvoicePrintSourceFactsPortImpl.detailRef(carrierRow)).immutablePayload()
                .canonicalJson().contains("發票已存入手機條碼載具 /ABC+123"));
        verify(events, times(1)).publish(any(BusinessPrintFactReady.class));
        var refs = printSource.referencesByOrder(new PrintScope("T1", "E1", "S1"), "SO2");
        assertEquals(List.of("INVOICE_DETAIL"), refs.stream().map(r -> r.scene()).toList(),
                "no proof for a carrier invoice, only the detail slip");
    }

    @Test
    void ac26AllowanceProofIsFrozenPrintableAndVoidStopsTheInvoiceDocuments() {
        sources.paid("SO1", 105_000);
        var r = issue("SO1");
        clearInvocations(events);
        var after = invoices.allowance(SCOPE, r.invoiceNo(), 45_000, "退一碗牛肉麵");
        String allowanceNo = after.allowances().getFirst().allowanceNo();
        verify(events).publish(any(BusinessPrintFactReady.class));
        var ref = printSource.resolveReference(new PrintSourceReference("INVOICE_ALLOWANCE", allowanceNo, allowanceNo,
                "1", "INVOICE_ALLOWANCE"), new PrintScope("T1", "E1", "S1"));
        String json = printSource.snapshot(ref).immutablePayload().canonicalJson();
        assertTrue(json.contains("\"originalNumber\":\"" + r.fiscalNumber().substring(0, 2) + "-"), json);
        assertTrue(json.contains("\"total\":\"450\""), json);
        assertTrue(json.contains("買受人簽收"), json);
        assertEquals(List.of("INVOICE_PROOF", "INVOICE_DETAIL", "INVOICE_ALLOWANCE"),
                printSource.referencesByOrder(new PrintScope("T1", "E1", "S1"), r.invoiceNo()).stream()
                        .map(x -> x.scene()).toList());

        sources.paid("SO2", 1_000);
        var other = issue("SO2");
        invoices.voidInvoice(SCOPE, other.invoiceNo(), "開錯");
        var voided = row(other.invoiceNo());
        assertEquals(GuardDecision.CANCEL_UNSUBMITTED_EXECUTION,
                printSource.executionGuard(InvoicePrintSourceFactsPortImpl.detailRef(voided)).decision());
    }

    // ---------------------------------------------------------------- helpers

    private InvoiceReceipt issue(String sourceNo) {
        return invoices.issue(SCOPE, "SUB_ORDER", sourceNo, Buyer.consumer());
    }

    private IvcInvoice row(String invoiceNo) {
        return invoiceRows.selectOne(new QueryWrapper<IvcInvoice>().eq("invoice_no", invoiceNo));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static InvoiceSourcePort.Line line(String name, long amount) {
        return line(name, amount, InvoiceSourcePort.Line.TAXABLE);
    }

    private static InvoiceSourcePort.Line line(String name, long amount, String taxType) {
        return new InvoiceSourcePort.Line(name, name, BigDecimal.ONE, null, amount, amount, taxType);
    }

    private static void assertCode(ErrorCode code, Runnable call) {
        var e = assertThrows(BizException.class, call::run);
        assertEquals(code, e.errorCode());
    }

    interface Task<T> {
        T run(int i) throws Exception;
    }

    private static <T> List<T> concurrently(int n, Task<T> task) throws Exception {
        var pool = Executors.newFixedThreadPool(n);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<T>>();
        for (int i = 0; i < n; i++) {
            int k = i;
            futures.add(pool.submit(() -> {
                start.await();
                return task.run(k);
            }));
        }
        start.countDown();
        var out = new ArrayList<T>();
        for (var f : futures) out.add(f.get());
        pool.shutdown();
        return out;
    }

    // ---------------------------------------------------------------- fixture

    static final class Db {
        static final String SCHEMA = "invoice_test_" + UUID.randomUUID().toString().replace("-", "");
        static final String URL = System.getenv("COMMERCE_MYSQL_URL");
        static final String USER = System.getenv("COMMERCE_MYSQL_USER");
        static final String PASS = System.getenv("COMMERCE_MYSQL_PASSWORD");
        static DataSource ds;
        static boolean created;

        static synchronized DataSource dataSource() {
            if (ds != null) return ds;
            assumeTrue(URL != null && URL.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/\\?.*"),
                    "COMMERCE_MYSQL_URL not set — native MySQL 9 test skipped");
            new JdbcTemplate(new DriverManagerDataSource(URL, USER, PASS)).execute("CREATE DATABASE " + SCHEMA);
            ds = new DriverManagerDataSource(URL.replace("/?", "/" + SCHEMA + "?"), USER, PASS);
            created = true;
            // V2 追加角色权限：先放一张与平台同形的 mch_role 与两条内置角色
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE mch_role (id BIGINT AUTO_INCREMENT PRIMARY KEY, entity_no VARCHAR(64),"
                    + " builtin TINYINT, role_code VARCHAR(32), deleted TINYINT, perms JSON,"
                    + " updated_at DATETIME(3), version BIGINT)");
            jdbc.update("INSERT INTO mch_role(entity_no,builtin,role_code,deleted,perms,version) VALUES"
                    + " ('*',1,'MANAGER',0,'[\"biz:print:read\"]',0),('*',1,'CLERK',0,'[]',0)");
            int applied = Flyway.configure().dataSource(ds).locations("classpath:db/invoice")
                    .table("ivc_flyway_history").baselineOnMigrate(true).baselineVersion("0").load().migrate()
                    .migrationsExecuted;
            assertEquals(9, applied);
            return ds;
        }
    }

    @AfterAll
    static void dropSchema() {
        if (Db.created)
            new JdbcTemplate(new DriverManagerDataSource(Db.URL, Db.USER, Db.PASS)).execute("DROP DATABASE " + Db.SCHEMA);
    }

    static final class Sources implements InvoiceSourcePort {
        final Map<String, InvoiceSource> byNo = new ConcurrentHashMap<>();

        final Map<String, List<BuyerSource>> orders = new ConcurrentHashMap<>();

        void paid(String no, long total) {
            paid(no, total, null);
        }

        void paid(String no, long total, String buyer) {
            byNo.put(no, new InvoiceSource("FOOD", "TWD", Instant.now(), total,
                    List.of(line("牛肉麵", total - 100), line("小菜", 100)), buyer));
        }

        public List<BuyerSource> sourcesOfBuyerOrder(String tenantNo, String buyer, String orderNo) {
            return orders.getOrDefault(buyer + "/" + orderNo, List.of());
        }

        public Set<String> sourceTypes() {
            return Set.of("SUB_ORDER");
        }

        public InvoiceSource load(Scope scope, String type, String no) {
            var s = byNo.get(no);
            if (s == null) throw BizException.of(ErrorCode.INVOICE_SOURCE_NOT_READY);
            return s;
        }
    }

    static final class FlakyGateway implements InvoiceGateway {
        final AtomicBoolean fail = new AtomicBoolean();
        final List<String> payloads = new ArrayList<>();

        public String code() {
            return "TEST";
        }

        public GatewayReceipt send(GatewayRequest request) {
            if (fail.get()) throw new IllegalStateException("gateway down");
            payloads.add(request.payloadJson());
            return GatewayReceipt.accepted("G-" + payloads.size());
        }
    }

    /** 加值中心桩：记下收到的账号，核验回固定统编与 QR 金钥，配号回固定一段。 */
    static final class FakeProvider implements InvoiceProviderGateway {
        final List<InvoiceGateway.ProviderAccount> seen = new ArrayList<>();
        volatile String taxId = "12345675";
        volatile String failCode;
        /** 上传回执：null = 成功；invalid = 账号问题；error = 可重试；其它 = 被拒 */
        volatile String sendCode;
        final java.util.concurrent.atomic.AtomicInteger sends = new java.util.concurrent.atomic.AtomicInteger();

        public String code() {
            return "PROV";
        }

        public GatewayReceipt send(GatewayRequest request) {
            seen.add(request.account());
            sends.incrementAndGet();
            String code = sendCode;
            if (code == null) return GatewayReceipt.accepted("P-" + request.refNo());
            return switch (code) {
                case "invalid" -> GatewayReceipt.accountProblem("prov invalid");
                case "error" -> GatewayReceipt.retryable("prov error");
                default -> GatewayReceipt.rejected("prov " + code);
            };
        }

        public ProviderOffice verify(InvoiceGateway.ProviderAccount account) {
            seen.add(account);
            if (failCode != null) throw new ProviderException(failCode, null);
            return new ProviderOffice(taxId, "示例營業人", KEY_HEX);
        }

        public AllocatedRange allocate(InvoiceGateway.ProviderAccount account, String periodCode) {
            seen.add(account);
            if (failCode != null) throw new ProviderException(failCode, null);
            return new AllocatedRange("APPLY-1", "CD", 12_345_600, 12_345_649, "07");
        }
    }

    static final class MemoryVault implements SecretVault {
        final Map<Long, byte[]> blobs = new ConcurrentHashMap<>();
        final AtomicLong ids = new AtomicLong();

        public SecretRef seal(KeyOwner owner, byte[] plaintext) {
            long id = ids.incrementAndGet();
            blobs.put(id, plaintext.clone());
            return new SecretRef(id);
        }

        public byte[] open(SecretNamespace namespace, SecretRef ref) {
            return blobs.get(ref.blobId()).clone();
        }
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackages = "ai.neargo.shop.invoice.mapper", sqlSessionFactoryRef = "sqlSessionFactory")
    @Import({InvoiceServiceImpl.class, InvoiceSetupServiceImpl.class, InvoiceProviderAccounts.class, InvoiceBuyerServiceImpl.class, InvoiceTransmissionServiceImpl.class,
            InvoiceRegimes.class, TwEguiRegime.class, InvoicePrintSourceFactsPortImpl.class})
    static class Config {
        @Bean
        DataSource dataSource() {
            return Db.dataSource();
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            var interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
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
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        OutboxEventBus outboxEventBus() {
            return mock(OutboxEventBus.class);
        }

        @Bean
        Sources sources() {
            return new Sources();
        }

        @Bean
        FlakyGateway gateway() {
            return new FlakyGateway();
        }

        @Bean
        FakeProvider provider() {
            return new FakeProvider();
        }

        @Bean
        SecretVault vault() {
            return new MemoryVault();
        }
    }
}
