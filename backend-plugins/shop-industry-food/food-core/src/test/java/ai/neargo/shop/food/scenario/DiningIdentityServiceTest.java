package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceCredentialVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceSessionReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.RegisterDeviceReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.StaffPartyReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.VersionReq;
import ai.neargo.shop.food.mapper.DiningIdentityMappers.AuthorizationMapper;
import ai.neargo.shop.food.mapper.DiningIdentityMappers.DeviceMapper;
import ai.neargo.shop.food.mapper.DiningIdentityMappers.OperationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.GuestSessionMapper;
import ai.neargo.shop.food.service.DiningIdentityService;
import ai.neargo.shop.food.service.impl.DiningIdentityServiceImpl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Actual primary persistence, generation/CAS, raw-secret exclusion and permanent original-key
 * replay. Not executed in coding phase.
 */
class DiningIdentityServiceTest {
    DiningIdentityService service;
    JdbcTemplate jdbc;
    OutboxPublisher outbox;
    final MutableClock clock = new MutableClock();

    @BeforeEach
    void setup() throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:identity_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(source);
        String ddl =
                Files.readString(
                                Path.of(
                                        "src/main/resources/db/industry/food/V16__food_dining_identity.sql"))
                        .replace("CHARACTER SET utf8mb4 COLLATE utf8mb4_bin", "")
                        .replace("ENGINE=InnoDB", "");
        for (String statement : ddl.split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
        jdbc.execute(
                "CREATE TABLE fnb_guest_session(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no"
                        + " VARCHAR(32),entity_no VARCHAR(32),created_at TIMESTAMP,created_by"
                        + " VARCHAR(32),updated_at TIMESTAMP,updated_by VARCHAR(32),version"
                        + " BIGINT,deleted INT,session_no VARCHAR(32) UNIQUE,check_no"
                        + " VARCHAR(32),buyer_no VARCHAR(32),device_no VARCHAR(32),token_hash"
                        + " VARBINARY(64),expires_at TIMESTAMP,scope VARCHAR(4000),store_no"
                        + " VARCHAR(32),table_no VARCHAR(32),revoked_at TIMESTAMP)");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        var plugins = new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        config.addInterceptor(plugins);
        for (var type :
                List.of(
                        DeviceMapper.class,
                        AuthorizationMapper.class,
                        OperationMapper.class,
                        GuestSessionMapper.class)) {
            config.addMapper(type);
        }
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        var template = new SqlSessionTemplate(factory.getObject());
        outbox = mock(OutboxPublisher.class);
        service =
                new DiningIdentityServiceImpl(
                        template.getMapper(DeviceMapper.class),
                        template.getMapper(AuthorizationMapper.class),
                        template.getMapper(OperationMapper.class),
                        template.getMapper(GuestSessionMapper.class),
                        mock(CheckMapper.class),
                        outbox,
                        new DataSourceTransactionManager(source),
                        JsonMapper.builder().build(),
                        clock);
    }

    DeviceCredentialVO register() {
        return service.registerDevice(
                "E", "S", "ADMIN", new RegisterDeviceReq("DEVICE", 0L, "kiosk"), "register");
    }

    @Test
    void onlyHashesPersistAndOriginalReplayNeverReissuesSecrets() {
        var device = register();
        var session =
                service.createDeviceSession(
                        "E",
                        "S",
                        "DEVICE",
                        device.rawCredential(),
                        new DeviceSessionReq(3600),
                        "session");
        assertThat(device.rawCredential()).startsWith("fd_");
        assertThat(session.accessToken()).startsWith("fs_");
        assertThat(
                        service.registerDevice(
                                        "E",
                                        "S",
                                        "ADMIN",
                                        new RegisterDeviceReq("DEVICE", 0L, "kiosk"),
                                        "register")
                                .rawCredential())
                .isNull();
        assertThat(
                        service.createDeviceSession(
                                        "E",
                                        "S",
                                        "DEVICE",
                                        device.rawCredential(),
                                        new DeviceSessionReq(3600),
                                        "session")
                                .accessToken())
                .isNull();
        for (String receipt :
                jdbc.queryForList("SELECT result_json FROM fnb_identity_operation", String.class)) {
            assertThat(receipt).doesNotContain(device.rawCredential(), session.accessToken());
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT credential_hash FROM fnb_dining_device", byte[].class))
                .containsExactly(sha256(device.rawCredential()));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT token_hash FROM fnb_guest_session WHERE session_no=?",
                                byte[].class,
                                session.party().sessionNo()))
                .containsExactly(sha256(session.accessToken()));
        var emitted = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outbox, atLeastOnce()).publish(emitted.capture(), any());
        for (var event : emitted.getAllValues()) {
            assertThat(JsonMapper.builder().build().writeValueAsString(event))
                    .doesNotContain(
                            device.rawCredential(),
                            session.accessToken(),
                            "credentialHash",
                            "tokenHash");
        }
        assertThat(device.toString()).doesNotContain(device.rawCredential());
        assertThat(session.toString()).doesNotContain(session.accessToken());
        service.validateDeviceSession(session.party().sessionNo());
        assertThat(session.party().scope()).containsExactly("CART_DEVICE");
        assertThat(session.party().buyerNo()).isNull();
        assertThat(session.party().checkNo()).isNull();
    }

    @Test
    void revokeAndReregisterCannotReviveOldDeviceSession() {
        var device = register();
        var session =
                service.createDeviceSession(
                        "E",
                        "S",
                        "DEVICE",
                        device.rawCredential(),
                        new DeviceSessionReq(3600),
                        "session");
        var revoked =
                service.revokeDevice(
                        "E", "S", "ADMIN", "DEVICE", new VersionReq(0L, "lost"), "revoke");
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThatThrownBy(() -> service.validateDeviceSession(session.party().sessionNo()))
                .isInstanceOf(BizException.class);
        service.registerDevice(
                "E",
                "S",
                "ADMIN",
                new RegisterDeviceReq("DEVICE", 1L, "replacement"),
                "reregister");
        assertThatThrownBy(() -> service.validateDeviceSession(session.party().sessionNo()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(
                        () ->
                                service.createDeviceSession(
                                        "E",
                                        "S",
                                        "DEVICE",
                                        device.rawCredential(),
                                        new DeviceSessionReq(3600),
                                        "bad"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void staffPartyIsRealGuestSourceAndCrossStoreOrRevocationCannotAuthorizeIt() {
        var created =
                service.createStaffParty(
                        "E", "S", "STAFF", new StaffPartyReq(null, "walk in", 3600), "party");
        var party = service.party("E", "S", created.party().sessionNo());
        assertThat(party.kind()).isEqualTo("GUEST");
        assertThat(party.scope()).containsExactly("STAFF_PARTY");
        assertThat(party.buyerNo()).isNull();
        assertThat(party.deviceNo()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM fnb_guest_session WHERE session_no=?",
                                Integer.class,
                                party.sessionNo()))
                .isOne();
        assertThatThrownBy(() -> service.party("E", "OTHER", party.sessionNo()))
                .isInstanceOf(BizException.class);
        service.revokeParty(
                "E", "S", "STAFF", party.sessionNo(), new VersionReq(0L, "done"), "end");
        assertThatThrownBy(() -> service.party("E", "S", party.sessionNo()))
                .isInstanceOf(BizException.class);
    }

    @Test
    void expiryAndChangedOriginalRequestRemainRejected() {
        var created =
                service.createStaffParty(
                        "E", "S", "STAFF", new StaffPartyReq(null, "walk in", 60), "party");
        clock.seconds = 61;
        assertThatThrownBy(() -> service.party("E", "S", created.party().sessionNo()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(
                        () ->
                                service.createStaffParty(
                                        "E",
                                        "S",
                                        "STAFF",
                                        new StaffPartyReq(null, "other", 60),
                                        "party"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void sameDeviceOrRevocationKeyWithChangedPayloadCannotAdvanceGeneration() {
        register();
        assertThatThrownBy(
                        () ->
                                service.registerDevice(
                                        "E",
                                        "S",
                                        "ADMIN",
                                        new RegisterDeviceReq("DEVICE", 0L, "other"),
                                        "register"))
                .isInstanceOf(BizException.class);
        assertThat(service.device("E", "S", "DEVICE").generation()).isEqualTo(1);
        service.revokeDevice("E", "S", "ADMIN", "DEVICE", new VersionReq(0L, "lost"), "revoke");
        assertThatThrownBy(
                        () ->
                                service.revokeDevice(
                                        "E",
                                        "S",
                                        "ADMIN",
                                        "DEVICE",
                                        new VersionReq(0L, "different"),
                                        "revoke"))
                .isInstanceOf(BizException.class);
        assertThat(service.device("E", "S", "DEVICE").generation()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fnb_dining_device", Integer.class))
                .isOne();
    }

    @Test
    void sourceEventFailureRollsBackPartyAndItsCredentialHashAndOperationReceipt() {
        doThrow(new IllegalStateException("event owner unavailable"))
                .when(outbox)
                .publish(any(), any());
        assertThatThrownBy(
                        () ->
                                service.createStaffParty(
                                        "E",
                                        "S",
                                        "STAFF",
                                        new StaffPartyReq(null, "walk in", 3600),
                                        "rollback"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fnb_guest_session", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM fnb_identity_authorization", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM fnb_identity_operation", Integer.class))
                .isZero();
    }

    private static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static class MutableClock extends Clock {
        long seconds;

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
            return Instant.parse("2026-10-24T00:00:00Z").plusSeconds(seconds);
        }
    }
}
