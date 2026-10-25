package ai.neargo.shop.printing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.printing.mapper.PrintMappers.AttemptMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.DeviceMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.JobMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.RouteMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.TemplateMapper;
import ai.neargo.shop.printing.service.PrintService;
import ai.neargo.shop.printing.service.impl.PrintServiceImpl;
import ai.neargo.shop.spi.printing.PrintPort.Acknowledge;
import ai.neargo.shop.spi.printing.PrintPort.DeviceReq;
import ai.neargo.shop.spi.printing.PrintPort.Document;
import ai.neargo.shop.spi.printing.PrintPort.Enqueue;
import ai.neargo.shop.spi.printing.PrintPort.Line;
import ai.neargo.shop.spi.printing.PrintPort.RouteReq;
import ai.neargo.shop.spi.printing.PrintPort.Scope;
import ai.neargo.shop.spi.printing.PrintPort.TemplateReq;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Executes real mapper transactions and durable attempt history; physical printing is deliberately
 * not simulated.
 */
class PrintDeliveryTest {
    private static final Scope OWNER = new Scope("E", "S", "OPERATOR");
    private static final Scope BRIDGE = new Scope("E", "S", "BRIDGE");
    private static final Document DOCUMENT =
            new Document(
                    1,
                    "ORDER",
                    "BATCH",
                    "订单",
                    null,
                    List.of(new Line("SKU", "2", null, "座位 1")),
                    "不代表收款");
    PrintService service;
    JdbcTemplate jdbc;
    private OutboxPublisher outbox;
    TestClock clock;
    DeviceMapper devices;
    JobMapper jobs;

    @BeforeEach
    void setup() throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:print_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(source);
        String schema =
                Files.readString(
                        Path.of(
                                "../shop-app/src/main/resources/db/migration/"
                                        + "V567__shared_print_delivery.sql"));
        // H2 does not enforce MariaDB JSON_VALID; service parsing and real-MariaDB migration checks
        // are separate.
        schema =
                schema.replace("JSON_VALID(capabilities)", "capabilities IS NOT NULL")
                        .replace("JSON_VALID(match_rule)", "match_rule IS NOT NULL")
                        .replace("JSON_VALID(document_snapshot)", "document_snapshot IS NOT NULL");
        for (String statement : schema.split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
        jdbc.execute("ALTER TABLE prn_device ADD authorization_version BIGINT NOT NULL DEFAULT 0");
        // H2 supports one ADD clause per ALTER; retain all production revocation columns.
        jdbc.execute("ALTER TABLE prn_device ADD revoke_reason VARCHAR(255)");
        jdbc.execute("ALTER TABLE prn_device ADD revoked_by VARCHAR(32)");
        jdbc.execute("ALTER TABLE prn_device ADD revoke_key VARCHAR(128)");
        jdbc.execute("ALTER TABLE prn_device ADD revocation_history CLOB");
        applyCanonicalPrintColumns(jdbc);
        // Runtime is a secondary projection of the canonical gate, including when the center is disabled.
        jdbc.execute("CREATE TABLE prn_printer_runtime (tenant_no VARCHAR(32), entity_no VARCHAR(32), printer_no VARCHAR(32), active_job_no VARCHAR(32), active_attempt_no VARCHAR(32), lease_owner VARCHAR(64), lease_token_hash VARCHAR(64), lease_until TIMESTAMP, blocked_reason VARCHAR(64), updated_at TIMESTAMP, revision BIGINT DEFAULT 0)");
        // Replay the actual compatibility widening for this legacy-only fixture subset.
        String ownerWidths = Files.readString(Path.of("../shop-app/src/main/resources/db/migration/"
                + "V585__print_owner_identity_width.sql"));
        var widthChanges = Pattern.compile(
                "ALTER TABLE (prn_device|prn_template|prn_route|prn_job|prn_attempt|prn_printer_runtime) MODIFY COLUMN (entity_no|store_no|created_by|updated_by|requested_by|revoked_by) VARCHAR\\(64\\)[^;]*;").matcher(ownerWidths);
        while (widthChanges.find()) jdbc.execute(widthChanges.group().replace(" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin", ""));
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        var plugins = new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        config.addInterceptor(plugins);
        for (var type :
                List.of(
                        DeviceMapper.class,
                        TemplateMapper.class,
                        RouteMapper.class,
                        JobMapper.class,
                        AttemptMapper.class)) {
            config.addMapper(type);
        }
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        devices = session.getMapper(DeviceMapper.class);
        jobs = session.getMapper(JobMapper.class);
        outbox = mock(OutboxPublisher.class);
        clock = new TestClock();
        service =
                new PrintServiceImpl(
                        devices,
                        session.getMapper(TemplateMapper.class),
                        session.getMapper(RouteMapper.class),
                        jobs,
                        session.getMapper(AttemptMapper.class),
                        JsonMapper.builder().build(),
                        clock,
                        outbox,
                        new DataSourceTransactionManager(source));
        service.bindDevice(
                OWNER,
                new DeviceReq("DEVICE", "GENERIC", "LAN", "VAULT:PRINT", "BRIDGE", "{}", true));
        service.publishTemplate(
                OWNER, new TemplateReq("TPL1", "ORDER", 1, "ORDER", "{{reprint}}{{document}}"));
        service.putRoute(OWNER, new RouteReq("ROUTE", "ORDER", null, "DEVICE", "TPL1", 1));
    }

    @Test
    void canonicalMerchantOperatorIdentityIsRetainedAcrossEnqueueClaimAndRevocation() {
        String operator = "U".repeat(64);
        var owner = new Scope("E", "S", operator);
        service.publishTemplate(owner, new TemplateReq("TPL64", "ORDER", 2, "ORDER", "{{document}}"));
        service.putRoute(owner, new RouteReq("ROUTE", "ORDER", null, "DEVICE", "TPL64", 1));
        var job = service.enqueue(owner, new Enqueue("LONG-ACTOR", null, "PART", DOCUMENT)).getFirst();
        assertThat(jdbc.queryForObject("SELECT requested_by FROM prn_job WHERE job_no=?", String.class, job.jobNo()))
                .isEqualTo(operator);
        assertThat(jdbc.queryForObject("SELECT created_by FROM prn_job WHERE job_no=?", String.class, job.jobNo()))
                .isEqualTo(operator);
        assertThat(jdbc.queryForObject("SELECT created_by FROM prn_template WHERE template_no='TPL64'", String.class))
                .isEqualTo(operator);
        service.heartbeat(BRIDGE, "DEVICE", true);
        assertThat(service.claim(BRIDGE, "DEVICE").jobNo()).isEqualTo(job.jobNo());
        service.revokeDevice(owner, "DEVICE", "owner stopped executor", "LONG-ACTOR-REVOKE");
        assertThat(jdbc.queryForObject("SELECT revoked_by FROM prn_device WHERE device_no='DEVICE'", String.class))
                .isEqualTo(operator);
    }

    @Test
    void trackUnknownReceipt() {
        var job = service.enqueue(OWNER, new Enqueue("EVENT", null, "PART", DOCUMENT)).getFirst();
        assertThat(job.status()).isEqualTo("PENDING");
        assertThatThrownBy(() -> service.claim(BRIDGE, "DEVICE")).isInstanceOf(BizException.class);
        service.heartbeat(BRIDGE, "DEVICE", true);
        var claim = service.claim(BRIDGE, "DEVICE");
        assertThat(claim.jobNo()).isEqualTo(job.jobNo());
        assertThat(service.claim(BRIDGE, "DEVICE")).isNull();
        clock.advance(61);
        assertThat(service.get(OWNER, job.jobNo()).status()).isEqualTo("UNKNOWN");
        service.heartbeat(BRIDGE, "DEVICE", true);
        assertThat(jdbc.queryForObject("SELECT status FROM prn_job", String.class))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT status FROM prn_attempt", String.class))
                .isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> service.retry(OWNER, job.jobNo()))
                .isInstanceOf(BizException.class);
        assertThat(service.claim(BRIDGE, "DEVICE")).isNull();
        var confirmed =
                service.acknowledge(
                        BRIDGE,
                        "DEVICE",
                        new Acknowledge(
                                claim.attemptNo(),
                                claim.leaseToken(),
                                "SUCCESS",
                                "CHANNEL",
                                "PRINTED"));
        assertThat(confirmed.status()).isEqualTo("SUCCESS");
        assertThat(confirmed.attempts()).hasSize(1);
    }

    @Test
    void onlyExplicitNotPrintedCanRetryAndOldAttemptCannotAcknowledgeNewLease() {
        var no =
                service.enqueue(OWNER, new Enqueue("EVENT", null, "PART", DOCUMENT))
                        .getFirst()
                        .jobNo();
        service.heartbeat(BRIDGE, "DEVICE", true);
        var first = service.claim(BRIDGE, "DEVICE");
        var failed =
                service.acknowledge(
                        BRIDGE,
                        "DEVICE",
                        new Acknowledge(
                                first.attemptNo(),
                                first.leaseToken(),
                                "FAILED",
                                null,
                                "NOT_PRINTED"));
        assertThat(failed.status()).isEqualTo("FAILED");
        service.retry(OWNER, no);
        var second = service.claim(BRIDGE, "DEVICE");
        assertThat(second.attemptNo()).isNotEqualTo(first.attemptNo());
        assertThatThrownBy(
                        () ->
                                service.acknowledge(
                                        BRIDGE,
                                        "DEVICE",
                                        new Acknowledge(
                                                first.attemptNo(),
                                                first.leaseToken(),
                                                "SUCCESS",
                                                null,
                                                "PRINTED")))
                .isInstanceOf(BizException.class);
        var unknown =
                service.acknowledge(
                        BRIDGE,
                        "DEVICE",
                        new Acknowledge(
                                second.attemptNo(),
                                second.leaseToken(),
                                "FAILED",
                                null,
                                "NETWORK_TIMEOUT"));
        assertThat(unknown.status()).isEqualTo("UNKNOWN");
        assertThat(unknown.attempts()).hasSize(2);
        assertThatThrownBy(() -> service.retry(OWNER, no)).isInstanceOf(BizException.class);
    }

    @Test
    void retainReprintOrigin() {
        var first = service.enqueue(OWNER, new Enqueue("EVENT", null, "PART", DOCUMENT)).getFirst();
        var reprint = service.reprint(OWNER, first.jobNo(), "纸张损坏", "REPRINT-1");
        assertThat(reprint.originalJobNo()).isEqualTo(first.jobNo());
        assertThat(reprint.requestedBy()).isEqualTo("OPERATOR");
        assertThat(reprint.renderedContent())
                .startsWith("REPRINT / 补打 " + first.jobNo() + " / 纸张损坏");
        assertThat(reprint.document()).isEqualTo(first.document());
        assertThat(service.reprint(OWNER, first.jobNo(), "纸张损坏", "REPRINT-1").jobNo())
                .isEqualTo(reprint.jobNo());
        assertThatThrownBy(
                        () -> service.reprint(OWNER, first.jobNo(), "another reason", "REPRINT-1"))
                .isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prn_job", Integer.class)).isEqualTo(2);
    }

    @Test
    void freezeReceiptVersionAndRouteTargets() {
        var initial =
                service.enqueue(OWNER, new Enqueue("EVENT", null, "PART", DOCUMENT)).getFirst();
        service.publishTemplate(
                OWNER, new TemplateReq("TPL2", "ORDER", 2, "ORDER", "NEW {{document}}"));
        service.putRoute(OWNER, new RouteReq("ROUTE", "ORDER", null, "DEVICE", "TPL2", 2));
        var replay = service.enqueue(OWNER, new Enqueue("EVENT", null, "PART", DOCUMENT));
        assertThat(replay).hasSize(1);
        assertThat(replay.getFirst().jobNo()).isEqualTo(initial.jobNo());
        assertThat(replay.getFirst().templateNo()).isEqualTo("TPL1");
        assertThatThrownBy(
                        () ->
                                service.publishTemplate(
                                        OWNER,
                                        new TemplateReq(
                                                "TPL1",
                                                "ORDER",
                                                1,
                                                "ORDER",
                                                "CHANGED {{document}}")))
                .isInstanceOf(BizException.class);
        var changed =
                new Document(
                        1,
                        "ORDER",
                        "BATCH",
                        "订单",
                        null,
                        List.of(new Line("SKU", "9", null, null)),
                        "不代表收款");
        assertThatThrownBy(
                        () -> service.enqueue(OWNER, new Enqueue("EVENT", null, "PART", changed)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void rejectUnauthorizedDeviceAndCrossStoreRead() {
        var no =
                service.enqueue(OWNER, new Enqueue("EVENT", null, "PART", DOCUMENT))
                        .getFirst()
                        .jobNo();
        assertThatThrownBy(() -> service.heartbeat(OWNER, "DEVICE", true))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.claim(new Scope("OTHER", "S", "BRIDGE"), "DEVICE"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.get(new Scope("E", "OTHER", "OPERATOR"), no))
                .isInstanceOf(BizException.class);
        service.heartbeat(BRIDGE, "DEVICE", true);
        var claim = service.claim(BRIDGE, "DEVICE");
        assertThatThrownBy(
                        () ->
                                service.acknowledge(
                                        BRIDGE,
                                        "DEVICE",
                                        new Acknowledge(
                                                claim.attemptNo(),
                                                "wrong",
                                                "SUCCESS",
                                                null,
                                                "PRINTED")))
                .isInstanceOf(BizException.class);
        assertThat(service.get(OWNER, no).status()).isEqualTo("SENT");
        clock.advance(91);
        assertThat(service.configuration(OWNER).devices().getFirst().online()).isFalse();
    }

    @Test
    void outboxFailureRollsBackTheWholeLogicalJob() {
        doThrow(new IllegalStateException("outbox unavailable")).when(outbox).publish(any(), any());
        assertThatThrownBy(
                        () -> service.enqueue(OWNER, new Enqueue("EVENT", null, "PART", DOCUMENT)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prn_job", Integer.class)).isZero();
    }

    @Test
    void identicalBindingRetryDoesNotInvalidateDeviceGrantEpoch() {
        long before = service.configuration(OWNER).devices().getFirst().authorizationVersion();
        service.bindDevice(
                OWNER,
                new DeviceReq("DEVICE", "GENERIC", "LAN", "VAULT:PRINT", "BRIDGE", "{}", true));
        assertThat(service.configuration(OWNER).devices().getFirst().authorizationVersion())
                .isEqualTo(before);
    }

    @Test
    void revokeInFlightDeviceImmediatelyAndNeverTurnUnknownIntoRetryableFailure() {
        var no =
                service.enqueue(OWNER, new Enqueue("EVENT", null, "PART", DOCUMENT))
                        .getFirst()
                        .jobNo();
        service.heartbeat(BRIDGE, "DEVICE", true);
        service.claim(BRIDGE, "DEVICE");
        var before = service.configuration(OWNER).devices().getFirst().authorizationVersion();
        var revoked = service.revokeDevice(OWNER, "DEVICE", "bridge lost", "revoke-1");
        assertThat(revoked.status()).isEqualTo("DISABLED");
        assertThat(revoked.authorizationVersion()).isGreaterThan(before);
        assertThat(service.get(OWNER, no).status()).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> service.claim(BRIDGE, "DEVICE")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.retry(OWNER, no)).isInstanceOf(BizException.class);
        assertThat(service.revokeDevice(OWNER, "DEVICE", "bridge lost", "revoke-1"))
                .isEqualTo(revoked);
        service.bindDevice(
                OWNER,
                new DeviceReq("DEVICE", "GENERIC", "LAN", "VAULT:PRINT", "BRIDGE", "{}", true));
        assertThat(service.configuration(OWNER).devices().getFirst().authorizationVersion())
                .isGreaterThan(revoked.authorizationVersion());
        assertThat(service.revokeDevice(OWNER, "DEVICE", "bridge lost", "revoke-1"))
                .isEqualTo(revoked);
        assertThat(service.configuration(OWNER).devices().getFirst().status()).isEqualTo("ACTIVE");
    }

    /** Replay only canonical column extensions; production data backfill remains a migration concern. */
    static void applyCanonicalPrintColumns(JdbcTemplate jdbc) throws Exception {
        String migration = Files.readString(Path.of("../shop-app/src/main/resources/db/migration/"
                + "V582__unified_print_capabilities.sql"));
        var statements = Pattern.compile(
                "ALTER\\s+TABLE\\s+(prn_job|prn_attempt|prn_device)\\s+(.+?);(?=\\s*(?:\\r?\\n|$))",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(migration);
        while (statements.find()) {
            for (String addition : statements.group(2).split(",\\s*(?=ADD\\s)")) {
                if (addition.stripLeading().matches("(?is)ADD\\s+COLUMN\\s+.*")) {
                    jdbc.execute("ALTER TABLE " + statements.group(1) + " " + addition.strip());
                }
            }
        }
    }

    static class TestClock extends Clock {
        private Instant instant = Instant.parse("2026-10-24T01:00:00Z");

        void advance(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
