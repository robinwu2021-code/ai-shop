package ai.neargo.shop.trade.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourcePort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.spi.trade.RetailChannelFulfillmentSealed;
import ai.neargo.shop.trade.entity.OrdIndustryFulfillment;
import ai.neargo.shop.trade.mapper.ChannelFulfillmentEvidenceMapper;
import ai.neargo.shop.trade.mapper.TradeMappers;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Real V592/V599 tables and primary transactions; inventory input is isolated, never a vendor or
 * funds success claim.
 */
class ChannelFulfillmentEvidenceStoreTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    JdbcTemplate sql;
    TransactionTemplate tx;
    ChannelFulfillmentEvidenceStore evidence;
    final IndustryFulfillmentPort.Reference original =
            new IndustryFulfillmentPort.Reference(
                    new PricingSourceFactsPort.Scope("MAIN", "E", "S"),
                    new PricingSourceRef("RETAIL", "CHANNEL_ORDER", "SOURCE", 1, null, null),
                    "ORDER",
                    "SUB");

    IndustryFulfillmentPort.Fulfill command(String key, String qty) {
        return new IndustryFulfillmentPort.Fulfill(
                original,
                "MERCHANT",
                1,
                "DELIVERY_HANDOVER",
                key,
                "b".repeat(64),
                null,
                "RIDER",
                "REAL_HANDOVER",
                List.of(
                        new IndustryFulfillmentPort.ItemTarget(
                                10L, "COMP", new BigDecimal(qty), "KG")));
    }

    OrdIndustryFulfillment completed(String no, IndustryFulfillmentPort.Fulfill command) {
        var row = new OrdIndustryFulfillment();
        row.setFulfillmentNo(no);
        row.setStatus("PARTIAL");
        row.setSourceHash("a".repeat(64));
        row.setPlanHash(command.planHash());
        row.setRequestHash(PricingSnapshotHash.of(command));
        row.setInventoryReceiptJson(json.writeValueAsString(inventory()));
        return row;
    }

    InventoryDemandPlanPort.Receipt inventory() {
        return new InventoryDemandPlanPort.Receipt(
                null, "PLAN", "b".repeat(64), "ACCEPTED_UNPAID", 3, null, List.of());
    }

    IndustryChannelFulfillmentSourcePort.Query query(String no) {
        return new IndustryChannelFulfillmentSourcePort.Query(
                new IndustryChannelOrderPort.Scope("MAIN", "E", "S"), "SOURCE", "ORDER", no, "1");
    }

    @BeforeEach
    void database() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:fulfillment_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(
                        new FileSystemResource(
                                "../shop-app/src/main/resources/db/migration/"
                                        + "V607__industry_channel_order_admission.sql"),
                        new FileSystemResource(
                                "../shop-app/src/main/resources/db/migration/V614__channel_fulfillment_evidence.sql"))
                .execute(ds);
        sql = new JdbcTemplate(ds);
        var manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        for (var type :
                List.of(
                        TradeMappers.ChannelImportMapper.class,
                        TradeMappers.ChannelAdmissionGuardMapper.class,
                        TradeMappers.ChannelQuoteMapper.class,
                        TradeMappers.ChannelPolicyMapper.class,
                        TradeMappers.ChannelPreparationMapper.class,
                        ChannelFulfillmentEvidenceMapper.class)) config.addMapper(type);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        var store =
                new IndustryChannelOrderStore(
                        session.getMapper(TradeMappers.ChannelImportMapper.class),
                        session.getMapper(TradeMappers.ChannelAdmissionGuardMapper.class),
                        session.getMapper(TradeMappers.ChannelQuoteMapper.class),
                        session.getMapper(TradeMappers.ChannelPolicyMapper.class),
                        session.getMapper(TradeMappers.ChannelPreparationMapper.class),
                        json,
                        mock(DocumentNumberCoordinator.class),
                        manager,
                        Clock.systemUTC());
        evidence =
                new ChannelFulfillmentEvidenceStore(
                        store, session.getMapper(ChannelFulfillmentEvidenceMapper.class));
        var allocation =
                new IndustryChannelOrderPort.Allocation(
                        "REMOTE_LINE",
                        null,
                        "MAIN",
                        "SOURCE_LINE",
                        "COMP",
                        "OPAQUE_ITEM",
                        "1.25",
                        "KG",
                        100,
                        "CNY",
                        "ORIGINAL_ALLOCATION",
                        10L);
        sql.update(
                "INSERT INTO"
                    + " ord_channel_import(admission_no,entity_no,store_no,channel_store_key,external_order_key,"
                    + "external_order_ref,source_system,source_no,source_version,source_hash,import_ref,quote_no,"
                    + "order_no,sub_order_no,status,funding_status,allocations_json)"
                    + " VALUES('A','E','S','CHANNEL','EXTERNAL_KEY','EXT','RETAIL','SOURCE',1,?,'IMPORT','QUOTE',"
                    + "'ORDER','SUB','ACCEPTED','POLICY_RESERVED',?)",
                "a".repeat(64),
                json.writeValueAsString(
                        new IndustryChannelOrderCommitter.StoredAcceptance(List.of(allocation))));
    }

    @Test
    void onlyANewPositiveCommittedProofReturnsAnOriginalEventWithTheActualImportBinding() {
        var command = command("NEW_EVENT", "0.5");
        var sealed =
                tx.<Optional<RetailChannelFulfillmentSealed>>execute(
                                status ->
                                        evidence.seal(
                                                completed("EVENT_F", command),
                                                command,
                                                inventory(),
                                                Map.of(10L, BigDecimal.ZERO)))
                        .orElseThrow();
        var proof = evidence.read(query("EVENT_F"));
        assertThat(sealed.importRef()).isEqualTo("IMPORT");
        assertThat(proof.data().importRef()).isEqualTo("IMPORT");
        assertThat(sealed.contentHash()).isEqualTo(proof.contentHash());
        assertThat(sealed.receiptNo()).isEqualTo(proof.data().receiptNo());
        assertThat(sealed.orderNo()).isEqualTo("ORDER");
        assertThat(sealed.fulfillmentNo()).isEqualTo("EVENT_F");
        assertThat(
                        tx.<Optional<RetailChannelFulfillmentSealed>>execute(
                                status ->
                                        evidence.seal(
                                                completed("EVENT_F", command),
                                                command,
                                                inventory(),
                                                Map.of(10L, BigDecimal.ZERO))))
                .isEmpty();
        var noOp = command("NO_OP", "0.5");
        assertThat(
                        tx.<Optional<RetailChannelFulfillmentSealed>>execute(
                                status ->
                                        evidence.seal(
                                                completed("NO_EVENT", noOp),
                                                noOp,
                                                inventory(),
                                                Map.of(10L, new BigDecimal("0.5")))))
                .isEmpty();
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM ord_channel_fulfillment_evidence",
                                Integer.class))
                .isOne();
    }

    @Test
    void twoCommittedActionsRetainExactSeparateDeltasAndStableOriginalReceipts() {
        var first = command("FIRST", "0.5");
        tx.executeWithoutResult(
                status ->
                        evidence.seal(
                                completed("F1", first),
                                first,
                                inventory(),
                                Map.of(10L, BigDecimal.ZERO)));
        var next = command("NEXT", "1.25");
        tx.executeWithoutResult(
                status ->
                        evidence.seal(
                                completed("F2", next),
                                next,
                                inventory(),
                                Map.of(10L, new BigDecimal("0.5"))));
        var firstProof = evidence.read(query("F1"));
        var second = evidence.read(query("F2"));
        assertThat(firstProof.data().lines().getFirst().quantity()).isEqualTo("0.5");
        assertThat(second.data().lines().getFirst().quantity()).isEqualTo("0.75");
        assertThat(evidence.read(query("F1")).data()).isEqualTo(firstProof.data());
        assertThat(firstProof.data().stage()).isEqualTo("HANDED_OVER");
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM ord_channel_fulfillment_evidence",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void preparationUnknownAndRolledBackLocalCommitNeverBecomePublishable() {
        var c = command("UNKNOWN", "0.5");
        var row = completed("F", c);
        row.setStatus("UNKNOWN");
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status ->
                                                evidence.seal(
                                                        row,
                                                        c,
                                                        inventory(),
                                                        Map.of(10L, BigDecimal.ZERO))))
                .isInstanceOf(RuntimeException.class);
        row.setStatus("PARTIAL");
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            evidence.seal(
                                                    row,
                                                    c,
                                                    inventory(),
                                                    Map.of(10L, BigDecimal.ZERO));
                                            throw new IllegalStateException(
                                                    "primary commit failed");
                                        }))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> evidence.read(query("F"))).isInstanceOf(RuntimeException.class);
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM ord_channel_fulfillment_evidence",
                                Integer.class))
                .isZero();
    }

    @Test
    void legacyTerminalWithoutSealedEvidenceAndForeignOriginalScopeRemainUnavailable() {
        assertThatThrownBy(() -> evidence.read(query("OLD"))).isInstanceOf(RuntimeException.class);
        var c = command("NEW", "0.5");
        tx.executeWithoutResult(
                status ->
                        evidence.seal(
                                completed("F", c), c, inventory(), Map.of(10L, BigDecimal.ZERO)));
        var foreign =
                new IndustryChannelFulfillmentSourcePort.Query(
                        new IndustryChannelOrderPort.Scope("MAIN", "OTHER", "S"),
                        "SOURCE",
                        "ORDER",
                        "F",
                        "1");
        assertThatThrownBy(() -> evidence.read(foreign)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void noOpCannotInventASecondPhysicalReceipt() {
        var c = command("NOOP", "0.5");
        tx.executeWithoutResult(
                status ->
                        evidence.seal(
                                completed("F", c),
                                c,
                                inventory(),
                                Map.of(10L, new BigDecimal("0.5"))));
        assertThatThrownBy(() -> evidence.read(query("F"))).isInstanceOf(RuntimeException.class);
    }
}
