package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.trade.entity.OrdPricingFundingSource;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.mockito.Answers;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort.State;
import ai.neargo.shop.spi.product.GoodsPricingPolicyFacts;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Component;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.trade.dto.PricingPreparation.Prepared;
import ai.neargo.shop.trade.dto.PricingPreparation.RuntimeConfig;
import ai.neargo.shop.trade.entitlement.port.EntitlementSourceDecisionPortImpl;
import ai.neargo.shop.trade.mapper.TradeMappers.PricingFundingSourceMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.EntitlementSourceDecisionMapper;
import ai.neargo.shop.trade.policy.PricingRuntimeConfigPolicy;
import ai.neargo.shop.trade.service.PricingPreparationService;
import ai.neargo.shop.trade.service.PricingFundingSourceService;
import ai.neargo.shop.trade.service.impl.PricingFundingSourceServiceImpl;
import ai.neargo.shop.trade.service.impl.EntitlementSourceDecisionServiceImpl;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.BenefitSelection;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot.Source;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingFacts;
import ai.neargo.shop.common.pricing.PricingRuleFacts;
import ai.neargo.shop.common.pricing.PricingRuleFacts.UnitPolicy;
import ai.neargo.shop.common.pricing.PricingPortion;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.idem.SysIdempotentMapper;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.assembler.PricingQuoteAssembler;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Allocation;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Amounts;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Context;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Funder;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Funding;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Industry;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Intent;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Kind;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Line;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.LineKind;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Phase;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.SaveReq;
import ai.neargo.shop.trade.entity.OrdPriceQuoteAllocation;
import ai.neargo.shop.trade.mapper.TradeMappers.PriceQuoteMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.PriceQuoteLineMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.PriceQuoteAllocationMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StorePricingConfigMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StorePricingRevisionMapper;
import ai.neargo.shop.trade.service.impl.PricingQuoteServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/** Native InnoDB, actual CHECK/unique failures, transaction rollback and races; never a business database. */
@EnabledIfEnvironmentVariable(named = "PRICING_TEST_DISPOSABLE", matches = "true")
class PricingQuotePersistenceTest {
    private static JdbcTemplate jdbc;
    private static SqlSessionTemplate session;
    private static DataSourceTransactionManager manager;
    private static IdempotencyService receipts;
    private static final PricingQuoteAssembler ASSEMBLER = new PricingQuoteAssembler();
    private static final Instant NOW = Instant.parse("2026-10-23T04:00:00Z");
    private PricingQuoteServiceImpl service;
    private final Context context = new Context("MAIN", "M1", "S1", "U1", "ACT");

    @BeforeAll
    static void setup() throws Exception {
        String url = System.getenv("PRICING_TEST_JDBC_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/pricing_test_[0-9a-f]+");
        new JdbcTemplate(new DriverManagerDataSource(url, "root", ""))
                .execute("CREATE DATABASE " + url.substring(url.lastIndexOf('/') + 1) + "_quote");
        var data = new DriverManagerDataSource(url + "_quote", "root", "");
        jdbc = new JdbcTemplate(data);
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).contains("MariaDB");
        for (String file : List.of("V546__pricing_configuration_publication.sql", "V550__pricing_quote_snapshots.sql",
                "V552__pricing_funding_source.sql")) {
            for (String sql : Files.readString(Path.of("../shop-app/src/main/resources/db/migration/" + file))
                    .replaceAll("(?m)^--[^\\r\\n]*", "").split(";")) {
                if (!sql.isBlank()) jdbc.execute(sql);
            }
        }
        String decisionSchema = Files.readString(Path.of("../../docs/technical/design/活动权益-support-schema.sql"));
        jdbc.execute(decisionSchema.substring(decisionSchema.indexOf("CREATE TABLE ord_ent_source_decision"),
                decisionSchema.indexOf("CREATE TABLE ord_card_sale_snapshot")).strip().replaceFirst(";\\s*$", ""));
        jdbc.execute("CREATE TABLE sys_idempotent(id BIGINT AUTO_INCREMENT PRIMARY KEY,idem_key VARCHAR(128),"
                + "endpoint VARCHAR(128),user_no VARCHAR(64),request_hash CHAR(64),result_json LONGTEXT,"
                + "created_at DATETIME,expire_at DATETIME,UNIQUE(idem_key,endpoint)) ENGINE=InnoDB");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (var mapper : List.of(PriceQuoteMapper.class, PriceQuoteLineMapper.class, PriceQuoteAllocationMapper.class,
                StorePricingConfigMapper.class, StorePricingRevisionMapper.class, SysIdempotentMapper.class,
                PricingFundingSourceMapper.class, EntitlementSourceDecisionMapper.class)) {
            configuration.addMapper(mapper);
        }
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(data);
        factory.setConfiguration(configuration);
        session = new SqlSessionTemplate(factory.getObject());
        manager = new DataSourceTransactionManager(data);
        receipts = new IdempotencyService(session.getMapper(SysIdempotentMapper.class), JsonMapper.builder().build());
    }

    @BeforeEach
    void reset() {
        for (String table : List.of("ord_pricing_funding_source", "ord_ent_source_decision", "ord_price_quote_allocation", "ord_price_quote_line", "ord_price_quote",
                "sys_idempotent", "ord_store_pricing_config", "ord_store_pricing_revision")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO ord_store_pricing_config(tenant_no,created_at,created_by,updated_at,updated_by,"
                + "config_no,entity_no,store_no,industry_code,template_no,status,active_revision_no,"
                + "draft_template_revision_no,draft_override,draft_schema_version,draft_version) "
                + "VALUES('MAIN',NOW(),'ACT',NOW(),'ACT','PC1','M1','S1','FOOD','PT1','ACTIVE','PR1','TR1','{}',3,0)");
        jdbc.update("INSERT INTO ord_store_pricing_revision(tenant_no,created_at,created_by,updated_at,updated_by,"
                + "revision_no,config_no,revision,entity_no,store_no,industry_code,template_revision_no,"
                + "override_snapshot,effective_snapshot,schema_version,effective_hash,engine_spec_version,"
                + "rule_catalog_version,published_at,published_by,publish_reason) "
                + "VALUES('MAIN',NOW(),'ACT',NOW(),'ACT','PR1','PC1',1,'M1','S1','FOOD','TR1','{}',"
                + "'{\"schemaVersion\":3}',3,?,'ENGINE_V1','V5',NOW(),'ACT','native fixture')", "a".repeat(64));
        service = build(NOW, session.getMapper(PriceQuoteAllocationMapper.class));
    }

    @Test
    void quoteIsAtomicImmutableAndScoped() {
        var quote = service.save(context, draft(false, 120), "SAVE");
        assertThat(count("ord_price_quote")).isEqualTo(1);
        assertThat(count("ord_price_quote_line")).isEqualTo(1);
        assertThat(count("ord_price_quote_allocation")).isEqualTo(1);
        assertThat(service.get(context, quote.quoteNo())).isEqualTo(quote);
        for (Context wrong : List.of(new Context("MAIN", "M2", "S1", "U1", "ACT"),
                new Context("MAIN", "M1", "S2", "U1", "ACT"),
                new Context("MAIN", "M1", "S1", "U2", "ACT"),
                new Context("MAIN", "M1", "S1", null, "ACT"))) {
            assertThatThrownBy(() -> service.get(wrong, quote.quoteNo())).isInstanceOf(BizException.class);
        }
        assertThat(service.save(context, draft(false, 120), "SAVE").quoteNo()).isEqualTo(quote.quoteNo());
        assertThat(count("ord_price_quote")).isEqualTo(1);
    }

    @Test
    void actualAllocationCheckFailureRollsBackHeaderLinesAndReceipt() {
        var allocationMapper = mock(PriceQuoteAllocationMapper.class);
        when(allocationMapper.insert(any(OrdPriceQuoteAllocation.class))).thenAnswer(call -> {
            OrdPriceQuoteAllocation row = call.getArgument(0);
            row.setAmountMinor(-1L);
            return session.getMapper(PriceQuoteAllocationMapper.class).insert(row);
        });
        var failing = build(NOW, allocationMapper);
        assertThatThrownBy(() -> failing.save(context, draft(false, 120), "FAIL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String table : List.of("ord_price_quote", "ord_price_quote_line", "ord_price_quote_allocation",
                "sys_idempotent")) assertThat(count(table)).isZero();
    }

    @Test
    void economicsHashExcludesRandomKeysAndLifetimeButIncludesFundingIdentity() {
        var first = service.save(context, draft(false, 120), "A");
        var second = build(NOW.plusSeconds(1), session.getMapper(PriceQuoteAllocationMapper.class))
                .save(context, draft(false, 240), "B");
        assertThat(first.quoteNo()).isNotEqualTo(second.quoteNo());
        assertThat(first.expiresAt()).isNotEqualTo(second.expiresAt());
        assertThat(first.economicsHash()).isEqualTo(second.economicsHash());
        var req = draft(false, 120);
        var old = req.allocations().getFirst();
        var changed = allocation(old.portion(), old.kind(), 0, 10000, 1000, "DIFFERENT_FUNDER");
        var different = copy(req, req.lines(), List.of(changed), req.intent(), req.tax());
        var other = service.save(context, different, "C");
        assertThat(other.economicsHash()).isNotEqualTo(first.economicsHash());
        assertThat(other.factsHash()).isEqualTo(first.factsHash());
        assertThat(other.requestHash()).isEqualTo(first.requestHash());
    }

    @Test
    void retainedAndSupersededLifecycleNeverUpdatesEconomicsOrDeletesEvidence() {
        var first = service.save(context, draft(false, 120), "A");
        var second = service.save(context, draft(false, 120), "B");
        assertThat(service.supersede(context, first.quoteNo(), second.quoteNo()).status()).isEqualTo("SUPERSEDED");
        var retained = service.retain(context, second.quoteNo(), "ORIGINAL_INTENT");
        assertThat(retained.resultSnapshot()).isEqualTo(second.resultSnapshot());
        assertThat(service.retain(context, second.quoteNo(), "ORIGINAL_INTENT")).isEqualTo(retained);
        assertThatThrownBy(() -> service.retain(context, second.quoteNo(), "OTHER_INTENT"))
                .isInstanceOf(BizException.class);
        var third = service.save(context, draft(false, 120), "C");
        assertThatThrownBy(() -> service.supersede(context, second.quoteNo(), third.quoteNo()))
                .isInstanceOf(BizException.class);
        var later = build(NOW.plusSeconds(120), session.getMapper(PriceQuoteAllocationMapper.class));
        assertThat(later.get(context, second.quoteNo()).status()).isEqualTo("EXPIRED");
        assertThat(later.retain(context, second.quoteNo(), "ORIGINAL_INTENT").retainedRef()).isEqualTo("ORIGINAL_INTENT");
        assertThatThrownBy(() -> later.retain(context, third.quoteNo(), "LATE"))
                .isInstanceOf(BizException.class);
        assertThat(count("ord_price_quote")).isEqualTo(3);
        assertThat(later.get(context, second.quoteNo()).resultSnapshot()).isEqualTo(second.resultSnapshot());
    }

    @Test
    void currentConfigAndIdempotencyCannotBeForgedAndSuccessfulReplaySurvivesPointerChange() {
        var original = service.save(context, draft(false, 120), "A");
        assertThatThrownBy(() -> service.save(new Context("MAIN", "M1", "S1", "U1", "OTHER"),
                draft(false, 120), "A")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.save(context, draft(false, 121), "A")).isInstanceOf(BizException.class);
        jdbc.update("UPDATE ord_store_pricing_config SET active_revision_no='OTHER' WHERE config_no='PC1'");
        assertThat(service.save(context, draft(false, 120), "A").quoteNo()).isEqualTo(original.quoteNo());
        assertThatThrownBy(() -> service.save(context, draft(false, 120), "NEW"))
                .isInstanceOf(BizException.class);
        assertThat(count("ord_price_quote")).isEqualTo(1);
    }

    @Test
    void parallelSameCommandCreatesOneCompleteQuote() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls = List.of(1, 2).stream().map(i -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                return service.save(context, draft(false, 120), "RACE").quoteNo();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(calls.get(0).get(20, TimeUnit.SECONDS)).isEqualTo(calls.get(1).get(20, TimeUnit.SECONDS));
        }
        assertThat(count("ord_price_quote")).isEqualTo(1);
        assertThat(count("ord_price_quote_line")).isEqualTo(1);
        assertThat(count("ord_price_quote_allocation")).isEqualTo(1);
    }

    @Test
    void invoiceTaxRoundingAndMissingOrDuplicateAllocationsFailBeforeAnyWrites() {
        var req = draft(false, 120);
        var line = req.lines().getFirst();
        var wrongTax = new Amounts(10000, 0, 0, 1000, 0, 0, 0, 0, 9000, 451, 9451, 0);
        var invalid = copy(req, List.of(new Line(line.source(), null, line.kind(), line.taxClass(), wrongTax, line.invoiceBaseMinor(), line.eligibility())),
                req.allocations(), req.intent(), req.tax());
        for (SaveReq bad : List.of(invalid, copy(req, req.lines(), List.of(), req.intent(), req.tax()),
                copy(req, req.lines(), List.of(req.allocations().getFirst(), req.allocations().getFirst()),
                        req.intent(), req.tax()))) {
            assertThatThrownBy(() -> service.save(context, bad, "BAD"))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        }
        assertThat(count("ord_price_quote")).isZero();
    }

    @Test
    void inclusiveTaxAndCashIntentRemainFrozenWithoutClaimingAReceipt() {
        var quote = service.save(context, draft(true, 120), "INCLUSIVE");
        assertThat(quote.resultSnapshot()).contains("\"taxMinor\":429", "\"receivableMinor\":9000");
        assertThat(jdbc.queryForObject("SELECT cash_change_minor FROM ord_price_quote", Long.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT remaining_due_minor FROM ord_price_quote", Long.class)).isZero();
        assertThat(quote.status()).isEqualTo("READY");
    }

    @Test
    void electronicPlanCannotAuthorizeCashChangeAndNoCashDueCannotReturnTender() {
        var inclusive = draft(true, 120);
        var cash = inclusive.allocations().get(1);
        var electronic = allocation(cash.portion(), Kind.OTHER_PLAN, 1, cash.basisMinor(), cash.amountMinor(), "CARD");
        var invalid = copy(inclusive, inclusive.lines(), List.of(inclusive.allocations().getFirst(), electronic),
                inclusive.intent(), inclusive.tax());
        assertThatThrownBy(() -> service.save(context, invalid, "CARD_CHANGE")).isInstanceOf(BizException.class);
        var request = draft(false, 120);
        var noCash = copy(request, request.lines(), request.allocations(),
                new Intent(request.intent().selection(), new Funding(0, 0, 0, 1000L)), request.tax());
        assertThatThrownBy(() -> service.save(context, noCash, "NO_CASH")).isInstanceOf(BizException.class);
        assertThat(count("ord_price_quote")).isZero();
    }

    @Test
    void pointsCannotBeBothDiscountAndConsiderationEvenAcrossDifferentReferences() {
        var req = draft(false, 120);
        var old = req.lines().getFirst();
        var a = new Amounts(10000, 0, 0, 0, 0, 1000, 0, 0, 9000, 450, 9450, 500);
        var discounts = allocation(PricingPortion.from(old.source()), Kind.POINTS_DISCOUNT, 0, 10000, 1000, "P1");
        var consideration = allocation(PricingPortion.from(old.source()), Kind.POINTS_CONSIDERATION, 1, 9450, 500, "P2");
        var invalid = copy(req, List.of(new Line(old.source(), null, old.kind(), old.taxClass(), a, old.invoiceBaseMinor(), old.eligibility())),
                List.of(discounts, consideration), req.intent(), req.tax());
        assertThatThrownBy(() -> service.save(context, invalid, "POINTS")).isInstanceOf(BizException.class);
        assertThat(count("ord_price_quote")).isZero();
    }

    private static final String PREPARATION_HASH = "d".repeat(64);
    private static final Credentials BUYER = new Credentials(null, null, "U1");
    private final EntitlementContext internal = new EntitlementContext("MAIN", "ACT", "REQUEST", "e".repeat(64));

    private SaveReq fundingDraft() throws Exception {
        var old = draft(false, 120);
        var unit = old.lines().getFirst().source();
        var amounts = new Amounts(10000, 0, 0, 0, 0, 0, 0, 0, 10000, 500, 10500, 0);
        var rows = PricingRuntimeFixture.rows();
        jdbc.update("UPDATE ord_store_pricing_revision SET effective_snapshot=?", rows.revision().getEffectiveSnapshot());
        return new SaveReq(old.source(), Industry.FOOD, "H5", Phase.LIVE, "PR1", "TR1", "ENGINE_V1", old.facts(),
                old.tax(), old.intent(), List.of(new Line(unit, null, LineKind.GOODS, TaxClass.STANDARD,
                amounts, 10000, policy(unit))), List.of(), 120);
    }
    private Prepared fundingPreparation(SaveReq draft) throws Exception {
        var rows = PricingRuntimeFixture.rows();
        var config = PricingRuntimeConfigPolicy.verify(rows.revision(), rows.template(), PricingRuntimeFixture.CAPS);
        var scope = new Scope("MAIN", "M1", "S1");
        var source = PricingSourceFacts.freeze(scope, "U1", draft.source(), "H5", "b".repeat(64),
                List.of(new Component(PricingSourceFactsPort.Kind.MAIN,
                        draft.lines().getFirst().source())));
        var rules = new RuntimeConfig(scope, "FOOD", "PC1", "PR1", 1, "TR1", "ENGINE_V1", "CATALOG",
                config, null, "c".repeat(64));
        var policies = GoodsPricingPolicyFacts.freeze(new GoodsPricingPolicyPort.Scope("MAIN", "M1", "S1"),
                List.of(new GoodsPricingPolicyPort.Policy("POLICY", "G1", "SKU1", 1, true, TaxClass.STANDARD)));
        return new Prepared(source, rules, draft.tax(), policies, NOW.plusSeconds(100), PREPARATION_HASH);
    }
    private PricingFundingSourceServiceImpl journal(Instant time, Prepared prepared, PricingFundingSourceMapper mapper) {
        var preparations = mock(PricingPreparationService.class);
        when(preparations.confirmCustomer(any(), any(), any())).thenReturn(prepared);
        return new PricingFundingSourceServiceImpl(session.getMapper(PriceQuoteMapper.class), mapper, service,
                preparations, new EntitlementSourceDecisionServiceImpl(session.getMapper(EntitlementSourceDecisionMapper.class),
                JsonMapper.builder().build()), manager, Clock.fixed(time, ZoneOffset.UTC));
    }
    private PricingFundingSourceServiceImpl journal(Instant time, Prepared prepared) {
        return journal(time, prepared, session.getMapper(PricingFundingSourceMapper.class));
    }
    private EntitlementSourceDecisionPortImpl reader(PricingFundingSourceService journal) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("journal", journal);
        return new EntitlementSourceDecisionPortImpl(null, session.getMapper(EntitlementSourceDecisionMapper.class),
                JsonMapper.builder().build(), beans.getBeanProvider(PricingFundingSourceService.class));
    }

    @Test void fundingJournalRetainsQuoteAndDuplicateStageKeepsOneEconomicSource() throws Exception {
        var draft = fundingDraft(); var quote = service.save(context, draft, "FUNDING");
        var journal = journal(NOW, fundingPreparation(draft));
        var source = journal.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH);
        assertThat(journal.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH)).isEqualTo(source);
        assertThat(count("ord_pricing_funding_source")).isEqualTo(1);
        assertThat(service.get(context, quote.quoteNo()).retainedRef()).isEqualTo(source.source().sourceNo());
        assertThat(source.expiresAt()).isEqualTo(NOW.plusSeconds(100));
        assertThat(reader(journal).query(internal, source.source()).status()).isEqualTo(State.PENDING);
        assertThat(reader(journal).query(internal, source.source()).amountMinor()).isEqualTo(10500);
    }
    @Test void fundingCancellationIsDurableIdempotentAndNeverChangesSourceRevision() throws Exception {
        var draft = fundingDraft(); var quote = service.save(context, draft, "FUNDING");
        var journal = journal(NOW, fundingPreparation(draft));
        var staged = journal.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH);
        var cancelled = journal.cancel(context, staged.source().sourceNo(), "CUSTOMER_CANCEL");
        assertThat(cancelled.source()).isEqualTo(staged.source());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(journal.cancel(context, staged.source().sourceNo(), "CUSTOMER_CANCEL")).isEqualTo(cancelled);
        assertThat(journal.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH)).isEqualTo(cancelled);
        assertThat(count("ord_ent_source_decision")).isEqualTo(1);
        var decision = reader(journal).query(internal, staged.source());
        assertThat(decision.status()).isEqualTo(State.CANCELLED);
        assertThat(decision.decision().sourceRevision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM ord_pricing_funding_source", Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> journal.cancel(context, staged.source().sourceNo(), "OTHER_REASON"))
                .isInstanceOf(BizException.class);
    }
    @Test void expiryIsStillPendingAndOnlyExplicitCancellationCreatesTerminalTruth() throws Exception {
        var draft = fundingDraft(); var prepared = fundingPreparation(draft);
        var quote = service.save(context, draft, "FUNDING");
        var original = journal(NOW, prepared); var staged = original.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH);
        var expired = journal(NOW.plusSeconds(200), prepared);
        assertThat(expired.get(context, staged.source().sourceNo()).status()).isEqualTo("PENDING");
        assertThat(reader(expired).query(internal, staged.source()).status()).isEqualTo(State.PENDING);
        assertThat(count("ord_ent_source_decision")).isZero();
        assertThatThrownBy(() -> expired.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH))
                .isInstanceOf(BizException.class);
        expired.cancel(context, staged.source().sourceNo(), "EXPIRED_RECONCILIATION");
        assertThat(reader(expired).query(internal, staged.source()).status()).isEqualTo(State.CANCELLED);
    }
    @Test void wrongTenantRevisionOrSourceShapeNeverFindsJournal() throws Exception {
        var draft = fundingDraft(); var journal = journal(NOW, fundingPreparation(draft));
        var quote = service.save(context, draft, "FUNDING");
        var staged = journal.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH);
        var wrong = new SourceRef("CORE", PricingFundingSourceService.SOURCE_TYPE, staged.source().sourceNo(), null, null, null, 2);
        assertThat(reader(journal).query(internal, wrong).status()).isEqualTo(State.UNKNOWN);
        assertThat(reader(journal).query(new EntitlementContext("OTHER", "ACT", "REQ", "e".repeat(64)),
                staged.source()).status()).isEqualTo(State.UNKNOWN);
        var shape = new SourceRef("CORE", PricingFundingSourceService.SOURCE_TYPE, staged.source().sourceNo(), "LINE", null, null, 1);
        assertThat(reader(journal).query(internal, shape).status()).isEqualTo(State.UNKNOWN);
    }
    @Test void journalScopesPayerAndRejectsSessionAndForgedEconomicSnapshot() throws Exception {
        var draft = fundingDraft(); var quote = service.save(context, draft, "FUNDING");
        var journal = journal(NOW, fundingPreparation(draft));
        assertThatThrownBy(() -> journal.stage(context, new Credentials("SESSION", null, "U1"), quote.quoteNo(), PREPARATION_HASH))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> journal.stage(context, new Credentials(null, null, "U2"), quote.quoteNo(), PREPARATION_HASH))
                .isInstanceOf(BizException.class);
        var staged = journal.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH);
        for (var wrong : List.of(new Context("MAIN", "M2", "S1", "U1", "ACT"),
                new Context("MAIN", "M1", "S2", "U1", "ACT"), new Context("MAIN", "M1", "S1", "U2", "ACT"))) {
            assertThatThrownBy(() -> journal.get(wrong, staged.source().sourceNo())).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> journal.cancel(wrong, staged.source().sourceNo(), "CANCEL")).isInstanceOf(BizException.class);
        }
        jdbc.update("UPDATE ord_pricing_funding_source SET amount_minor=1");
        assertThatThrownBy(() -> journal.get(context, staged.source().sourceNo())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> reader(journal).query(internal, staged.source())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> journal.cancel(context, staged.source().sourceNo(), "CANCEL")).isInstanceOf(BizException.class);
    }
    @Test void failedJournalInsertRollsBackQuoteRetention() throws Exception {
        var draft = fundingDraft(); var quote = service.save(context, draft, "FUNDING");
        var broken = mock(PricingFundingSourceMapper.class);
        when(broken.insert(any(OrdPricingFundingSource.class)))
                .thenThrow(new IllegalStateException("injected insert failure"));
        var journal = journal(NOW, fundingPreparation(draft), broken);
        assertThatThrownBy(() -> journal.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("ord_pricing_funding_source")).isZero();
        assertThat(service.get(context, quote.quoteNo()).retainedRef()).isNull();
    }
    @Test void failedJournalTransitionRollsBackInsertedCancellationDecision() throws Exception {
        var draft = fundingDraft(); var quote = service.save(context, draft, "FUNDING");
        var original = journal(NOW, fundingPreparation(draft));
        var staged = original.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH);
        var broken = mock(PricingFundingSourceMapper.class, Answers.RETURNS_DEFAULTS);
        when(broken.selectOne(any(Wrapper.class)))
                .thenAnswer(call -> session.getMapper(PricingFundingSourceMapper.class).selectOne(call.getArgument(0)));
        // Default mock returns zero from update; the decision written immediately before it must roll back.
        var failed = journal(NOW, fundingPreparation(draft), broken);
        assertThatThrownBy(() -> failed.cancel(context, staged.source().sourceNo(), "CANCEL")).isInstanceOf(BizException.class);
        assertThat(count("ord_ent_source_decision")).isZero();
        assertThat(original.get(context, staged.source().sourceNo()).status()).isEqualTo("PENDING");
    }
    @Test void concurrentStageSharesQuoteLockAndCreatesOnlyOneJournal() throws Exception {
        var draft = fundingDraft(); var quote = service.save(context, draft, "FUNDING");
        var journal = journal(NOW, fundingPreparation(draft));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var a = pool.submit(() -> { gate.await(); return journal.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH); });
            var b = pool.submit(() -> { gate.await(); return journal.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH); });
            gate.countDown(); assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(b.get(10, TimeUnit.SECONDS));
        }
        assertThat(count("ord_pricing_funding_source")).isEqualTo(1);
    }

    @Test void changedProductEligibilityAndTaxCannotFreezeOldQuote() throws Exception {
        var draft = fundingDraft(); var quote = service.save(context, draft, "FUNDING");
        var original = fundingPreparation(draft);
        var changedPolicies = GoodsPricingPolicyFacts.freeze(original.goodsPolicies().scope(),
                List.of(new GoodsPricingPolicyPort.Policy("POLICY", "G1", "SKU1", 2, false, TaxClass.STANDARD)));
        var changed = new Prepared(original.source(), original.rules(), original.tax(), changedPolicies,
                original.expiresAt(), PREPARATION_HASH);
        var journal = journal(NOW, changed);
        assertThatThrownBy(() -> journal.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH)).isInstanceOf(BizException.class);
        var oldTax = original.tax();
        var tax = new MerchantTaxSnapshot("MAIN", "M1", "S1", 2, oldTax.source(), oldTax.ownerNo(), 2,
                600, oldTax.priceTaxMode(), new TaxSettingDefinition(true, 600, oldTax.priceTaxMode())
                .snapshotHash("MERCHANT_CUSTOM:M1", 2));
        var changedTax = journal(NOW, new Prepared(original.source(), original.rules(), tax, original.goodsPolicies(),
                original.expiresAt(), PREPARATION_HASH));
        assertThatThrownBy(() -> changedTax.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH)).isInstanceOf(BizException.class);
        assertThat(count("ord_pricing_funding_source")).isZero();
    }
    @Test void alreadyDiscountedQuoteAndChangedPreparationAreRejected() throws Exception {
        var baseline = fundingDraft(); var prepared = fundingPreparation(baseline);
        var old = draft(false, 120);
        var discounted = new SaveReq(old.source(), old.industry(), "H5", old.phase(), old.storeRevisionNo(),
                old.templateRevisionNo(), old.engineSpecVersion(), old.facts(), old.tax(), old.intent(), old.lines(),
                old.allocations(), old.lifetimeSeconds());
        var quote = service.save(context, discounted, "DISCOUNTED"); var journal = journal(NOW, prepared);
        assertThatThrownBy(() -> journal.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH)).isInstanceOf(BizException.class);
        var clean = service.save(context, baseline, "BASELINE");
        assertThatThrownBy(() -> journal.stage(context, BUYER, clean.quoteNo(), "f".repeat(64))).isInstanceOf(BizException.class);
        assertThat(count("ord_pricing_funding_source")).isZero();
    }
    @Test void ambientTransactionCannotReadSourceOwnersAndExpiredQuoteCannotBeRetained() throws Exception {
        var draft = fundingDraft(); var quote = service.save(context, draft, "FUNDING");
        var prepared = fundingPreparation(draft); var journal = journal(NOW, prepared);
        var transaction = new TransactionTemplate(manager);
        assertThatThrownBy(() -> transaction.execute(tx -> journal.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH)))
                .isInstanceOf(BizException.class);
        jdbc.update("UPDATE ord_price_quote SET expires_at='2026-10-23 03:59:59.000'");
        assertThatThrownBy(() -> journal.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH)).isInstanceOf(BizException.class);
        assertThat(count("ord_pricing_funding_source")).isZero();
    }
    @Test void concurrentCancellationHasOneDecisionAndNeverResurrectsSource() throws Exception {
        var draft = fundingDraft(); var quote = service.save(context, draft, "FUNDING");
        var journal = journal(NOW, fundingPreparation(draft));
        var staged = journal.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var a = pool.submit(() -> { gate.await(); return journal.cancel(context, staged.source().sourceNo(), "CANCEL"); });
            var b = pool.submit(() -> { gate.await(); return journal.cancel(context, staged.source().sourceNo(), "CANCEL"); });
            gate.countDown(); assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(b.get(10, TimeUnit.SECONDS));
        }
        assertThat(count("ord_ent_source_decision")).isEqualTo(1);
        assertThat(reader(journal).query(internal, staged.source()).status()).isEqualTo(State.CANCELLED);
    }

    @Test void cancellationBetweenDecisionAndJournalReadsReturnsDurableCancellation() throws Exception {
        var draft = fundingDraft(); var quote = service.save(context, draft, "FUNDING");
        var journal = journal(NOW, fundingPreparation(draft));
        var staged = journal.stage(context, BUYER, quote.quoteNo(), PREPARATION_HASH);
        var reads = mock(EntitlementSourceDecisionMapper.class);
        var calls = new AtomicInteger();
        when(reads.selectOne(any(Wrapper.class))).thenAnswer(call -> {
            int n = calls.incrementAndGet();
            if (n == 2) journal.cancel(context, staged.source().sourceNo(), "CONCURRENT_CANCEL");
            if (n <= 2) return null;
            return session.getMapper(EntitlementSourceDecisionMapper.class).selectOne(call.getArgument(0));
        });
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("journal", journal);
        var reader = new EntitlementSourceDecisionPortImpl(null, reads, JsonMapper.builder().build(),
                beans.getBeanProvider(PricingFundingSourceService.class));
        var decision = reader.query(internal, staged.source());
        assertThat(decision.status()).isEqualTo(State.CANCELLED);
        assertThat(calls.get()).isEqualTo(3);
        // A terminal journal without its same-transaction durable decision is still rejected, never fabricated.
        jdbc.update("DELETE FROM ord_ent_source_decision");
        assertThatThrownBy(() -> reader(journal).query(internal, staged.source())).isInstanceOf(BizException.class);
    }

    private PricingQuoteServiceImpl build(Instant time, PriceQuoteAllocationMapper mapper) {
        return new PricingQuoteServiceImpl(session.getMapper(PriceQuoteMapper.class),
                session.getMapper(PriceQuoteLineMapper.class), mapper, session.getMapper(StorePricingConfigMapper.class),
                session.getMapper(StorePricingRevisionMapper.class), receipts, ASSEMBLER, manager,
                Clock.fixed(time, ZoneOffset.UTC));
    }

    private SaveReq draft(boolean inclusive, int lifetime) {
        var mode = inclusive ? PriceTaxMode.INCLUSIVE : PriceTaxMode.EXCLUSIVE;
        var money = new MoneyMinor("AED", 10000);
        var unit = new PricingUnit("L1", null, "G1", "SKU1", "PRICE1", "INVOICE1", BigDecimal.ZERO,
                BigDecimal.ONE, money, money);
        long tax = inclusive ? 429 : 450;
        long net = inclusive ? 8571 : 9000;
        long gross = inclusive ? 9000 : 9450;
        var amounts = new Amounts(10000, 0, 0, 1000, 0, 0, 0, 0, net, tax, gross, 0);
        var snapshot = new MerchantTaxSnapshot("MAIN", "M1", "S1", 1, Source.MERCHANT_CUSTOM, "M1", 1,
                500, mode, new TaxSettingDefinition(true, 500, mode).snapshotHash("MERCHANT_CUSTOM:M1", 1));
        var allocations = new ArrayList<Allocation>();
        allocations.add(allocation(PricingPortion.from(unit), Kind.COUPON_DISCOUNT, 0, 10000, 1000, "MERCHANT"));
        if (inclusive) allocations.add(allocation(PricingPortion.from(unit), Kind.CASH_PLAN, 1, gross, gross, "CASH"));
        return new SaveReq(new PricingSourceRef("FOOD", "CART", "CART1", 1, null, null), Industry.FOOD, "CASHIER",
                Phase.LIVE, "PR1", "TR1", "ENGINE_V1", new PricingFacts("AED", "b".repeat(64), 1, List.of(unit)),
                snapshot, new Intent(new BenefitSelection(BenefitSelection.Mode.EXPLICIT, List.of(), List.of()),
                        new Funding(0, inclusive ? gross : 0, inclusive ? gross : 0, inclusive ? 10000L : null)),
                List.of(new Line(unit, null, LineKind.GOODS, TaxClass.STANDARD, amounts, inclusive ? gross : net, policy(unit))), allocations, lifetime);
    }

    private Allocation allocation(PricingPortion portion, Kind kind, int step, long basis, long amount, String funder) {
        return new Allocation(portion, kind, step, "BENEFIT" + step, "OWNER", "OWNER_QUOTE", "APPLICATION" + step,
                "RULE", "V1", basis, amount, null, "OWNER_FROZEN_V1", List.of(new Funder(funder, amount)));
    }

    private SaveReq copy(SaveReq r, List<Line> lines, List<Allocation> allocations, Intent intent, MerchantTaxSnapshot tax) {
        return new SaveReq(r.source(), r.industry(), r.channel(), r.phase(), r.storeRevisionNo(), r.templateRevisionNo(),
                r.engineSpecVersion(), r.facts(), tax, intent, lines, allocations, r.lifetimeSeconds());
    }

    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private UnitPolicy policy(PricingUnit unit) {
        return new UnitPolicy(PricingPortion.from(unit), true, PricingRuleFacts.ScopeStatus.OPEN,
                null, PricingRuleFacts.PricingRole.NONE);
    }

}
