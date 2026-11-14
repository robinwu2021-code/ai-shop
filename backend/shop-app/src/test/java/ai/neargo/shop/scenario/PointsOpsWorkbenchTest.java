package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService;
import ai.neargo.shop.pay.points.admin.impl.PointsWorkbenchServiceImpl;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.statement.StoreStatementService;
import ai.neargo.shop.pay.points.type.PointTypeService.CreateType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

@SpringJUnitConfig(PointsOpsWorkbenchTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
        properties = {
            "shop.points.enabled=true",
            "spring.datasource.url=${points.jdbc.url:jdbc:h2:mem:points_ops_workbench;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
            "spring.datasource.username=${points.jdbc.user:sa}",
            "spring.datasource.password=${points.jdbc.password:}",
            "spring.datasource.driver-class-name=${points.jdbc.driver:org.h2.Driver}"
        })
class PointsOpsWorkbenchTest {
    @Autowired PointsWorkbenchService workbench;
    @Autowired PointsAdministrationService admin;

    @Autowired
    @Qualifier("payDataSource")
    DataSource data;

    @BeforeAll
    void schema() throws java.sql.SQLException {
        PointsPostingDatabaseTest.initializeSchema(data);
        try (var connection=data.getConnection(); var columns=connection.getMetaData().getColumns(connection.getCatalog(),null,"pts_point_type","name")) {
            if (!columns.next()) new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("db/migration/V537__points_type_name_unique.sql")).execute(data);
        }
    }

    @Test
    void store_route_groups_are_filtered_before_paging_and_stale_or_other_store_actions_fail() {
        String type = type();
        statement(type, "OTHER", "OTHER_ROUTE", 1);
        String a = statement(type, "STORE", "ROUTE_A", 2);
        String b = statement(type, "STORE", "ROUTE_B", 3);
        var first = workbench.statements(type, "STORE", 0, 1);
        assertThat(first.rows())
                .singleElement()
                .satisfies(r -> assertThat(r.snapshot().statementNo()).isEqualTo(a));
        assertThat(workbench.statements(type, "STORE", first.next(), 1).rows())
                .singleElement()
                .satisfies(r -> assertThat(r.snapshot().statementNo()).isEqualTo(b));
        assertThatThrownBy(
                        () ->
                                workbench.changeStatement(
                                        new PointsWorkbenchService.StatementChange(
                                                "WRONG_STORE",
                                                type,
                                                "OTHER",
                                                a,
                                                2,
                                                PointsWorkbenchService.StatementAction.CONFIRM,
                                                "TEST"),
                                        "OPS"))
                .hasMessageContaining("STORE");
        assertThatThrownBy(
                        () ->
                                workbench.changeStatement(
                                        new PointsWorkbenchService.StatementChange(
                                                "STALE",
                                                type,
                                                "STORE",
                                                a,
                                                1,
                                                PointsWorkbenchService.StatementAction.CONFIRM,
                                                "TEST"),
                                        "OPS"))
                .hasMessageContaining("REVISION");
        assertThat(new ObjectMapper().writeValueAsString(first))
                .doesNotContain("payload_json", "accountVersion", "subMerchantId");
    }

    @Test
    void exact_statement_and_revision_lines_reject_other_store_and_unknown_revision() {
        String type = type();
        String no = statement(type, "STORE", "ROUTE", 3);
        assertThat(workbench.statement(new PointsWorkbenchService.Reference(type, "STORE", no))
                .snapshot().statementNo()).isEqualTo(no);
        assertThatThrownBy(() -> workbench.statement(new PointsWorkbenchService.Reference(type, "OTHER", no)))
                .hasMessageContaining("STORE");
        assertThat(workbench.statementLines(new PointsWorkbenchService.StatementLines(type, "STORE", no, 3, 0, 20)).rows()).isEmpty();
        assertThatThrownBy(() -> workbench.statementLines(new PointsWorkbenchService.StatementLines(type, "OTHER", no, 3, 0, 20)))
                .hasMessageContaining("STORE");
        assertThatThrownBy(() -> workbench.statementLines(new PointsWorkbenchService.StatementLines(type, "STORE", no, 2, 0, 20)))
                .hasMessageContaining("REVISION");
    }

    @Autowired ai.neargo.shop.pay.points.event.PointsEventQueue queue;
    @Autowired PointsPayoutPreparationTest.TestClock clock;

    @Test
    void
            manual_event_retry_keeps_its_identity_requires_current_version_and_never_exposes_payload() {
        String type = type();
        String no = "PRIVATE_EVENT:" + type;
        queue.enqueue(
                type,
                no,
                new ai.neargo.shop.pay.points.event.PointsEventQueue.Message(
                        ai.neargo.shop.pay.points.event.PointsEventQueue.Kind.OPS_ATTENTION,
                        "SENSITIVE_BANK_BODY"));
        var lease = queue.claim(type, clock.millis());
        queue.fail(lease, clock.millis(), true, "TEST_FAILURE");
        var page = workbench.events(type, 0, 50);
        var event = page.rows().getFirst();
        assertThat(event.eventNo()).isEqualTo(no);
        assertThat(event.state()).isEqualTo("MANUAL");
        assertThat(event.reason()).isEqualTo("TEST_FAILURE");
        assertThat(event.subOrderNo()).isNull();
        assertThat(new ObjectMapper().writeValueAsString(page))
                .doesNotContain("SENSITIVE_BANK_BODY", "payload");
        assertThatThrownBy(
                        () ->
                                workbench.retryEvent(
                                        new PointsWorkbenchService.EventRetry(
                                                "STALE:" + type,
                                                type,
                                                event.id(),
                                                event.version() - 1,
                                                "TEST"),
                                        "OPS"))
                .hasMessageContaining("REVISION");
        var request =
                new PointsWorkbenchService.EventRetry(
                        "RETRY:" + type, type, event.id(), event.version(), "TEST");
        var result = workbench.retryEvent(request, "OPS");
        assertThat(workbench.retryEvent(request, "OPS")).isEqualTo(result);
        assertThat(workbench.events(type, 0, 50).rows().getFirst().eventNo()).isEqualTo(no);
        assertThat(workbench.events(type, 0, 50).rows().getFirst().state()).isEqualTo("RETRY");
    }

    @Test
    void refund_queries_redact_original_bank_context_and_refuse_another_store_or_revision() {
        String type = type();
        String no = "RF_" + UUID.randomUUID().toString().replace("-", "");
        var payment =
                new ai.neargo.shop.spi.points.PointsRefundPort.PaymentSnapshot(
                        "DIRECT",
                        "TEST",
                        "M",
                        "STORE",
                        "SECRET_ACCOUNT",
                        "SECRET_SUB",
                        null,
                        null,
                        100);
        var command =
                new ai.neargo.shop.spi.points.PointsRefundPort.RefundCommand(
                        "AS:" + type,
                        "SUB:" + type,
                        "USER",
                        type,
                        "CNY",
                        1,
                        100,
                        50,
                        List.of(),
                        payment);
        var preparation =
                new ai.neargo.shop.spi.points.PointsRefundPort.RefundPreparation(
                        no, type, 1, "HASH", 0, 0, 0, 0, 0, 100, 0, null, "PREPARED");
        var saved =
                new ai.neargo.shop.pay.points.refund.impl.PointsRefundPreparationServiceImpl
                        .RefundSnapshot(
                        command,
                        preparation,
                        "NONE",
                        "NONE",
                        java.util.Map.of(),
                        null,
                        null,
                        null,
                        null,
                        null);
        new JdbcTemplate(data)
                .update(
                        "INSERT INTO"
                            + " pts_refund_preparation(position_no,type_code,state,payload_json)"
                            + " VALUES(?,?,'PREPARED',?)",
                        no,
                        type,
                        new ObjectMapper().writeValueAsString(saved));
        var view = workbench.refund(new PointsWorkbenchService.Reference(type, "STORE", no));
        assertThat(view.preparation().netCashMinor()).isEqualTo(100);
        assertThat(new ObjectMapper().writeValueAsString(view))
                .doesNotContain("SECRET_ACCOUNT", "SECRET_SUB", "payment", "payload_json");
        assertThatThrownBy(
                        () ->
                                workbench.refund(
                                        new PointsWorkbenchService.Reference(type, "OTHER", no)))
                .hasMessageContaining("STORE");
        assertThatThrownBy(
                        () ->
                                workbench.reviseRefund(
                                        new PointsWorkbenchService.Revision(
                                                "STALE:" + type, type, "STORE", no, 99, "TEST"),
                                        "OPS"))
                .hasMessageContaining("REVISION");
        assertThatThrownBy(
                        () ->
                                workbench.reviseRefund(
                                        new PointsWorkbenchService.Revision(
                                                "NOT_FAILED:" + type, type, "STORE", no, 0, "TEST"),
                                        "OPS"))
                .hasMessageContaining("NOT_FAILED");
        assertThat(
                        workbench
                                .refund(new PointsWorkbenchService.Reference(type, "STORE", no))
                                .state())
                .isEqualTo("PREPARED");
    }

    private String type() {
        String type = "OW_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        admin.create(
                new PointsAdministrationService.Create(
                        "CREATE:" + type,
                        new CreateType(type, "CN", new RedeemRate(2, 1), 2000, 365),
                        "SYNTHETIC"),
                "TEST");
        return type;
    }

    private String statement(String type, String store, String route, long revision) {
        String no = "ST_" + UUID.randomUUID().toString().replace("-", "");
        var snapshot =
                new StoreStatementService.Snapshot(
                        no,
                        new StoreStatementService.Period(type, store, 100, 200, route),
                        List.of(),
                        List.of(
                                new StoreStatementService.Revision(
                                        revision, List.of(), 0, "TEST", null)),
                        "DRAFT",
                        null);
        new JdbcTemplate(data)
                .update(
                        "INSERT INTO"
                            + " pts_store_statement(position_no,type_code,state,original_points,processed_points,payload_json,store_no,period_from,period_to,revision,route_key)"
                            + " VALUES(?,?,'DRAFT',0,0,?,?,100,200,?,?)",
                        no,
                        type,
                        new ObjectMapper().writeValueAsString(snapshot),
                        store,
                        revision,
                        route);
        return no;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({PointsOpsConfigurationTest.Config.class, PointsWorkbenchServiceImpl.class})
    static class Config {}
}
