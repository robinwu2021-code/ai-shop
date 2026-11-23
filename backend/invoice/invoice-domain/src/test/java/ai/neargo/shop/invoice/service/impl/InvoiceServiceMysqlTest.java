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
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsInvoiceQuery;
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsSellerQuery;
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsSellerVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsTransmissionQuery;
import ai.neargo.shop.invoice.dto.InvoiceDtos.RegimeProfileReq;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.gateway.InvoiceGateway;
import ai.neargo.shop.invoice.gateway.AccountCapability;
import ai.neargo.shop.invoice.gateway.InvoiceGateways;
import ai.neargo.shop.invoice.gateway.ProviderException;
import ai.neargo.shop.invoice.gateway.ProviderProfile;
import ai.neargo.shop.invoice.gateway.QueryCapability;
import ai.neargo.shop.invoice.gateway.RevokeStatusCapability;
import ai.neargo.shop.invoice.gateway.RangeCapability;
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
import org.springframework.test.context.TestPropertySource;
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
@TestPropertySource(properties = "shop.invoice.regimes.TW_EGUI.default-gateway=TEST")
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
    @Autowired ai.neargo.shop.invoice.service.InvoiceOpsService ops;
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
        provider.uploads.clear();
        provider.blanks.clear();
        provider.trackFailCode = null;
        provider.revokeStates.clear();
        provider.revokeChecks.clear();
        provider.declared = FakeProvider.DEFAULT_PROFILE;
        provider.preflightReason = null;
        provider.failKind = null;
        provider.rangeQrKey = null;
        provider.queryState = QueryCapability.ProviderInvoiceStatus.State.ISSUED;
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

    /**
     * 打统编（B2B）时上传报文的应税明细用未税单价 / 金额（汎宇《POS API 測試注意事項》「F0401才用未稅」：有统编 75 → 71）；
     * 免税、零税率明细不变；应税明细未税金额合计等于主档应税销售额，尾差放在最后一条应税明细。B2C 仍送含税。
     */
    @Test
    void b2bTaxableLinesAreSentUntaxedAndAddUpToTheSalesAmount() throws Exception {
        sources.byNo.put("MIX", new InvoiceSourcePort.InvoiceSource("FOOD", "TWD", Instant.now(), 15_000,
                List.of(line("咖啡", 7_500, InvoiceSourcePort.Line.TAXABLE),
                        line("書籍", 7_500, InvoiceSourcePort.Line.EXEMPT)), null));
        var mix = json(payloadOf(invoices.issue(SCOPE, "SUB_ORDER", "MIX", new Buyer("22099131", null, null, null, null))
                .invoiceNo()));
        var items = mix.path("Details").path("ProductItem");
        assertEquals("71", items.get(0).path("UnitPrice").asText(), "应税明细未税");
        assertEquals("71", items.get(0).path("Amount").asText());
        assertEquals("75", items.get(1).path("UnitPrice").asText(), "免税明细不变");
        assertEquals("75", items.get(1).path("Amount").asText());
        assertEquals(71, mix.path("Amount").path("SalesAmount").asLong());
        assertEquals(75, mix.path("Amount").path("FreeTaxSalesAmount").asLong());
        assertEquals(4, mix.path("Amount").path("TaxAmount").asLong());
        assertEquals(150, mix.path("Amount").path("TotalAmount").asLong());

        // 逐行四舍五入合计 99，主档 100：尾差进最后一条应税明细；数量 2 时单价 = 未税金额 / 数量
        sources.byNo.put("ROUND", new InvoiceSourcePort.InvoiceSource("FOOD", "TWD", Instant.now(), 10_500,
                List.of(line("甲", 3_300, InvoiceSourcePort.Line.TAXABLE), line("乙", 3_300, InvoiceSourcePort.Line.TAXABLE),
                        new InvoiceSourcePort.Line("丙", "丙", BigDecimal.valueOf(2), null, 1_950, 3_900,
                                InvoiceSourcePort.Line.TAXABLE)), null));
        var round = json(payloadOf(invoices.issue(SCOPE, "SUB_ORDER", "ROUND",
                new Buyer("22099131", null, null, null, null)).invoiceNo()));
        var r = round.path("Details").path("ProductItem");
        assertEquals(List.of("31", "31", "38"), List.of(r.get(0).path("Amount").asText(), r.get(1).path("Amount").asText(),
                r.get(2).path("Amount").asText()));
        assertEquals("19", r.get(2).path("UnitPrice").asText(), "38 / 2");
        assertEquals(100, round.path("Amount").path("SalesAmount").asLong());
        assertEquals(5, round.path("Amount").path("TaxAmount").asLong());

        sources.byNo.put("B2C", new InvoiceSourcePort.InvoiceSource("FOOD", "TWD", Instant.now(), 7_500,
                List.of(line("咖啡", 7_500, InvoiceSourcePort.Line.TAXABLE)), null));
        var b2c = json(payloadOf(issue("B2C").invoiceNo())).path("Details").path("ProductItem").get(0);
        assertEquals("75", b2c.path("UnitPrice").asText(), "B2C 仍送含税");
        assertEquals("75", b2c.path("Amount").asText());
    }

    private String payloadOf(String invoiceNo) {
        return jdbc.queryForObject("SELECT payload FROM ivc_transmission WHERE message_type='ISSUE' AND ref_no=?",
                String.class, invoiceNo);
    }

    private static tools.jackson.databind.JsonNode json(String text) {
        return JsonMapper.builder().build().readTree(text);
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
        assertEquals("pos-secret", provider.seen.getFirst().secret(), "上传时现解账号");
        assertEquals("P-" + r.invoiceNo(), jdbc.queryForObject(
                "SELECT gateway_ref FROM ivc_transmission WHERE ref_no=?", String.class, r.invoiceNo()));
    }

    /** AC43：发票打印模拟取本张票的打印件 —— 应印的证明联、交易明细、未作废的折让证明单；作废的票不出；别店的票查不到。 */
    @Test
    void ac43PrintFactsListTheDocumentsThisInvoiceCanPrint() {
        sources.paid("SO1", 105_000);
        var r = issue("SO1");
        var facts = invoices.printFacts(SCOPE, r.invoiceNo());
        assertEquals(List.of("INVOICE_PROOF", "INVOICE_DETAIL"), facts.stream().map(f -> f.scene()).toList());
        var proof = facts.getFirst().factRef();
        assertEquals("INVOICE", proof.sourceType());
        assertEquals(r.invoiceNo(), proof.factNo());
        assertEquals(row(r.invoiceNo()).getProofSnapshotHash(), proof.snapshotHash(), "引用的就是冻结的那份内容");

        var allowed = invoices.allowance(SCOPE, r.invoiceNo(), 10_000, "退一杯");
        var allowanceNo = allowed.allowances().getFirst().allowanceNo();
        var withAllowance = invoices.printFacts(SCOPE, r.invoiceNo());
        assertEquals("INVOICE_ALLOWANCE", withAllowance.getLast().scene());
        assertEquals(allowanceNo, withAllowance.getLast().docNo());
        assertEquals("INVOICE_ALLOWANCE", withAllowance.getLast().factRef().sourceType());

        sources.paid("SO2", 1_000);
        var voided = issue("SO2");
        invoices.voidInvoice(SCOPE, voided.invoiceNo(), "開錯");
        assertEquals(List.of(), invoices.printFacts(SCOPE, voided.invoiceNo()), "作废的票不出证明联与明细");
        assertCode(ErrorCode.INVOICE_NOT_FOUND,
                () -> invoices.printFacts(new Scope("T1", "E1", "S2", "U1"), r.invoiceNo()));
    }

    /**
     * §24 AC-P7：POS 收款来源（PAY_ORDER / OFFLINE_RECORD）走同一条通用链路 —— 同来源二次开票回原票、
     * 来源键带类型（同号不同类型是两张票）、作废后可重开、打印事实照出。
     */
    @Test
    void acP7PosSourcesIssueOnceVoidAndPrintLikeSubOrder() {
        sources.paid("POS1", 12_300);
        var record = invoices.issue(SCOPE, "OFFLINE_RECORD", "POS1", Buyer.consumer());
        assertEquals(12_300L, row(record.invoiceNo()).getTotalMinor());
        assertEquals(record.invoiceNo(), invoices.issue(SCOPE, "OFFLINE_RECORD", "POS1", Buyer.consumer()).invoiceNo());
        var payment = invoices.issue(SCOPE, "PAY_ORDER", "POS1", Buyer.consumer());
        assertNotEquals(record.invoiceNo(), payment.invoiceNo(), "来源键是 类型:单号");
        assertEquals(payment.invoiceNo(), invoices.issue(SCOPE, "PAY_ORDER", "POS1", Buyer.consumer()).invoiceNo());
        assertEquals(2, count("ivc_invoice"));
        assertEquals("OFFLINE_RECORD:POS1", row(record.invoiceNo()).getActiveSourceKey());
        assertEquals(List.of("INVOICE_PROOF", "INVOICE_DETAIL"),
                invoices.printFacts(SCOPE, payment.invoiceNo()).stream().map(f -> f.scene()).toList());

        invoices.voidInvoice(SCOPE, payment.invoiceNo(), "開錯");
        var again = invoices.issue(SCOPE, "PAY_ORDER", "POS1", Buyer.consumer());
        assertNotEquals(payment.fiscalNumber(), again.fiscalNumber());
        assertCode(ErrorCode.INVOICE_SOURCE_NOT_READY,
                () -> invoices.issue(SCOPE, "OFFLINE_RECORD", "NOPE", Buyer.consumer()));
    }

    /** AC40（数据域，§18.7）：运营会话只看自己商家范围内的主体；空范围一个都看不到；不限则全部可见。 */
    @Test
    void ac40OpsViewsAreNarrowedToTheStaffMerchantScope() {
        var e2 = new Scope("T1", "E2", "S9", "U1");
        setup.saveProfile(e2, new RegimeProfileReq("TW_EGUI", "53212539", "第二家", "TEST", null, null, null,
                null, null, null, null, null));
        sources.paid("SO1", 1_000);
        var r = issue("SO1");   // E1 的一张票与一条上传任务
        var sellerQ = new OpsSellerQuery(null, null, null, null, null);
        var taskQ = new OpsTransmissionQuery(null, null, null, null, null);
        var invoiceQ = new OpsInvoiceQuery(null, null, null, null, null, null, null);

        assertEquals(List.of("E1", "E2"), ops.sellers("T1", null, sellerQ).records().stream()
                .map(OpsSellerVO::entityNo).toList(), "不限 = 全部");
        assertEquals(List.of("E2"), ops.sellers("T1", Set.of("E2"), sellerQ).records().stream()
                .map(OpsSellerVO::entityNo).toList());
        assertEquals(0, ops.transmissions("T1", Set.of("E2"), taskQ).total(), "E1 的任务对 E2 范围不可见");
        assertEquals(0, invoices.listForOps("T1", Set.of("E2"), invoiceQ).total());
        assertEquals(1, ops.transmissions("T1", Set.of("E1"), taskQ).total());
        assertEquals(r.invoiceNo(), invoices.listForOps("T1", Set.of("E1"), invoiceQ).records().getFirst().invoiceNo());
        assertEquals(0, ops.sellers("T1", Set.of(), sellerQ).total(), "只配了社区 / 自提点维度 = 一个都看不到");
        assertEquals(0, ops.transmissions("T1", Set.of(), taskQ).total());
        assertEquals(0, invoices.listForOps("T1", Set.of(), invoiceQ).total());
    }

    /**
     * AC42：系统默认市场与供应商；商家可在该市场支持的供应商里自选（迁移过来的商户）；改回跟随默认；
     * 开出的票冻结在当时那家，之后切换不改走别家，查单也去当时那家查。
     */
    @Test
    void ac42DefaultMarketAndProviderWithMerchantChoiceFrozenAtIssue() {
        var options = setup.providerOptions(null);
        assertEquals("TW_EGUI", options.regimeCode(), "不传市场 = 系统默认市场");
        assertEquals("TEST", options.defaultGatewayCode());
        assertEquals(List.of("TEST", "PROV"), options.options().stream().map(o -> o.code()).toList(), "默认的排第一");
        var prov = options.options().get(1);
        assertTrue(prov.requiresAccount() && prov.verifiable() && prov.rangeProvided() && prov.queryable());
        assertTrue(options.options().getFirst().isDefault() && !options.options().getFirst().requiresAccount());

        var followed = setup.saveProfile(SCOPE, new RegimeProfileReq(null, "12345675", "示例營業人", null, null, null,
                null, null, null, null, null, null));
        assertEquals("TW_EGUI", followed.regimeCode());
        assertEquals("TEST", followed.gatewayCode());
        assertEquals("DEFAULT", followed.gatewaySource());
        assertEquals(null, jdbc.queryForObject("SELECT gateway_code FROM ivc_regime_profile", String.class),
                "跟随默认存空值：默认一换，它就跟着换");
        assertCode(ErrorCode.INVOICE_GATEWAY_UNSUPPORTED, () -> setup.saveProfile(SCOPE, new RegimeProfileReq(
                "TW_EGUI", "12345675", "示例營業人", "NOPE", null, null, null, null, null, null, null, null)));

        useProvider();
        assertEquals("MERCHANT", setup.profile(SCOPE).orElseThrow().gatewaySource());
        sources.paid("SO1", 1_000);
        var first = issue("SO1");
        setup.saveProfile(SCOPE, new RegimeProfileReq("TW_EGUI", "12345675", "示例營業人", "", null, null, null,
                "OFFICE-1", null, null, null, null));
        assertEquals("DEFAULT", setup.profile(SCOPE).orElseThrow().gatewaySource(), "空串 = 改回跟随默认");
        sources.paid("SO2", 1_000);
        var second = issue("SO2");
        assertEquals("PROV", jdbc.queryForObject("SELECT gateway_code FROM ivc_transmission WHERE ref_no=?",
                String.class, first.invoiceNo()), "开出的票冻结在当时的供应商");
        assertEquals("TEST", jdbc.queryForObject("SELECT gateway_code FROM ivc_transmission WHERE ref_no=?",
                String.class, second.invoiceNo()));
        provider.seen.clear();
        transmissions.checkWithProvider(SCOPE, first.invoiceNo());
        assertEquals(1, provider.seen.size(), "查单去当时那家查，哪怕主体已换到不能查单的默认供应商");
    }

    @Test
    void ac40OpsSeesEverySellerWithBacklogAndRangesAndFiltersByProblem() {
        var e2 = new Scope("T1", "E2", "S9", "U9");
        setup.saveProfile(e2, new RegimeProfileReq("TW_EGUI", "53212539", "第二家", "PROV", null, null, null,
                "OFFICE-2", "u", "p", null, null));
        sources.paid("SO1", 105_000);
        var r = issue("SO1");
        invoices.allowance(SCOPE, r.invoiceNo(), 30_000, "退一杯");
        jdbc.update("UPDATE ivc_transmission SET report_deadline_at = UTC_TIMESTAMP(3) - INTERVAL 1 MINUTE"
                + " WHERE message_type = 'ISSUE'");

        var all = ops.sellers("T1", null, new OpsSellerQuery(null, null, null, null, null)).records();
        assertEquals(List.of("E1", "E2"), all.stream().map(OpsSellerVO::entityNo).toList());
        var e1 = all.getFirst();
        assertEquals(2, e1.pending(), "开立 + 折让两条未送达");
        assertEquals(1, e1.overdue());
        assertEquals(50, e1.currentRemaining() + 1, "AB 1–50 已用掉 1 张");
        assertEquals(0, all.get(1).currentRemaining());

        assertEquals(List.of("E1"), entities(new OpsSellerQuery(null, "OVERDUE", null, null, null)));
        assertEquals(List.of("E1", "E2"), entities(new OpsSellerQuery(null, "LOW_RANGE", null, null, null)),
                "E1 剩 49 < 50，E2 没号段");
        assertEquals(List.of("E2"), entities(new OpsSellerQuery("PROV", null, null, null, null)));
        assertEquals(List.of("E2"), entities(new OpsSellerQuery(null, null, "53212539", null, null)));
        jdbc.update("UPDATE ivc_regime_profile SET provider_held_at = UTC_TIMESTAMP(3), provider_hold_reason = 'x'"
                + " WHERE entity_no = 'E2'");
        assertEquals(List.of("E2"), entities(new OpsSellerQuery(null, "HELD", null, null, null)));
        assertEquals(1, ops.sellers("T1", null, new OpsSellerQuery(null, null, null, 2, 1)).records().size(), "分页");

        var late = ops.transmissions("T1", null, new OpsTransmissionQuery(null, null, true, null, null)).records();
        assertEquals(1, late.size());
        assertEquals(r.fiscalNumber(), late.getFirst().fiscalNumber());
        assertTrue(late.getFirst().overdue());
        var allowanceTask = ops.transmissions("T1", null, new OpsTransmissionQuery("E1", null, null, null, null)).records()
                .stream().filter(t -> "ALLOWANCE".equals(t.messageType())).findFirst().orElseThrow();
        assertEquals(r.invoiceNo(), allowanceTask.invoiceNo(), "折让任务对回原发票");

        assertEquals(1, invoices.listForOps("T1", null, new OpsInvoiceQuery(null, null, r.fiscalNumber(), null, null, null,
                null)).total());
        assertEquals(new Scope("T1", "E1", "S1", "OPS1"), invoices.scopeOf("T1", r.invoiceNo(), "OPS1"));
        assertCode(ErrorCode.INVOICE_NOT_FOUND, () -> invoices.scopeOf("T1", "NOPE", "OPS1"));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_function_point"
                + " WHERE point_code = 'OPS_FINANCE__E_INVOICES' AND href = '/e-invoices'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_point"
                + " WHERE point_code = 'OPS_FINANCE__E_INVOICES'", Integer.class), "超管与财务");
    }

    private List<String> entities(OpsSellerQuery q) {
        return ops.sellers("T1", null, q).records().stream().map(OpsSellerVO::entityNo).toList();
    }

    @Test
    void ac39ProviderCheckReconcilesDeliveredUploadsAndFlagsMismatches() {
        sources.paid("SO1", 1_000);
        var r = issue("SO1");
        assertCode(ErrorCode.INVOICE_STATE_INVALID, () -> transmissions.checkWithProvider(SCOPE, r.invoiceNo()));

        useProvider();
        sources.paid("SO2", 1_000);
        var r2 = issue("SO2");
        provider.sendCode = "S1";   // 上传回「失败」→ 待人工；实际上鲸跃已收到
        transmissions.transmitDue(10);
        assertEquals("NEEDS_REVIEW", invoices.get(SCOPE, r2.invoiceNo()).uploadStatus());

        var check = transmissions.checkWithProvider(SCOPE, r2.invoiceNo());
        assertEquals(List.of("ISSUE"), check.reconciled(), "鲸跃已有这张票：补记已送达");
        assertEquals("CONSISTENT", check.verdict());
        assertEquals("SENT", invoices.get(SCOPE, r2.invoiceNo()).uploadStatus());
        assertEquals(List.of(), transmissions.checkWithProvider(SCOPE, r2.invoiceNo()).reconciled(), "已送达不重复补记");

        provider.queryState = QueryCapability.ProviderInvoiceStatus.State.NOT_FOUND;
        assertEquals("MISMATCH", transmissions.checkWithProvider(SCOPE, r2.invoiceNo()).verdict(),
                "本地已送达、鲸跃查无此票：告警");

        invoices.voidInvoice(SCOPE, r2.invoiceNo(), "開錯");
        provider.queryState = QueryCapability.ProviderInvoiceStatus.State.ISSUED;
        assertEquals("PENDING_UPLOAD", transmissions.checkWithProvider(SCOPE, r2.invoiceNo()).verdict(),
                "作废还没送达：差异只是待补传");
        provider.queryState = QueryCapability.ProviderInvoiceStatus.State.VOIDED;
        var voided = transmissions.checkWithProvider(SCOPE, r2.invoiceNo());
        assertEquals(List.of("VOID"), voided.reconciled());
        assertEquals("CONSISTENT", voided.verdict());

        provider.failCode = "invalid";
        var e = assertThrows(BizException.class, () -> transmissions.checkWithProvider(SCOPE, r2.invoiceNo()));
        assertEquals(ErrorCode.INVOICE_PROVIDER_REJECTED, e.errorCode());
        assertCode(ErrorCode.INVOICE_NOT_FOUND, () -> transmissions.checkWithProvider(SCOPE, "NOPE"));
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

    /** P2-AC1/AC2：字轨查询（配号方式 + 已下载字轨）、上传下期字轨与区间校验。 */
    @Test
    void p2ProviderTracksQueryAndUploadValidateBeforeCallingTheProvider() {
        useProvider();
        var tracks = setup.providerTracks(SCOPE, null);
        assertEquals(period, tracks.periodCode(), "不传期别取当期");
        assertEquals("MANUAL", tracks.allocationMode());
        assertEquals(List.of(new ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderTrackVO("07", "AB", 0, 99)), tracks.tracks());

        String next = TwPeriods.periodOf(TwPeriods.periodEnd(period));
        var ok = setup.uploadProviderTracks(SCOPE, trackReq(next, 10_000_050, 10_050_099));
        assertEquals(1001, ok.books(), "10000050–10050099 共 50050 号");
        var sent = provider.uploads.getFirst();
        assertEquals(next, sent.periodCode());
        assertEquals("12345675", sent.sellerTaxId(), "分配统编 = 卖方统编");
        assertEquals(10_000_050, sent.ranges().getFirst().startNo());

        provider.uploads.clear();
        assertCode(ErrorCode.INVOICE_RANGE_INVALID, () -> setup.uploadProviderTracks(SCOPE, trackReq(period, 0, 49)));
        assertCode(ErrorCode.INVOICE_RANGE_INVALID, () -> setup.uploadProviderTracks(SCOPE, trackReq(next, 1, 50)));
        assertCode(ErrorCode.INVOICE_RANGE_INVALID, () -> setup.uploadProviderTracks(SCOPE, trackReq(next, 0, 48)));
        assertCode(ErrorCode.INVOICE_RANGE_INVALID,
                () -> setup.uploadProviderTracks(SCOPE, trackReq(next, 0, 50 * 10_000 - 1)));   // 超过 9999 本
        assertTrue(provider.uploads.isEmpty(), "不合规不打服务商");

        provider.trackFailCode = "失敗：字軌區間不存在";
        var e = assertThrows(BizException.class, () -> setup.uploadProviderTracks(SCOPE, trackReq(next, 0, 49)));
        assertEquals(ErrorCode.INVOICE_PROVIDER_REJECTED, e.errorCode());
        assertEquals(List.of("失敗：字軌區間不存在"), List.of(e.args()));
    }

    /** P2-AC3：上一期未用号码按字轨合并上报一次；失败记原因，下轮重试。 */
    @Test
    void p2BlankRangesAreReportedOncePerRangeAndRetriedOnFailure() {
        useProvider();
        jdbc.update("UPDATE ivc_number_range SET next_no=21");      // AB 1–50 用掉 1–20
        var after = TwPeriods.periodEnd(period).plus(Duration.ofHours(1));   // 下一期开始后：上一期 = period

        provider.trackFailCode = "S030";
        assertEquals(0, setup.reportBlankRanges(after));
        assertEquals("S030", jdbc.queryForObject("SELECT blank_report_error FROM ivc_number_range", String.class),
                "失败记原因");
        assertEquals(null, jdbc.queryForObject("SELECT blank_reported_at FROM ivc_number_range", Object.class));

        provider.trackFailCode = null;
        assertEquals(1, setup.reportBlankRanges(after));
        var report = provider.blanks.getFirst();
        assertEquals(period, report.periodCode());
        assertEquals("AB", report.track());
        assertEquals("07", report.invoiceType());
        assertEquals("12345675", report.sellerTaxId());
        assertEquals(List.of(new ai.neargo.shop.invoice.gateway.TrackCapability.TrackRange("07", "AB", 21, 50)),
                report.ranges(), "只报未用的 21–50");
        assertEquals(null, jdbc.queryForObject("SELECT blank_report_error FROM ivc_number_range", String.class));

        assertEquals(0, setup.reportBlankRanges(after), "同一段不重复上报");
        assertEquals(0, setup.reportBlankRanges(Instant.now()), "当期还没结束：上一期没有号段");
        assertEquals(1, provider.blanks.size());
    }

    /** P2-AC4/AC5：供应商没有字轨能力时接口拒绝、巡检跳过；可选列表带 trackManageable。 */
    @Test
    void p2ProvidersWithoutTrackCapabilityAreRejectedAndSkipped() {
        assertCode(ErrorCode.INVOICE_STATE_INVALID, () -> setup.providerTracks(SCOPE, null));
        assertEquals(0, setup.reportBlankRanges(TwPeriods.periodEnd(period).plus(Duration.ofHours(1))));
        var options = setup.providerOptions("TW_EGUI").options();
        assertTrue(options.stream().anyMatch(o -> o.code().equals("PROV") && o.trackManageable()));
        assertTrue(options.stream().anyMatch(o -> o.code().equals("TEST") && !o.trackManageable()));
    }

    private static ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderTrackUploadReq trackReq(String periodCode, int start,
            int end) {
        return new ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderTrackUploadReq(periodCode, "07", "AB",
                List.of(new ai.neargo.shop.invoice.dto.InvoiceDtos.TrackSpanReq(start, end)));
    }

    // ---- 注销与同号重开（TDD-发票-注销与同号重开） ----

    static final Duration TEN_MIN = Duration.ofMinutes(10), DAY = Duration.ofHours(24), FIVE_DAYS = Duration.ofDays(5);

    /** 每次查进度前把注销中的发票 updated_at 拨回，模拟「距上次检查已超过间隔」。 */
    private int[] checkRevocationsNow() {
        jdbc.update("UPDATE ivc_invoice SET updated_at = DATE_SUB(updated_at, INTERVAL 1 HOUR) WHERE status='REVOKING'");
        return transmissions.checkRevocations(Instant.now(), TEN_MIN, DAY, FIVE_DAYS);
    }

    private String issuedAndSent(String sourceNo) {
        sources.paid(sourceNo, 1_000);
        var no = issue(sourceNo).invoiceNo();
        transmissions.transmitDue(10);
        return no;
    }

    /** AC1 / AC2 / AC5：前置校验、注销中冻结作废折让打印、REVOKE 任务入队。 */
    @Test
    void revokeChecksPreconditionsFreezesTheInvoiceAndQueuesF0701() {
        useProvider();
        sources.paid("SO1", 1_000);
        var no = issue("SO1").invoiceNo();
        assertFalse(invoices.get(SCOPE, no).revocable(), "开立还没送达");
        assertCode(ErrorCode.INVOICE_STATE_INVALID, () -> invoices.revoke(SCOPE, no, "買方填錯"));
        transmissions.transmitDue(10);
        assertTrue(invoices.get(SCOPE, no).revocable());

        var v = invoices.revoke(SCOPE, no, "買方填錯");
        assertEquals("REVOKING", v.status());
        assertEquals("買方填錯", v.revocation().reason());
        assertFalse(v.revocable());
        var payload = jdbc.queryForObject("SELECT payload FROM ivc_transmission WHERE message_type='REVOKE' AND ref_no=?",
                String.class, no);
        assertTrue(payload.contains("F0701") && payload.contains("買方填錯"), payload);   // JSON 列会重排空白

        assertCode(ErrorCode.INVOICE_STATE_INVALID, () -> invoices.revoke(SCOPE, no, "again"));
        assertCode(ErrorCode.INVOICE_STATE_INVALID, () -> invoices.voidInvoice(SCOPE, no, "x"));
        assertCode(ErrorCode.INVOICE_STATE_INVALID, () -> invoices.allowance(SCOPE, no, 100, "x"));
        assertTrue(invoices.printFacts(SCOPE, no).isEmpty(), "注销中不出证明联与明细");

        var withAllowance = issuedAndSent("SO2");
        invoices.allowance(SCOPE, withAllowance, 100, "退一份");
        assertCode(ErrorCode.INVOICE_STATE_INVALID, () -> invoices.revoke(SCOPE, withAllowance, "x"));
    }

    /** AC2：开票时的供应商不支持注销（TEST 桩）时拒绝。 */
    @Test
    void revokeIsRejectedWhenTheFrozenGatewayCannotRevoke() {
        sources.paid("SO1", 1_000);
        var no = issue("SO1").invoiceNo();
        transmissions.transmitDue(10);
        assertFalse(invoices.get(SCOPE, no).revocable());
        assertCode(ErrorCode.INVOICE_STATE_INVALID, () -> invoices.revoke(SCOPE, no, "x"));
    }

    /** AC3 / AC4：进度 PENDING → DONE 释放号码；同号重开生成新票并互相关联；不能重开两次；不取新号。 */
    @Test
    void revokedInvoiceIsReissuedUnderTheSameNumber() {
        useProvider();
        var no = issuedAndSent("SO1");
        var fiscal = invoices.get(SCOPE, no).fiscalNumber();
        int nextBefore = jdbc.queryForObject("SELECT next_no FROM ivc_number_range", Integer.class);
        invoices.revoke(SCOPE, no, "買方填錯");
        assertArrayEquals(new int[] {0, 0, 0}, checkRevocationsNow(), "注销报文还没送达：不查");
        transmissions.transmitDue(10);

        assertArrayEquals(new int[] {0, 0, 0}, checkRevocationsNow(), "PENDING：不动");
        provider.revokeStates.add(RevokeStatusCapability.RevokeState.DONE);
        assertArrayEquals(new int[] {1, 0, 0}, checkRevocationsNow());
        assertEquals(fiscal + "@" + TwPeriods.local(Instant.now()).toLocalDate(), provider.revokeChecks.getLast());
        assertEquals("REVOKED", invoices.get(SCOPE, no).status());
        assertEquals(null, jdbc.queryForObject("SELECT active_fiscal_key FROM ivc_invoice WHERE invoice_no=?", String.class, no));

        var fresh = invoices.reissue(SCOPE, no, new Buyer("53212539", "示例買方股份有限公司", null, null, null));
        assertEquals(fiscal, fresh.fiscalNumber(), "同号重开");
        assertNotEquals(no, fresh.invoiceNo());
        assertEquals("ISSUED", fresh.status());
        assertEquals("B2B", fresh.buyerType());
        assertEquals(no, fresh.revocation().revisedFromInvoiceNo());
        assertEquals(fresh.invoiceNo(), invoices.get(SCOPE, no).revocation().revisedToInvoiceNo());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ivc_transmission WHERE message_type='ISSUE' AND ref_no=?",
                Integer.class, fresh.invoiceNo()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ivc_invoice WHERE fiscal_number=?", Integer.class, fiscal));
        assertCode(ErrorCode.INVOICE_STATE_INVALID, () -> invoices.reissue(SCOPE, no, null));
        assertEquals(nextBefore, jdbc.queryForObject("SELECT next_no FROM ivc_number_range", Integer.class),
                "重开不取新号");
    }

    /** AC3：服务商注销失败回到已开立并可再次注销（复用同一条 REVOKE 任务）；上传被拒同样回到已开立。 */
    @Test
    void failedRevocationReturnsToIssuedAndCanBeRevokedAgain() {
        useProvider();
        var no = issuedAndSent("SO1");
        invoices.revoke(SCOPE, no, "第一次");
        transmissions.transmitDue(10);
        provider.revokeStates.add(RevokeStatusCapability.RevokeState.FAILED);
        assertArrayEquals(new int[] {0, 1, 0}, checkRevocationsNow());
        var back = invoices.get(SCOPE, no);
        assertEquals("ISSUED", back.status());
        assertEquals("provider revoke failed", back.revocation().revokeError());
        assertTrue(back.revocable());

        invoices.revoke(SCOPE, no, "第二次");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ivc_transmission WHERE message_type='REVOKE'", Integer.class));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM ivc_transmission WHERE message_type='REVOKE'", String.class));
        provider.sendCode = "S040";
        transmissions.transmitDue(10);
        var rejected = invoices.get(SCOPE, no);
        assertEquals("ISSUED", rejected.status(), "注销上传被拒：回到已开立");
        assertTrue(rejected.revocation().revokeError().contains("S040"), rejected.revocation().revokeError());
    }

    /** AC7：注销中超时、期末未重开各提醒一次；期末已注销未重开的号码进空白上报；AC4：跨期不能重开。 */
    @Test
    void revocationAlertsCrossPeriodReissueAndBlankReport() {
        useProvider();
        var no = issuedAndSent("SO1");
        var fiscal = invoices.get(SCOPE, no).fiscalNumber();
        invoices.revoke(SCOPE, no, "x");
        transmissions.transmitDue(10);
        jdbc.update("UPDATE ivc_invoice SET revoke_requested_at = DATE_SUB(revoke_requested_at, INTERVAL 2 DAY)");
        assertArrayEquals(new int[] {0, 0, 1}, checkRevocationsNow(), "注销中超过 24 小时");
        assertArrayEquals(new int[] {0, 0, 0}, checkRevocationsNow(), "只提醒一次");

        provider.revokeStates.add(RevokeStatusCapability.RevokeState.DONE);
        checkRevocationsNow();
        var end = TwPeriods.periodEnd(period);
        assertArrayEquals(new int[] {0, 0, 1}, transmissions.checkRevocations(end.minus(Duration.ofDays(1)), TEN_MIN, DAY,
                FIVE_DAYS), "期末前未重开提醒");
        assertArrayEquals(new int[] {0, 0, 0}, transmissions.checkRevocations(end.minus(Duration.ofHours(1)), TEN_MIN, DAY,
                FIVE_DAYS));

        assertEquals(1, setup.reportBlankRanges(end.plus(Duration.ofHours(1))));
        var spans = provider.blanks.getFirst().ranges();
        int n = Integer.parseInt(fiscal.substring(2));
        assertTrue(spans.contains(new ai.neargo.shop.invoice.gateway.TrackCapability.TrackRange("07", "AB", n, n)),
                "已注销未重开的号码作空白上报：" + spans);
        assertEquals(0, setup.reportBlankRanges(end.plus(Duration.ofHours(1))), "只报一次");

        jdbc.update("UPDATE ivc_invoice SET period_code='11002' WHERE invoice_no=?", no);
        assertCode(ErrorCode.INVOICE_VOID_PERIOD_CLOSED, () -> invoices.reissue(SCOPE, no, null));
    }

    // ---- 供应商能力定义完善（TDD-发票-供应商能力定义完善） ----

    /** D2：预检不通过时整笔回滚 —— 不落发票、不占号、不入上传任务。 */
    @Test
    void capPreflightRejectionRollsBackWithoutUsingANumber() {
        useProvider();
        sources.paid("SO1", 1_000);
        provider.preflightReason = "zero-rated invoices are not supported";
        var e = assertThrows(BizException.class, () -> issue("SO1"));
        assertEquals(ErrorCode.INVOICE_PROVIDER_UNSUPPORTED, e.errorCode());
        assertEquals(List.of("PROV", "zero-rated invoices are not supported"), List.of(e.args()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ivc_invoice", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ivc_transmission", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT next_no FROM ivc_number_range", Integer.class), "号码没占用");
        provider.preflightReason = null;
        assertEquals("AB00000001", issue("SO1").fiscalNumber(), "放行后同一号码照常开出");
    }

    /** D4：账号类失败暂停该主体（与上传同一机制），核验通过即解除；其它分类不暂停。 */
    @Test
    void capAccountFailuresHoldTheEntityOthersDoNot() {
        useProvider();
        provider.failCode = "busy";
        provider.failKind = ProviderException.Kind.UNAVAILABLE;
        assertCode(ErrorCode.INVOICE_PROVIDER_REJECTED, () -> setup.fetchProviderRange(SCOPE, null));
        assertEquals(null, setup.profile(SCOPE).orElseThrow().providerHoldReason(), "不可达不暂停");

        provider.failCode = "invalid";
        provider.failKind = ProviderException.Kind.ACCOUNT;
        assertCode(ErrorCode.INVOICE_PROVIDER_REJECTED, () -> setup.fetchProviderRange(SCOPE, null));
        assertEquals("invalid", setup.profile(SCOPE).orElseThrow().providerHoldReason(), "账号问题：暂停");

        provider.failCode = null;
        provider.failKind = null;
        setup.verifyProvider(SCOPE);
        assertEquals(null, setup.profile(SCOPE).orElseThrow().providerHoldReason(), "核验通过解除");
    }

    /** D6：期别窗口外直接拒绝、不打服务商；D5：领号回传的 QR 金钥只在声明 RANGE 时存。 */
    @Test
    void capRangeWindowAndRangeQrKeyFollowTheDeclaration() {
        useProvider();
        provider.declared = FakeProvider.DEFAULT_PROFILE.withRangeWindows(
                java.util.Set.of(ai.neargo.shop.invoice.gateway.RangeWindow.CURRENT,
                        ai.neargo.shop.invoice.gateway.RangeWindow.NEXT));
        var e = assertThrows(BizException.class, () -> setup.fetchProviderRange(SCOPE, "11002"));
        assertEquals(ErrorCode.INVOICE_PROVIDER_UNSUPPORTED, e.errorCode());
        assertTrue(provider.seen.isEmpty(), "窗口外不打服务商");

        jdbc.update("UPDATE ivc_regime_profile SET qr_key_ref=NULL");
        provider.rangeQrKey = KEY_HEX;
        setup.fetchProviderRange(SCOPE, period);
        assertFalse(setup.profile(SCOPE).orElseThrow().qrKeyConfigured(), "默认商家手填：领号回传的不当金钥");
        provider.declared = provider.declared.withQrKeySource(ai.neargo.shop.invoice.gateway.QrKeySource.RANGE);
        setup.fetchProviderRange(SCOPE, period);
        assertTrue(setup.profile(SCOPE).orElseThrow().qrKeyConfigured(), "声明 RANGE：存进 secret");
    }

    /** D3：代号由卖方统编带出（不必填、填了别的值拒绝）；有格式要求的校验格式。 */
    @Test
    void capCredentialDeclarationDerivesAndValidatesTheAccountCode() {
        provider.declared = FakeProvider.DEFAULT_PROFILE.withCredentials(List.of(
                new ai.neargo.shop.invoice.gateway.CredentialField(
                        ai.neargo.shop.invoice.gateway.CredentialField.Slot.ACCOUNT_CODE, "sellerId", false, null,
                        ai.neargo.shop.invoice.gateway.CredentialField.Derived.SELLER_TAX_ID)));
        var saved = setup.saveProfile(SCOPE, new RegimeProfileReq("TW_EGUI", "12345675", "示例營業人", "PROV", null, null,
                null, null, "pos", "possn", null, null));
        assertEquals("12345675", saved.providerAccountCode(), "不填也由统编带出");
        assertCode(ErrorCode.INVOICE_PROVIDER_ACCOUNT_INVALID, () -> setup.saveProfile(SCOPE, new RegimeProfileReq(
                "TW_EGUI", "12345675", "示例營業人", "PROV", null, null, null, "99999999", null, null, null, null)));

        provider.declared = FakeProvider.DEFAULT_PROFILE.withCredentials(List.of(
                new ai.neargo.shop.invoice.gateway.CredentialField(
                        ai.neargo.shop.invoice.gateway.CredentialField.Slot.ACCOUNT_CODE, "officeId", false,
                        "[A-Z]+-\\d+", ai.neargo.shop.invoice.gateway.CredentialField.Derived.NONE)));
        assertEquals("OFFICE-1", setup.saveProfile(SCOPE, new RegimeProfileReq("TW_EGUI", "12345675", "示例營業人", "PROV",
                null, null, null, "OFFICE-1", null, null, null, null)).providerAccountCode());
        assertCode(ErrorCode.INVOICE_PROVIDER_ACCOUNT_INVALID, () -> setup.saveProfile(SCOPE, new RegimeProfileReq(
                "TW_EGUI", "12345675", "示例營業人", "PROV", null, null, null, "office", null, null, null, null)));
    }

    /** D1：可选供应商带能力集合与声明；布尔字段由能力推导。 */
    @Test
    void capProviderOptionsExposeCapabilitiesAndDeclarations() {
        var options = setup.providerOptions("TW_EGUI").options();
        var prov = options.stream().filter(o -> o.code().equals("PROV")).findFirst().orElseThrow();
        assertEquals(List.of("VERIFY_ACCOUNT", "ALLOCATE_RANGE", "QUERY_STATUS", "MANAGE_TRACKS", "REPORT_BLANK", "REVOKE"),
                prov.capabilities());
        assertTrue(prov.verifiable() && prov.rangeProvided() && prov.queryable() && prov.trackManageable());
        assertEquals(List.of("ACCOUNT_CODE", "KEY_ID", "SECRET"), prov.credentials().stream().map(c -> c.slot()).toList());
        assertEquals("MERCHANT", prov.qrKeySource());
        assertEquals(List.of("ANY"), prov.rangeWindows());
        var test = options.stream().filter(o -> o.code().equals("TEST")).findFirst().orElseThrow();
        assertEquals(List.of(), test.capabilities());
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
            // V10 运营端菜单写平台的功能点 / 角色点：放两张与平台同形（只含用到的列与唯一键）的表
            jdbc.execute("CREATE TABLE sys_function_point (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " point_code VARCHAR(64) NOT NULL UNIQUE, function_code VARCHAR(64), name VARCHAR(64),"
                    + " group_name VARCHAR(64), href VARCHAR(255), ui_perm_code VARCHAR(64), perm_code VARCHAR(64),"
                    + " backend_status VARCHAR(32), ui_ready TINYINT, matrix_code VARCHAR(32), point_type VARCHAR(16),"
                    + " sort INT, created_at DATETIME, updated_at DATETIME)");
            jdbc.execute("CREATE TABLE sys_role_point (id BIGINT AUTO_INCREMENT PRIMARY KEY, role_code VARCHAR(64),"
                    + " point_code VARCHAR(64), end_code VARCHAR(16), created_at DATETIME, updated_at DATETIME,"
                    + " UNIQUE KEY uk_role_point (role_code, point_code, end_code))");
            int applied = Flyway.configure().dataSource(ds).locations("classpath:db/invoice")
                    .table("ivc_flyway_history").baselineOnMigrate(true).baselineVersion("0").load().migrate()
                    .migrationsExecuted;
            assertEquals(14, applied); // V1–V14（V14：电子发票菜单点归入「应付发票」）
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
            return Set.of("SUB_ORDER", "PAY_ORDER", "OFFLINE_RECORD");
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

        public ProviderProfile profile() {
            return new ProviderProfile(java.util.Set.of("TW_EGUI"), ProviderProfile.NumberingMode.LOCAL_RANGE,
                    ProviderProfile.ReportMode.ASYNC_UPLOAD, false);
        }

        public GatewayReceipt send(GatewayRequest request) {
            if (fail.get()) throw new IllegalStateException("gateway down");
            payloads.add(request.payloadJson());
            return GatewayReceipt.accepted("G-" + payloads.size());
        }
    }

    /** 加值中心桩：记下收到的账号，核验回固定统编与 QR 金钥，配号回固定一段。 */
    static final class FakeProvider implements InvoiceGateway, AccountCapability, RangeCapability, QueryCapability,
            ai.neargo.shop.invoice.gateway.TrackCapability, ai.neargo.shop.invoice.gateway.BlankRangeCapability,
            RevokeStatusCapability {
        /** Z11 依次回的进度；空 = PENDING */
        final java.util.Deque<RevokeState> revokeStates = new java.util.concurrent.ConcurrentLinkedDeque<>();
        final List<String> revokeChecks = new ArrayList<>();
        final List<ai.neargo.shop.invoice.gateway.TrackCapability.TrackUpload> uploads = new ArrayList<>();
        final List<ai.neargo.shop.invoice.gateway.BlankRangeCapability.BlankReport> blanks = new ArrayList<>();
        volatile String trackFailCode;
        final List<InvoiceGateway.ProviderAccount> seen = new ArrayList<>();
        volatile String taxId = "12345675";
        volatile String failCode;
        /** 上传回执：null = 成功；invalid = 账号问题；error = 可重试；其它 = 被拒 */
        volatile String sendCode;
        final java.util.concurrent.atomic.AtomicInteger sends = new java.util.concurrent.atomic.AtomicInteger();

        public String code() {
            return "PROV";
        }

        static final ProviderProfile DEFAULT_PROFILE = new ProviderProfile(java.util.Set.of("TW_EGUI"),
                ProviderProfile.NumberingMode.PROVIDER_RANGE, ProviderProfile.ReportMode.ASYNC_UPLOAD, true)
                .withUploads(java.util.Set.of(ai.neargo.shop.invoice.gateway.ProviderOperation.ISSUE,
                        ai.neargo.shop.invoice.gateway.ProviderOperation.VOID,
                        ai.neargo.shop.invoice.gateway.ProviderOperation.ALLOWANCE,
                        ai.neargo.shop.invoice.gateway.ProviderOperation.ALLOWANCE_VOID,
                        ai.neargo.shop.invoice.gateway.ProviderOperation.REVOKE));
        /** 测试按需改声明（期别窗口、QR 金钥来源、凭据字段） */
        volatile ProviderProfile declared = DEFAULT_PROFILE;
        /** 非空 = 预检不通过的原因 */
        volatile String preflightReason;
        /** 能力动作失败的分类；空 = 旧写法（未分类） */
        volatile ProviderException.Kind failKind;
        /** 领号随段回传的 QR 金钥 */
        volatile String rangeQrKey;

        public ProviderProfile profile() {
            return declared;
        }

        public java.util.Optional<String> preflight(GatewayRequest request) {
            return java.util.Optional.ofNullable(preflightReason);
        }

        private ProviderException failure() {
            return failKind == null ? new ProviderException(failCode, null)
                    : new ProviderException(failKind, failCode, null, null);
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
            if (failCode != null) throw failure();
            return new ProviderOffice(taxId, "示例營業人", KEY_HEX);
        }

        volatile QueryCapability.ProviderInvoiceStatus.State queryState =
                QueryCapability.ProviderInvoiceStatus.State.ISSUED;

        public ProviderInvoiceStatus queryStatus(InvoiceGateway.ProviderAccount account, String fiscalNumber,
                java.time.LocalDate invoiceDate) {
            seen.add(account);
            if (failCode != null) throw new ProviderException(failCode, null);
            return new ProviderInvoiceStatus(queryState, queryState.name());
        }

        public AllocatedRange allocate(InvoiceGateway.ProviderAccount account, String periodCode) {
            seen.add(account);
            if (failCode != null) throw failure();
            return new AllocatedRange("APPLY-1", "CD", 12_345_600, 12_345_649, "07", rangeQrKey);
        }

        public AllocationMode allocationMode(InvoiceGateway.ProviderAccount account, String periodCode) {
            seen.add(account);
            if (trackFailCode != null) throw new ProviderException(trackFailCode, null);
            return AllocationMode.MANUAL;
        }

        public List<TrackRange> downloadedTracks(InvoiceGateway.ProviderAccount account, String periodCode) {
            seen.add(account);
            if (trackFailCode != null) throw new ProviderException(trackFailCode, null);
            return List.of(new TrackRange("07", "AB", 0, 99));
        }

        public void uploadNextTracks(InvoiceGateway.ProviderAccount account, TrackUpload upload) {
            seen.add(account);
            if (trackFailCode != null) throw new ProviderException(trackFailCode, null);
            uploads.add(upload);
        }

        public RevokeState revokeStatus(InvoiceGateway.ProviderAccount account, String fiscalNumber,
                java.time.LocalDate invoiceDate) {
            seen.add(account);
            revokeChecks.add(fiscalNumber + "@" + invoiceDate);
            var next = revokeStates.poll();
            return next == null ? RevokeState.PENDING : next;
        }

        public void reportBlank(InvoiceGateway.ProviderAccount account, BlankReport report) {
            seen.add(account);
            if (trackFailCode != null) throw new ProviderException(trackFailCode, null);
            blanks.add(report);
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
    @Import({InvoiceServiceImpl.class, InvoiceSetupServiceImpl.class, InvoiceProviderAccounts.class, InvoiceOpsServiceImpl.class, InvoiceBuyerServiceImpl.class, InvoiceTransmissionServiceImpl.class, InvoiceGateways.class, InvoiceGatewayPolicy.class,
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
