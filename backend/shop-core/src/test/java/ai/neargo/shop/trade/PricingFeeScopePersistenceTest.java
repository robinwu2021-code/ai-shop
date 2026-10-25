package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Allocation;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Definition;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Kind;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Method;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Rule;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Source;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.mapper.PricingFeeRuleMapper;
import ai.neargo.shop.trade.service.PricingFeeRuleService.SaveReq;
import ai.neargo.shop.trade.service.impl.PricingFeeRuleServiceImpl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Real CORE owner SQL. Tests are source-only during the single coding phase. */
public class PricingFeeScopePersistenceTest {
    private final Scope scope = new Scope("MAIN", "E", "S");
    private JdbcTemplate db;
    private Connection schemaConnection;

    @AfterEach
    void closeSchemaConnection() throws Exception {
        if (schemaConnection != null) schemaConnection.close();
    }

    private TransactionTemplate tx;
    private PricingFeeRuleServiceImpl service;
    private Rule rule;

    public static Timestamp utc(Integer precision) {
        return Timestamp.valueOf(LocalDateTime.of(2026, 10, 24, 0, 0));
    }

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:fee_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        db = new JdbcTemplate(ds);
        schemaConnection = ds.getConnection();
        var ddl = new JdbcTemplate(new SingleConnectionDataSource(schemaConnection, true));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ddl.execute(
                "CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR"
                        + " 'ai.neargo.shop.trade.PricingFeeScopePersistenceTest.utc'");
        ddl.execute("CREATE TABLE ord_order(id BIGINT PRIMARY KEY)");
        ddl.execute("CREATE TABLE ord_sub_order(id BIGINT PRIMARY KEY)");
        ddl.execute(
                "CREATE TABLE ord_industry_order_source(entity_no VARCHAR(32),store_no"
                    + " VARCHAR(32),order_no VARCHAR(32),sub_order_no VARCHAR(32),quote_no"
                    + " VARCHAR(32),status VARCHAR(16),deleted INT DEFAULT 0,source_system"
                    + " VARCHAR(32),source_type VARCHAR(32),source_no VARCHAR(32),source_version"
                    + " BIGINT)");
        ddl.execute(
                "ALTER TABLE ord_industry_order_source ADD tenant_no VARCHAR(32) DEFAULT 'MAIN'");
        ddl.execute("ALTER TABLE ord_industry_order_source ADD allocations_json VARCHAR(100000)");
        ddl.execute(
                "CREATE TABLE ord_price_quote(tenant_no VARCHAR(32),entity_no VARCHAR(32),store_no"
                        + " VARCHAR(32),quote_no VARCHAR(32),result_snapshot VARCHAR(100000))");
        ddl.execute(
                "CREATE TABLE ord_industry_amendment(tenant_no VARCHAR(32),entity_no"
                        + " VARCHAR(32),store_no VARCHAR(32),order_no VARCHAR(32),sub_order_no"
                        + " VARCHAR(32),amendment_no VARCHAR(32),source_hash VARCHAR(64),status"
                        + " VARCHAR(16))");
        ddl.execute(
                "CREATE TABLE ord_industry_amendment_line(amendment_no VARCHAR(32),order_item_id"
                        + " BIGINT,source_component_ref VARCHAR(64),quantity DECIMAL(24,6))");
        String sql =
                Files.readString(
                        Path.of(
                                "../shop-app/src/main/resources/db/migration/V581__pricing_fee_rules_and_scope_applications.sql"));
        sql =
                sql.replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "")
                        .replace(" JSON ", " VARCHAR(100000) ");
        for (String statement : sql.split(";")) if (!statement.isBlank()) ddl.execute(statement);
        service =
                new PricingFeeRuleServiceImpl(
                        new PricingFeeRuleMapper(ds), JsonMapper.builder().build());
        rule =
                tx.execute(
                        t ->
                                service.save(
                                        scope,
                                        "A",
                                        "MIN",
                                        new SaveReq(
                                                -1,
                                                new Definition(
                                                        Kind.MINIMUM,
                                                        Method.MINIMUM,
                                                        "ORDER",
                                                        "CNY",
                                                        1000,
                                                        null,
                                                        TaxClass.STANDARD),
                                                "ACTIVE",
                                                "minimum policy"),
                                        "rule"));
    }

    private PricingSourceFactsPort.Snapshot source(String cart, long amount) {
        var unit =
                new PricingUnit(
                        "L",
                        null,
                        "G",
                        "SKU",
                        "PRICE",
                        "L",
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        new MoneyMinor("CNY", amount),
                        new MoneyMinor("CNY", amount));
        var source =
                PricingSourceFacts.freeze(
                        scope,
                        "BUYER",
                        new PricingSourceRef("FOOD", "CART", cart, 0, null, null),
                        "POS",
                        "a".repeat(64),
                        List.of(
                                new PricingSourceFactsPort.Component(
                                        PricingSourceFactsPort.Kind.MAIN, unit)));
        return PricingSourceFacts.withFees(
                source,
                List.of(
                        new Source(
                                "MIN",
                                rule.revisionNo(),
                                "CHECK",
                                Kind.MINIMUM,
                                BigDecimal.ONE,
                                "ORDER",
                                List.of("L"),
                                null)));
    }

    @Test
    void concurrentScopeRevisionCannotDuplicateFeeAndAdviceHasOriginalOrderAuthority() {
        var first = service.resolve(source("C1", 100));
        tx.execute(
                t -> {
                    service.applyPlan(scope, "A", "O1", "S1", "Q1", first);
                    return null;
                });
        var second = service.resolve(source("C2", 1000));
        var competing = service.resolve(source("C3", 1000));
        tx.execute(
                t -> {
                    service.applyPlan(scope, "A", "O2", "S2", "Q2", second);
                    return null;
                });
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        t -> {
                                            service.applyPlan(
                                                    scope, "A", "O3", "S3", "Q3", competing);
                                            return null;
                                        }))
                .isInstanceOf(BizException.class);
        db.update(
                "INSERT INTO"
                    + " ord_industry_order_source(entity_no,store_no,order_no,sub_order_no,quote_no,status)"
                    + " VALUES ('E','S','O2','S2','Q2','BOUND')");
        var advice = service.pendingAdjustments(scope, "CHECK").getFirst();
        assertThat(advice.orderNo()).isEqualTo("O1");
        assertThat(advice.targetFeeMinor()).isZero();
        assertThatThrownBy(
                        () ->
                                service.authorizeAdjustment(
                                        scope, advice.applicationNo(), 1, advice.sourceHash()))
                .isInstanceOf(BizException.class);
        tx.execute(
                t -> {
                    service.completeAdjustment(
                            scope, advice.applicationNo(), 0, advice.sourceHash(), "A", "correct");
                    return null;
                });
        assertThatThrownBy(
                        () ->
                                service.authorizeAdjustment(
                                        scope, advice.applicationNo(), 0, advice.sourceHash()))
                .isInstanceOf(BizException.class);
        assertThat(service.pendingAdjustments(scope, "CHECK")).isEmpty();
        assertThat(db.queryForObject("SELECT fee_minor FROM ord_pricing_fee_scope", Long.class))
                .isZero();
    }

    @Test
    void feeFreeSnapshotOmitsOptionalFieldsAndForeignStoreCannotResolveRule() {
        var withFees = source("C1", 100);
        var legacy =
                new PricingSourceFactsPort.Snapshot(
                        withFees.schemaVersion(),
                        withFees.scope(),
                        withFees.subjectNo(),
                        withFees.source(),
                        withFees.channel(),
                        withFees.prices(),
                        withFees.composition(),
                        withFees.invoiceBases(),
                        null);
        assertThat(JsonMapper.builder().build().writeValueAsString(legacy)).doesNotContain("fees");
        assertThatThrownBy(() -> service.get(new Scope("MAIN", "E", "FOREIGN"), "MIN"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void confirmedReturnReducesOriginalBasisAndCreatesIdempotentMinimumIncrease() {
        var original = source("C1", 100);
        var plan = service.resolve(original);
        tx.execute(
                t -> {
                    service.applyPlan(scope, "A", "O1", "S1", "Q1", plan);
                    return null;
                });
        var json = JsonMapper.builder().build();
        var allocations = List.of(new Allocation("L", null, 1L, 1, 1000, "CNY", "h"));
        db.update(
                "INSERT INTO"
                    + " ord_industry_order_source(entity_no,store_no,order_no,sub_order_no,quote_no,status,source_system,source_type,source_no,source_version,allocations_json)"
                    + " VALUES('E','S','O1','S1','Q1','BOUND','FOOD','CART','C1',0,?)",
                json.writeValueAsString(allocations));
        db.update(
                "INSERT INTO ord_price_quote VALUES('MAIN','E','S','Q1',?)",
                json.writeValueAsString(
                        Map.of(
                                "feePlan",
                                plan,
                                "lines",
                                List.of(Map.of("source", original.prices().units().getFirst())))));
        db.update(
                "INSERT INTO ord_industry_amendment"
                        + " VALUES('MAIN','E','S','O1','S1','AM1','HASH','CONFIRMED')");
        db.update("INSERT INTO ord_industry_amendment_line VALUES('AM1',1,'',1)");
        var ref =
                new Reference(
                        scope,
                        new PricingSourceRef("FOOD", "CART", "C1", 0, null, null),
                        "O1",
                        "S1");
        tx.execute(
                t -> {
                    service.reconcileOnAmendment(ref, "AM1", "HASH");
                    return null;
                });
        tx.execute(
                t -> {
                    service.reconcileOnAmendment(ref, "AM1", "HASH");
                    return null;
                });
        assertThat(
                        db.queryForObject(
                                "SELECT goods_basis_minor FROM ord_pricing_fee_scope", Long.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM ord_pricing_fee_reconciliation",
                                Integer.class))
                .isEqualTo(1);
        var advice = service.pendingAdjustments(scope, "CHECK").getFirst();
        assertThat(advice.currentFeeMinor()).isEqualTo(900);
        assertThat(advice.targetFeeMinor()).isEqualTo(1000);
        tx.execute(
                t -> {
                    service.completeAdjustment(
                            scope,
                            advice.applicationNo(),
                            1000,
                            advice.sourceHash(),
                            "A",
                            "correct");
                    return null;
                });
        assertThat(db.queryForObject("SELECT fee_minor FROM ord_pricing_fee_scope", Long.class))
                .isEqualTo(1000);
    }
}
