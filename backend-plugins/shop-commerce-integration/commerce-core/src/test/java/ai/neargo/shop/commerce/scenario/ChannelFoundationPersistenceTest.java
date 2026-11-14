package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration;
import ai.neargo.shop.commerce.config.CommerceSchemaValidator;
import ai.neargo.shop.commerce.dto.CreateChannelAccountReq;
import ai.neargo.shop.commerce.dto.CreateChannelConnectionReq;
import ai.neargo.shop.commerce.service.ChannelAccountService;
import ai.neargo.shop.commerce.service.ChannelConnectionService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort.Proof;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

class ChannelFoundationPersistenceTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(PrimaryPersistenceConfiguration.class)
                .withConfiguration(AutoConfigurations.of(CommerceCoreAutoConfiguration.class))
                .withPropertyValues(
                        "shop.commerce.enabled=true",
                        "shop.commerce.migration=MIGRATE",
                        "spring.datasource.url=jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=");
    }

    private ApplicationContextRunner verifiedRunner() {
        return runner().withBean(
                        ChannelConnectionIdentityPort.class,
                        () ->
                                identity -> {
                                    assertThat(
                                                    TransactionSynchronizationManager
                                                            .isActualTransactionActive())
                                            .isFalse();
                                    return new Proof(
                                            "IMPOSTOR".equals(identity.externalAccountRef())
                                                    ? Status.UNKNOWN
                                                    : Status.VERIFIED,
                                            identity,
                                            "TRUSTED_TEST_RECEIPT",
                                            Instant.now().minusSeconds(1),
                                            Instant.now().plusSeconds(60));
                                });
    }

    private void seed(JdbcTemplate sql) {
        sql.update(
                "INSERT INTO cim_provider(provider_no,provider_code,name,status) VALUES"
                        + " ('P','TEST','Test','ACTIVE')");
        sql.update(
                "INSERT INTO"
                    + " cim_product_profile(profile_no,provider_no,product_code,market,protocol_code,capability_definition,identity_schema_version,identity_namespace,store_identity_scope,status)"
                    + " VALUES"
                    + " ('PP','P','DELIVERY','TW','TEST_V1','{}',1,'test:TW:v1','GLOBAL','VERIFIED')");
    }

    @Test
    void draftsPersistWithImmutableRoutingAndReplayOriginalSnapshot() {
        verifiedRunner()
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            seed(sql);
                            var accounts = c.getBean(ChannelAccountService.class);
                            var req = new CreateChannelAccountReq("PP", "REMOTE_ACCOUNT");
                            var first = accounts.createAccount("E", "A", req, "key");
                            assertThat(accounts.createAccount("E", "A", req, "key"))
                                    .isEqualTo(first);
                            assertThat(first.status()).isEqualTo("DRAFT");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM cim_account", Long.class))
                                    .isEqualTo(1);
                            assertThatThrownBy(
                                            () ->
                                                    accounts.createAccount(
                                                            "E",
                                                            "A",
                                                            new CreateChannelAccountReq(
                                                                    "PP", "DIFFERENT"),
                                                            "key"))
                                    .isInstanceOf(BizException.class);
                            assertThatThrownBy(() -> accounts.createAccount("E", "B", req, "key"))
                                    .isInstanceOf(BizException.class);
                            assertThatThrownBy(
                                            () -> accounts.getAccount("OTHER", first.accountNo()))
                                    .isInstanceOf(BizException.class);
                            var connections = c.getBean(ChannelConnectionService.class);
                            var create =
                                    new CreateChannelConnectionReq(
                                            first.accountNo(),
                                            "REMOTE_STORE",
                                            Owner.FOOD,
                                            "INSTANT");
                            var link =
                                    connections.createConnection("E", "S", "A", create, "link-key");
                            assertThat(link.status()).isEqualTo("DRAFT");
                            assertThat(link.sourceOwner()).isEqualTo(Owner.FOOD);
                            assertThat(
                                            connections.createConnection(
                                                    "E", "S", "A", create, "link-key"))
                                    .isEqualTo(link);
                            assertThatThrownBy(
                                            () ->
                                                    connections.createConnection(
                                                            "E",
                                                            "S",
                                                            "A",
                                                            new CreateChannelConnectionReq(
                                                                    first.accountNo(),
                                                                    "REMOTE_STORE",
                                                                    Owner.RETAIL,
                                                                    "INSTANT"),
                                                            "different-key"))
                                    .isInstanceOf(BizException.class);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM cim_connection_revision",
                                                    Long.class))
                                    .isEqualTo(1);
                            assertThat(connections.listConnections("E", "S", 1, 20).total())
                                    .isEqualTo(1);
                            assertThat(connections.listConnections("OTHER", "S", 1, 20).total())
                                    .isZero();
                        });
    }

    @Test
    void remoteIdentityCannotBeReclaimedByAnotherEntityAndFailedRequestRollsBack() {
        verifiedRunner()
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            seed(sql);
                            var accounts = c.getBean(ChannelAccountService.class);
                            var connections = c.getBean(ChannelConnectionService.class);
                            var a =
                                    accounts.createAccount(
                                            "E1",
                                            "A",
                                            new CreateChannelAccountReq("PP", "R1"),
                                            "a");
                            var b =
                                    accounts.createAccount(
                                            "E2",
                                            "B",
                                            new CreateChannelAccountReq("PP", "R2"),
                                            "b");
                            connections.createConnection(
                                    "E1",
                                    "S1",
                                    "A",
                                    new CreateChannelConnectionReq(
                                            a.accountNo(), "GLOBAL_STORE", Owner.FOOD, "INSTANT"),
                                    "c");
                            assertThatThrownBy(
                                            () ->
                                                    connections.createConnection(
                                                            "E2",
                                                            "S2",
                                                            "B",
                                                            new CreateChannelConnectionReq(
                                                                    b.accountNo(),
                                                                    "GLOBAL_STORE",
                                                                    Owner.RETAIL,
                                                                    "INSTANT"),
                                                            "d"))
                                    .isInstanceOf(BizException.class);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM cim_request_journal",
                                                    Long.class))
                                    .isEqualTo(3);
                            assertThatThrownBy(
                                            () ->
                                                    connections.createConnection(
                                                            "E2",
                                                            "S2",
                                                            "B",
                                                            new CreateChannelConnectionReq(
                                                                    a.accountNo(),
                                                                    "OTHER_STORE",
                                                                    Owner.RETAIL,
                                                                    "INSTANT"),
                                                            "e"))
                                    .isInstanceOf(BizException.class);
                            assertThat(connections.listConnections("E2", "S2", 1, 20).total())
                                    .isZero();
                        });
    }

    @Test
    void queryPagesAreBoundedAndNoUnverifiedProtocolCanClaimIdentity() {
        verifiedRunner()
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            seed(sql);
                            var accounts = c.getBean(ChannelAccountService.class);
                            assertThatThrownBy(() -> accounts.listAccounts("E", 0, 101))
                                    .isInstanceOf(BizException.class);
                            var a =
                                    accounts.createAccount(
                                            "E", "A", new CreateChannelAccountReq("PP", "R"), "a");
                            sql.update(
                                    "UPDATE cim_product_profile SET status='UNVERIFIED' WHERE"
                                            + " profile_no='PP'");
                            assertThatThrownBy(
                                            () ->
                                                    c.getBean(ChannelConnectionService.class)
                                                            .createConnection(
                                                                    "E",
                                                                    "S",
                                                                    "A",
                                                                    new CreateChannelConnectionReq(
                                                                            a.accountNo(),
                                                                            "STORE",
                                                                            Owner.FOOD,
                                                                            "INSTANT"),
                                                                    "c"))
                                    .isInstanceOf(BizException.class);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM cim_store_connection",
                                                    Long.class))
                                    .isZero();
                        });
    }

    @Test
    void disabledModuleDoesNotCreateMappersOrTouchDatabase() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommerceCoreAutoConfiguration.class))
                .run(
                        c ->
                                assertThat(c)
                                        .hasNotFailed()
                                        .doesNotHaveBean(ChannelAccountService.class));
    }

    @Test
    void validationNeverCreatesMissingHistoryOrImplicitBaseline() {
        runner().withPropertyValues("shop.commerce.migration=VALIDATE")
                .run(
                        c -> {
                            assertThat(c).hasFailed();
                            assertThat(c.getStartupFailure())
                                    .hasRootCauseMessage("Missing commerce migration history");
                        });
    }

    @Test
    void validateDetectsAppliedSchemaDriftWithoutRepairingIt() {
        runner().run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var source = c.getBean(DataSource.class);
                            var sql = new JdbcTemplate(source);
                            sql.execute(
                                    "ALTER TABLE cim_store_connection DROP COLUMN"
                                            + " channel_store_key");
                            assertThatThrownBy(
                                            () ->
                                                    new CommerceSchemaValidator()
                                                            .initialize(source, "VALIDATE"))
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessageContaining("Missing commerce columns");
                        });
    }

    @Test
    void validateDetectsMissingIdentityUniquenessEvenWhenHistoryIsValid() {
        runner().run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var source = c.getBean(DataSource.class);
                            var sql = new JdbcTemplate(source);
                            sql.execute(
                                    "ALTER TABLE cim_store_connection DROP INDEX"
                                            + " uk_cim_remote_store");
                            assertThatThrownBy(
                                            () ->
                                                    new CommerceSchemaValidator()
                                                            .initialize(source, "VALIDATE"))
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessageContaining("Missing commerce unique key");
                        });
    }

    @Test
    void unprovenDraftCannotReserveLegitimateMerchantsRemoteIdentity() {
        runner().run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            seed(sql);
                            var accounts = c.getBean(ChannelAccountService.class);
                            var attacker =
                                    accounts.createAccount(
                                            "ATTACKER",
                                            "A",
                                            new CreateChannelAccountReq("PP", "IMPOSTOR"),
                                            "a");
                            assertThatThrownBy(
                                            () ->
                                                    c.getBean(ChannelConnectionService.class)
                                                            .createConnection(
                                                                    "ATTACKER",
                                                                    "S",
                                                                    "A",
                                                                    new CreateChannelConnectionReq(
                                                                            attacker.accountNo(),
                                                                            "VICTIM_STORE",
                                                                            Owner.FOOD,
                                                                            "INSTANT"),
                                                                    "c"))
                                    .isInstanceOf(BizException.class);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM cim_store_connection",
                                                    Long.class))
                                    .isZero();
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM cim_connection_revision",
                                                    Long.class))
                                    .isZero();
                        });
    }

    @Test
    void rejectedImpostorLeavesIdentityAvailableForProvenOwner() {
        verifiedRunner()
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            seed(sql);
                            var accounts = c.getBean(ChannelAccountService.class);
                            var service = c.getBean(ChannelConnectionService.class);
                            var bad =
                                    accounts.createAccount(
                                            "BAD",
                                            "A",
                                            new CreateChannelAccountReq("PP", "IMPOSTOR"),
                                            "a");
                            var good =
                                    accounts.createAccount(
                                            "GOOD",
                                            "B",
                                            new CreateChannelAccountReq("PP", "OWNER"),
                                            "b");
                            assertThatThrownBy(
                                            () ->
                                                    service.createConnection(
                                                            "BAD",
                                                            "S1",
                                                            "A",
                                                            new CreateChannelConnectionReq(
                                                                    bad.accountNo(),
                                                                    "SHARED_REMOTE",
                                                                    Owner.FOOD,
                                                                    "INSTANT"),
                                                            "c"))
                                    .isInstanceOf(BizException.class);
                            var connection =
                                    service.createConnection(
                                            "GOOD",
                                            "S2",
                                            "B",
                                            new CreateChannelConnectionReq(
                                                    good.accountNo(),
                                                    "SHARED_REMOTE",
                                                    Owner.FOOD,
                                                    "INSTANT"),
                                            "d");
                            assertThat(connection.status()).isEqualTo("DRAFT");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT identity_receipt_ref FROM"
                                                            + " cim_store_connection",
                                                    String.class))
                                    .isEqualTo("TRUSTED_TEST_RECEIPT");
                        });
    }

    @Test
    void provenIdentityReplayDoesNotRepeatExternalVerification() {
        var calls = new AtomicInteger();
        runner().withBean(
                        ChannelConnectionIdentityPort.class,
                        () ->
                                identity -> {
                                    if (calls.incrementAndGet() != 1)
                                        throw new IllegalStateException(
                                                "External verifier is now unavailable");
                                    assertThat(
                                                    TransactionSynchronizationManager
                                                            .isActualTransactionActive())
                                            .isFalse();
                                    return new Proof(
                                            Status.VERIFIED,
                                            identity,
                                            "RECEIPT",
                                            Instant.now().minusSeconds(1),
                                            Instant.now().plusSeconds(60));
                                })
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            seed(sql);
                            var account =
                                    c.getBean(ChannelAccountService.class)
                                            .createAccount(
                                                    "E",
                                                    "A",
                                                    new CreateChannelAccountReq("PP", "OWNER"),
                                                    "a");
                            var service = c.getBean(ChannelConnectionService.class);
                            var req =
                                    new CreateChannelConnectionReq(
                                            account.accountNo(), "REMOTE", Owner.RETAIL, "PARCEL");
                            var first = service.createConnection("E", "S", "A", req, "c");
                            assertThat(service.createConnection("E", "S", "A", req, "c"))
                                    .isEqualTo(first);
                            assertThat(calls).hasValue(1);
                        });
    }

    @Test
    void staleAccountDuringRemoteVerificationCannotClaimIdentity() {
        var database = new AtomicReference<JdbcTemplate>();
        runner().withBean(
                        ChannelConnectionIdentityPort.class,
                        () ->
                                identity -> {
                                    assertThat(
                                                    TransactionSynchronizationManager
                                                            .isActualTransactionActive())
                                            .isFalse();
                                    database.get()
                                            .update(
                                                    "UPDATE cim_account SET version=version+1 WHERE"
                                                        + " account_no=?",
                                                    identity.accountNo());
                                    return new Proof(
                                            Status.VERIFIED,
                                            identity,
                                            "RECEIPT",
                                            Instant.now().minusSeconds(1),
                                            Instant.now().plusSeconds(60));
                                })
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            seed(sql);
                            database.set(sql);
                            var account =
                                    c.getBean(ChannelAccountService.class)
                                            .createAccount(
                                                    "E",
                                                    "A",
                                                    new CreateChannelAccountReq("PP", "OWNER"),
                                                    "a");
                            assertThatThrownBy(
                                            () ->
                                                    c.getBean(ChannelConnectionService.class)
                                                            .createConnection(
                                                                    "E",
                                                                    "S",
                                                                    "A",
                                                                    new CreateChannelConnectionReq(
                                                                            account.accountNo(),
                                                                            "REMOTE",
                                                                            Owner.FOOD,
                                                                            "INSTANT"),
                                                                    "c"))
                                    .isInstanceOf(BizException.class);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM cim_store_connection",
                                                    Long.class))
                                    .isZero();
                        });
    }

    @Test
    void expiredProofCannotClaimIdentity() {
        runner().withBean(
                        ChannelConnectionIdentityPort.class,
                        () ->
                                identity ->
                                        new Proof(
                                                Status.VERIFIED,
                                                identity,
                                                "EXPIRED",
                                                Instant.now().minusSeconds(120),
                                                Instant.now().minusSeconds(1)))
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            seed(sql);
                            var account =
                                    c.getBean(ChannelAccountService.class)
                                            .createAccount(
                                                    "E",
                                                    "A",
                                                    new CreateChannelAccountReq("PP", "OWNER"),
                                                    "a");
                            assertThatThrownBy(
                                            () ->
                                                    c.getBean(ChannelConnectionService.class)
                                                            .createConnection(
                                                                    "E",
                                                                    "S",
                                                                    "A",
                                                                    new CreateChannelConnectionReq(
                                                                            account.accountNo(),
                                                                            "REMOTE",
                                                                            Owner.FOOD,
                                                                            "INSTANT"),
                                                                    "c"))
                                    .isInstanceOf(BizException.class);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM cim_store_connection",
                                                    Long.class))
                                    .isZero();
                        });
    }
}
