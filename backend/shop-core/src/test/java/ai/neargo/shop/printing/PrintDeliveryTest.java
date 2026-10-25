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
    private PrintService service;
    private JdbcTemplate jdbc;
    private OutboxPublisher outbox;
    private TestClock clock;

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
        // Mirrors V579; H2 accepts one ADD per ALTER, MySQL's multi-ADD form is a syntax error here.
        jdbc.execute("ALTER TABLE prn_device ADD revoke_reason VARCHAR(255)");
        jdbc.execute("ALTER TABLE prn_device ADD revoked_by VARCHAR(32)");
        jdbc.execute("ALTER TABLE prn_device ADD revoke_key VARCHAR(128)");
        jdbc.execute("ALTER TABLE prn_device ADD revocation_history CLOB");
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
        outbox = mock(OutboxPublisher.class);
        clock = new TestClock();
        service =
                new PrintServiceImpl(
                        session.getMapper(DeviceMapper.class),
                        session.getMapper(TemplateMapper.class),
                        session.getMapper(RouteMapper.class),
                        session.getMapper(JobMapper.class),
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

    private static class TestClock extends Clock {
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
