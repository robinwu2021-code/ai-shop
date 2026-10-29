package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration;
import ai.neargo.shop.commerce.dto.AuthorizeChannelAccountReq;
import ai.neargo.shop.commerce.dto.CreateChannelAccountReq;
import ai.neargo.shop.commerce.dto.RevokeChannelAuthorizationReq;
import ai.neargo.shop.commerce.service.ChannelAccountService;
import ai.neargo.shop.commerce.service.ChannelAuthorizationService;
import ai.neargo.shop.commerce.service.ChannelOperationService;
import ai.neargo.shop.commerce.service.impl.ChannelAuthorizationServiceImpl;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

class ChannelAuthorizationTest {
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

    static class Gateway implements ChannelAuthorizationGateway {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger exchanges = new AtomicInteger();
        final AtomicInteger revokes = new AtomicInteger();
        AuthorizationContext context;
        boolean timeout;
        boolean wrongAccount;
        boolean badUrl;
        boolean revokeUnknown;
        boolean exchangeTimeout, recoveryKnown, recoveryTerminal, wrongRecoveryState;
        Runnable duringRecovery;
        Grant originalGrant;
        int recoveryQueries;
        String extraQuery;
        Runnable earlyCallback;

        public String profileNo() {
            return "PP";
        }

        public Set<String> returnTargetCodes() {
            return Set.of("B_APP");
        }

        public Set<String> authorizationOrigins() {
            return Set.of("https://auth.example.test");
        }

        public Set<String> callbackUris() {
            return Set.of("https://shop.example.test/callback/commerce/TEST/authorization");
        }

        public Start begin(AuthorizationContext request) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            context = request;
            starts.incrementAndGet();
            if (timeout) throw new IllegalStateException("timeout with secret, never persisted");
            if (earlyCallback != null) earlyCallback.run();
            String url =
                    "https://auth.example.test/authorize?state="
                            + request.state()
                            + "&redirect_uri=https%3A%2F%2Fshop.example.test%2Fcallback%2Fcommerce%2FTEST%2Fauthorization";
            if (badUrl) url += "&client_secret=never-expose";
            if (extraQuery != null) url += "&" + extraQuery;
            return new Start(url, "START_RECEIPT");
        }

        public Grant exchange(AuthorizationContext request, String code) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            exchanges.incrementAndGet();
            originalGrant =
                    new Grant(
                            wrongAccount ? "OTHER" : request.externalAccountRef(),
                            "vault:opaque-ref",
                            Set.of("ORDER", "CATALOG"),
                            Instant.now().minusSeconds(1),
                            Instant.now().plusSeconds(3600),
                            "GRANT_RECEIPT");
            if (exchangeTimeout)
                throw new IllegalStateException("provider stored grant before timeout");
            return originalGrant;
        }

        @Override
        public AuthorizationRecovery queryAuthorization(AuthorizationQuery query) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(query.operationNo()).isEqualTo(context.operationNo());
            recoveryQueries++;
            if (duringRecovery != null) {
                var hook = duringRecovery;
                duringRecovery = null;
                hook.run();
            }
            if (recoveryTerminal)
                return new AuthorizationRecovery(
                        query,
                        RecoveryStatus.TERMINAL_FAILED,
                        null,
                        null,
                        "ORIGINAL_TERMINAL_RECEIPT");
            if (!recoveryKnown)
                return new AuthorizationRecovery(query, RecoveryStatus.UNKNOWN, null, null, null);
            if (query.callbackConsumed())
                return new AuthorizationRecovery(
                        query, RecoveryStatus.FOUND, null, originalGrant, "ORIGINAL_GRANT_QUERY");
            var start =
                    new Start(
                            "https://auth.example.test/authorize?state="
                                    + (wrongRecoveryState ? "X".repeat(43) : context.state())
                                    + "&redirect_uri=https%3A%2F%2Fshop.example.test%2Fcallback%2Fcommerce%2FTEST%2Fauthorization",
                            "ORIGINAL_START");
            return new AuthorizationRecovery(
                    query, RecoveryStatus.FOUND, start, null, "ORIGINAL_START_QUERY");
        }

        @Override
        public RevocationRecovery queryRevocation(RevocationContext query) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            recoveryQueries++;
            return new RevocationRecovery(
                    query,
                    recoveryKnown ? RecoveryStatus.FOUND : RecoveryStatus.UNKNOWN,
                    recoveryKnown ? new Revocation(true, "ORIGINAL_REVOKE") : null,
                    recoveryKnown ? "ORIGINAL_REVOKE_QUERY" : null);
        }

        public Revocation revoke(RevocationContext request) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            revokes.incrementAndGet();
            return new Revocation(!revokeUnknown, "REVOKE_RECEIPT");
        }
    }

    @Test
    void grantAndRevocationUseOriginalOperationAndNeverExposeCredentialReference() {
        var gateway = new Gateway();
        runner().withBean(ChannelAuthorizationGateway.class, () -> gateway)
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            seed(sql);
                            var accounts = c.getBean(ChannelAccountService.class);
                            var service = c.getBean(ChannelAuthorizationService.class);
                            var operations = c.getBean(ChannelOperationService.class);
                            var account =
                                    accounts.createAccount(
                                            "E",
                                            "A",
                                            new CreateChannelAccountReq("PP", "REMOTE"),
                                            "create");
                            var req = new AuthorizeChannelAccountReq(0L, "B_APP");
                            var first =
                                    service.authorize(
                                            "E", account.accountNo(), "A", req, "authorize");
                            assertThat(first.status()).isEqualTo("SENDING");
                            assertThat(
                                            service.authorize(
                                                    "E",
                                                    account.accountNo(),
                                                    "A",
                                                    req,
                                                    "authorize"))
                                    .isEqualTo(first);
                            assertThat(gateway.starts).hasValue(1);
                            assertThat(operations.getOperation("E", first.operationNo()).status())
                                    .isEqualTo("SUBMITTED");
                            assertThatThrownBy(
                                            () ->
                                                    service.authorize(
                                                            "E",
                                                            "DIFFERENT",
                                                            "A",
                                                            req,
                                                            "authorize"))
                                    .isInstanceOf(BizException.class);
                            assertThatThrownBy(
                                            () ->
                                                    service.completeAuthorization(
                                                            "OTHER",
                                                            gateway.context.state(),
                                                            "CODE",
                                                            null))
                                    .isInstanceOf(BizException.class);
                            var callback =
                                    service.completeAuthorization(
                                            "TEST", gateway.context.state(), "CODE", null);
                            assertThat(callback.status()).isEqualTo("SUCCEEDED");
                            assertThat(
                                            service.completeAuthorization(
                                                            "TEST",
                                                            gateway.context.state(),
                                                            "CODE",
                                                            null)
                                                    .status())
                                    .isEqualTo("SUCCEEDED");
                            assertThat(gateway.exchanges).hasValue(1);
                            var active = accounts.getAccount("E", account.accountNo());
                            assertThat(active.status()).isEqualTo("ACTIVE");
                            var history = service.listRevisions("E", account.accountNo(), 1, 20);
                            assertThat(history.total()).isEqualTo(1);
                            assertThat(history.records().getFirst().current()).isTrue();
                            assertThat(history.toString()).doesNotContain("vault:");
                            var revoke =
                                    service.revoke(
                                            "E",
                                            account.accountNo(),
                                            "A",
                                            new RevokeChannelAuthorizationReq(
                                                    active.version(),
                                                    "OWNER_REQUEST",
                                                    "disconnect"),
                                            "revoke");
                            assertThat(operations.getOperation("E", revoke.operationNo()).status())
                                    .isEqualTo("SUCCEEDED");
                            assertThat(accounts.getAccount("E", account.accountNo()).status())
                                    .isEqualTo("REVOKED");
                            assertThat(
                                            service.listRevisions("E", account.accountNo(), 1, 20)
                                                    .records()
                                                    .getFirst()
                                                    .current())
                                    .isFalse();
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM cim_operation_attempt",
                                                    Long.class))
                                    .isEqualTo(3);
                        });
    }

    @Test
    void missingGatewayCannotCreateAnAuthorizationIntent() {
        runner().run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            seed(sql);
                            var account =
                                    c.getBean(ChannelAccountService.class)
                                            .createAccount(
                                                    "E",
                                                    "A",
                                                    new CreateChannelAccountReq("PP", "REMOTE"),
                                                    "create");
                            assertThatThrownBy(
                                            () ->
                                                    c.getBean(ChannelAuthorizationService.class)
                                                            .authorize(
                                                                    "E",
                                                                    account.accountNo(),
                                                                    "A",
                                                                    new AuthorizeChannelAccountReq(
                                                                            0L, "B_APP"),
                                                                    "authorize"))
                                    .isInstanceOf(BizException.class);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM cim_operation",
                                                    Long.class))
                                    .isZero();
                        });
    }

    @Test
    void timeoutIsUnknownAndReplayDoesNotBlindlyResend() {
        var gateway = new Gateway();
        gateway.timeout = true;
        runner().withBean(ChannelAuthorizationGateway.class, () -> gateway)
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
                                                    new CreateChannelAccountReq("PP", "REMOTE"),
                                                    "create");
                            var service = c.getBean(ChannelAuthorizationService.class);
                            var req = new AuthorizeChannelAccountReq(0L, "B_APP");
                            var action =
                                    service.authorize("E", account.accountNo(), "A", req, "key");
                            assertThat(service.authorize("E", account.accountNo(), "A", req, "key"))
                                    .isEqualTo(action);
                            var op =
                                    c.getBean(ChannelOperationService.class)
                                            .getOperation("E", action.operationNo());
                            assertThat(op.status()).isEqualTo("UNKNOWN");
                            assertThat(op.authorizationUrl()).isNull();
                            assertThat(gateway.starts).hasValue(1);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT error_code FROM cim_operation_attempt",
                                                    String.class))
                                    .isEqualTo("PROVIDER_UNCONFIRMED");
                        });
    }

    @Test
    void wrongRemoteAccountCannotBecomeActiveAndStateIsConsumedOnce() {
        var gateway = new Gateway();
        gateway.wrongAccount = true;
        runner().withBean(ChannelAuthorizationGateway.class, () -> gateway)
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
                                                    new CreateChannelAccountReq("PP", "REMOTE"),
                                                    "create");
                            var service = c.getBean(ChannelAuthorizationService.class);
                            service.authorize(
                                    "E",
                                    account.accountNo(),
                                    "A",
                                    new AuthorizeChannelAccountReq(0L, "B_APP"),
                                    "key");
                            assertThat(
                                            service.completeAuthorization(
                                                            "TEST",
                                                            gateway.context.state(),
                                                            "CODE",
                                                            null)
                                                    .status())
                                    .isEqualTo("UNKNOWN");
                            service.completeAuthorization(
                                    "TEST", gateway.context.state(), "CODE", null);
                            assertThat(gateway.exchanges).hasValue(1);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM"
                                                            + " cim_authorization_revision",
                                                    Long.class))
                                    .isZero();
                            assertThat(
                                            c.getBean(ChannelAccountService.class)
                                                    .getAccount("E", account.accountNo())
                                                    .status())
                                    .isNotEqualTo("ACTIVE");
                        });
    }

    @Test
    void earlyCallbackSuccessCannotBeRolledBackByLateBeginResponse() {
        var gateway = new Gateway();
        runner().withBean(ChannelAuthorizationGateway.class, () -> gateway)
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
                                                    new CreateChannelAccountReq("PP", "REMOTE"),
                                                    "create");
                            var service = c.getBean(ChannelAuthorizationService.class);
                            gateway.earlyCallback =
                                    () ->
                                            service.completeAuthorization(
                                                    "TEST", gateway.context.state(), "CODE", null);
                            var action =
                                    service.authorize(
                                            "E",
                                            account.accountNo(),
                                            "A",
                                            new AuthorizeChannelAccountReq(0L, "B_APP"),
                                            "key");
                            assertThat(
                                            c.getBean(ChannelOperationService.class)
                                                    .getOperation("E", action.operationNo())
                                                    .status())
                                    .isEqualTo("SUCCEEDED");
                            assertThat(gateway.exchanges).hasValue(1);
                        });
    }

    @Test
    void unsafeAuthorizationUrlIsNeverReturned() {
        var gateway = new Gateway();
        gateway.badUrl = true;
        runner().withBean(ChannelAuthorizationGateway.class, () -> gateway)
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
                                                    new CreateChannelAccountReq("PP", "REMOTE"),
                                                    "create");
                            var action =
                                    c.getBean(ChannelAuthorizationService.class)
                                            .authorize(
                                                    "E",
                                                    account.accountNo(),
                                                    "A",
                                                    new AuthorizeChannelAccountReq(0L, "B_APP"),
                                                    "key");
                            var result =
                                    c.getBean(ChannelOperationService.class)
                                            .getOperation("E", action.operationNo());
                            assertThat(result.status()).isEqualTo("UNKNOWN");
                            assertThat(result.authorizationUrl()).isNull();
                            assertThat(result.toString()).doesNotContain("never-expose");
                        });
    }

    @Test
    void expiredAndForgedStateNeverExchangeCode() {
        var gateway = new Gateway();
        runner().withBean(ChannelAuthorizationGateway.class, () -> gateway)
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
                                                    new CreateChannelAccountReq("PP", "REMOTE"),
                                                    "create");
                            var service = c.getBean(ChannelAuthorizationService.class);
                            var action =
                                    service.authorize(
                                            "E",
                                            account.accountNo(),
                                            "A",
                                            new AuthorizeChannelAccountReq(0L, "B_APP"),
                                            "key");
                            assertThatThrownBy(
                                            () ->
                                                    service.completeAuthorization(
                                                            "TEST", "x".repeat(43), "CODE", null))
                                    .isInstanceOf(BizException.class);
                            sql.update(
                                    "UPDATE cim_operation SET expires_at=? WHERE operation_no=?",
                                    LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
                                    action.operationNo());
                            assertThatThrownBy(
                                            () ->
                                                    service.completeAuthorization(
                                                            "TEST",
                                                            gateway.context.state(),
                                                            "CODE",
                                                            null))
                                    .isInstanceOf(BizException.class);
                            assertThat(gateway.exchanges).hasValue(0);
                            assertThat(
                                            c.getBean(ChannelOperationService.class)
                                                    .getOperation("E", action.operationNo())
                                                    .authorizationUrl())
                                    .isNull();
                        });
    }

    @Test
    void lateCallbackCanConfirmOriginalUnknownBeginWithoutNewSend() {
        var gateway = new Gateway();
        gateway.timeout = true;
        runner().withBean(ChannelAuthorizationGateway.class, () -> gateway)
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
                                                    new CreateChannelAccountReq("PP", "REMOTE"),
                                                    "create");
                            var service = c.getBean(ChannelAuthorizationService.class);
                            var action =
                                    service.authorize(
                                            "E",
                                            account.accountNo(),
                                            "A",
                                            new AuthorizeChannelAccountReq(0L, "B_APP"),
                                            "key");
                            assertThat(
                                            service.completeAuthorization(
                                                            "TEST",
                                                            gateway.context.state(),
                                                            "CODE",
                                                            null)
                                                    .status())
                                    .isEqualTo("SUCCEEDED");
                            assertThat(gateway.starts).hasValue(1);
                            assertThat(
                                            c.getBean(ChannelAccountService.class)
                                                    .getAccount("E", account.accountNo())
                                                    .status())
                                    .isEqualTo("ACTIVE");
                            assertThatThrownBy(
                                            () ->
                                                    service.completeAuthorization(
                                                            "TEST",
                                                            gateway.context.state(),
                                                            "OTHER_CODE",
                                                            null))
                                    .isInstanceOf(BizException.class);
                            assertThatThrownBy(
                                            () ->
                                                    c.getBean(ChannelOperationService.class)
                                                            .getOperation(
                                                                    "OTHER", action.operationNo()))
                                    .isInstanceOf(BizException.class);
                            assertThatThrownBy(
                                            () ->
                                                    service.listRevisions(
                                                            "OTHER", account.accountNo(), 1, 20))
                                    .isInstanceOf(BizException.class);
                        });
    }

    @Test
    void unconfirmedRevocationBlocksAccountWithoutRewritingGrantHistory() {
        var gateway = new Gateway();
        gateway.revokeUnknown = true;
        runner().withBean(ChannelAuthorizationGateway.class, () -> gateway)
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            seed(sql);
                            var accounts = c.getBean(ChannelAccountService.class);
                            var account =
                                    accounts.createAccount(
                                            "E",
                                            "A",
                                            new CreateChannelAccountReq("PP", "REMOTE"),
                                            "create");
                            var service = c.getBean(ChannelAuthorizationService.class);
                            service.authorize(
                                    "E",
                                    account.accountNo(),
                                    "A",
                                    new AuthorizeChannelAccountReq(0L, "B_APP"),
                                    "key");
                            service.completeAuthorization(
                                    "TEST", gateway.context.state(), "CODE", null);
                            var active = accounts.getAccount("E", account.accountNo());
                            var req =
                                    new RevokeChannelAuthorizationReq(
                                            active.version(), "OWNER_REQUEST", "disconnect");
                            var action =
                                    service.revoke("E", account.accountNo(), "A", req, "revoke");
                            assertThat(service.revoke("E", account.accountNo(), "A", req, "revoke"))
                                    .isEqualTo(action);
                            assertThat(gateway.revokes).hasValue(1);
                            assertThat(accounts.getAccount("E", account.accountNo()).status())
                                    .isEqualTo("REVOKE_UNKNOWN");
                            assertThat(
                                            service.listRevisions("E", account.accountNo(), 1, 20)
                                                    .records()
                                                    .getFirst()
                                                    .current())
                                    .isFalse();
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT status FROM cim_authorization_revision",
                                                    String.class))
                                    .isEqualTo("GRANTED");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT reason_code FROM cim_operation WHERE"
                                                            + " operation_type='REVOKE'",
                                                    String.class))
                                    .isEqualTo("OWNER_REQUEST");
                        });
    }

    @Test
    void unregisteredReturnUrlCannotBePublished() {
        assertUnregisteredQueryBlocked("return_url=https%3A%2F%2Fevil.example");
    }

    @Test
    void unregisteredApiKeyCannotBePublished() {
        assertUnregisteredQueryBlocked("api_key=example-secret");
    }

    private void assertUnregisteredQueryBlocked(String query) {
        var gateway = new Gateway();
        gateway.extraQuery = query;
        runner().withBean(ChannelAuthorizationGateway.class, () -> gateway)
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
                                                    new CreateChannelAccountReq("PP", "REMOTE"),
                                                    "create");
                            var action =
                                    c.getBean(ChannelAuthorizationService.class)
                                            .authorize(
                                                    "E",
                                                    account.accountNo(),
                                                    "A",
                                                    new AuthorizeChannelAccountReq(0L, "B_APP"),
                                                    "key");
                            var operation =
                                    c.getBean(ChannelOperationService.class)
                                            .getOperation("E", action.operationNo());
                            assertThat(operation.status()).isEqualTo("UNKNOWN");
                            assertThat(operation.authorizationUrl()).isNull();
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT authorization_url FROM cim_operation",
                                                    String.class))
                                    .isNull();
                        });
    }

    @Test
    void retainedGrantCanBeRevokedAfterReauthorizationDenial() {
        assertRetainedGrantRevocable(false);
    }

    @Test
    void retainedGrantRevocationUnknownDoesNotRestoreActive() {
        assertRetainedGrantRevocable(true);
    }

    private void assertRetainedGrantRevocable(boolean unknown) {
        var gateway = new Gateway();
        gateway.revokeUnknown = unknown;
        runner().withBean(ChannelAuthorizationGateway.class, () -> gateway)
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            seed(sql);
                            var accounts = c.getBean(ChannelAccountService.class);
                            var account =
                                    accounts.createAccount(
                                            "E",
                                            "A",
                                            new CreateChannelAccountReq("PP", "REMOTE"),
                                            "create");
                            var service = c.getBean(ChannelAuthorizationService.class);
                            service.authorize(
                                    "E",
                                    account.accountNo(),
                                    "A",
                                    new AuthorizeChannelAccountReq(0L, "B_APP"),
                                    "first");
                            service.completeAuthorization(
                                    "TEST", gateway.context.state(), "CODE1", null);
                            var active = accounts.getAccount("E", account.accountNo());
                            service.authorize(
                                    "E",
                                    account.accountNo(),
                                    "A",
                                    new AuthorizeChannelAccountReq(active.version(), "B_APP"),
                                    "again");
                            assertThat(
                                            service.completeAuthorization(
                                                            "TEST",
                                                            gateway.context.state(),
                                                            null,
                                                            "access_denied")
                                                    .status())
                                    .isEqualTo("REJECTED");
                            var rejected = accounts.getAccount("E", account.accountNo());
                            assertThat(rejected.status()).isEqualTo("AUTH_REJECTED");
                            var action =
                                    service.revoke(
                                            "E",
                                            account.accountNo(),
                                            "A",
                                            new RevokeChannelAuthorizationReq(
                                                    rejected.version(),
                                                    "OWNER_REQUEST",
                                                    "disconnect old grant"),
                                            "revoke");
                            assertThat(gateway.revokes).hasValue(1);
                            assertThat(
                                            c.getBean(ChannelOperationService.class)
                                                    .getOperation("E", action.operationNo())
                                                    .status())
                                    .isEqualTo(unknown ? "UNKNOWN" : "SUCCEEDED");
                            assertThat(accounts.getAccount("E", account.accountNo()).status())
                                    .isEqualTo(unknown ? "REVOKE_UNKNOWN" : "REVOKED");
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM"
                                                            + " cim_authorization_revision",
                                                    Long.class))
                                    .isEqualTo(1);
                        });
    }

    @Test
    void readOnlyRecoveryFindsPersistedGrantAndRevocationWithoutRepeatingOriginalWrites() {
        var gateway = new Gateway();
        gateway.exchangeTimeout = true;
        gateway.recoveryKnown = true;
        runner().withBean(ChannelAuthorizationGateway.class, () -> gateway)
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            seed(sql);
                            var accounts = c.getBean(ChannelAccountService.class);
                            var service = c.getBean(ChannelAuthorizationServiceImpl.class);
                            var account =
                                    accounts.createAccount(
                                            "E",
                                            "A",
                                            new CreateChannelAccountReq("PP", "REMOTE"),
                                            "create");
                            var action =
                                    service.authorize(
                                            "E",
                                            account.accountNo(),
                                            "A",
                                            new AuthorizeChannelAccountReq(0L, "B_APP"),
                                            "authorize");
                            assertThat(
                                            service.completeAuthorization(
                                                            "TEST",
                                                            gateway.context.state(),
                                                            "CODE",
                                                            null)
                                                    .status())
                                    .isEqualTo("UNKNOWN");
                            sql.execute(
                                    "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                        + " 00:00:00'");
                            assertThat(service.recoverDue(10)).isOne();
                            assertThat(accounts.getAccount("E", account.accountNo()).status())
                                    .isEqualTo("ACTIVE");
                            assertThat(gateway.exchanges).hasValue(1);
                            gateway.revokeUnknown = true;
                            var active = accounts.getAccount("E", account.accountNo());
                            service.revoke(
                                    "E",
                                    account.accountNo(),
                                    "A",
                                    new RevokeChannelAuthorizationReq(
                                            active.version(), "OWNER_REQUEST", "stop"),
                                    "revoke");
                            sql.execute(
                                    "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                        + " 00:00:00' WHERE operation_type='REVOKE'");
                            assertThat(service.recoverDue(10)).isOne();
                            assertThat(accounts.getAccount("E", account.accountNo()).status())
                                    .isEqualTo("REVOKED");
                            assertThat(gateway.revokes).hasValue(1);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM cim_operation_attempt"
                                                        + " WHERE attempt_kind='QUERY'",
                                                    Integer.class))
                                    .isEqualTo(2);
                        });
    }

    @Test
    void originalStartQueryMustMatchSavedStateAndLocalExpiryAloneCannotClearUnknownGate() {
        var gateway = new Gateway();
        gateway.timeout = true;
        gateway.recoveryKnown = true;
        gateway.wrongRecoveryState = true;
        runner().withBean(ChannelAuthorizationGateway.class, () -> gateway)
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            seed(sql);
                            var accounts = c.getBean(ChannelAccountService.class);
                            var service = c.getBean(ChannelAuthorizationServiceImpl.class);
                            var account =
                                    accounts.createAccount(
                                            "E",
                                            "A",
                                            new CreateChannelAccountReq("PP", "REMOTE"),
                                            "create");
                            var action =
                                    service.authorize(
                                            "E",
                                            account.accountNo(),
                                            "A",
                                            new AuthorizeChannelAccountReq(0L, "B_APP"),
                                            "authorize");
                            sql.execute(
                                    "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                        + " 00:00:00'");
                            service.recoverDue(10);
                            assertThat(
                                            c.getBean(ChannelOperationService.class)
                                                    .getOperation("E", action.operationNo())
                                                    .status())
                                    .isEqualTo("UNKNOWN");
                            gateway.wrongRecoveryState = false;
                            sql.execute(
                                    "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                        + " 00:00:00'");
                            service.recoverDue(10);
                            assertThat(
                                            c.getBean(ChannelOperationService.class)
                                                    .getOperation("E", action.operationNo())
                                                    .authorizationUrl())
                                    .contains(gateway.context.state());
                            gateway.recoveryKnown = false;
                            sql.execute(
                                    "UPDATE cim_operation SET expires_at='2000-01-01"
                                        + " 00:00:00',next_recovery_at='2000-01-01 00:00:00'");
                            service.recoverDue(10);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT active_operation_no FROM cim_account"
                                                        + " WHERE account_no=?",
                                                    String.class,
                                                    account.accountNo()))
                                    .isEqualTo(action.operationNo());
                            assertThat(gateway.starts).hasValue(1);
                            assertThat(gateway.exchanges).hasValue(0);
                            gateway.recoveryTerminal = true;
                            sql.execute(
                                    "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                        + " 00:00:00'");
                            service.recoverDue(10);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT active_operation_no FROM cim_account"
                                                        + " WHERE account_no=?",
                                                    String.class,
                                                    account.accountNo()))
                                    .isNull();
                        });
    }

    @Test
    void callbackWinningDuringReadOnlyRecoveryCannotBeOverwrittenByLateUrl() {
        var gateway = new Gateway();
        gateway.timeout = true;
        gateway.recoveryKnown = true;
        runner().withBean(ChannelAuthorizationGateway.class, () -> gateway)
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            seed(sql);
                            var accounts = c.getBean(ChannelAccountService.class);
                            var service = c.getBean(ChannelAuthorizationServiceImpl.class);
                            var account =
                                    accounts.createAccount(
                                            "E",
                                            "A",
                                            new CreateChannelAccountReq("PP", "REMOTE"),
                                            "create");
                            var action =
                                    service.authorize(
                                            "E",
                                            account.accountNo(),
                                            "A",
                                            new AuthorizeChannelAccountReq(0L, "B_APP"),
                                            "authorize");
                            gateway.duringRecovery =
                                    () ->
                                            service.completeAuthorization(
                                                    "TEST", gateway.context.state(), "CODE", null);
                            sql.execute(
                                    "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                        + " 00:00:00'");
                            service.recoverDue(10);
                            assertThat(accounts.getAccount("E", account.accountNo()).status())
                                    .isEqualTo("ACTIVE");
                            assertThat(
                                            c.getBean(ChannelOperationService.class)
                                                    .getOperation("E", action.operationNo())
                                                    .status())
                                    .isEqualTo("SUCCEEDED");
                            assertThat(gateway.exchanges).hasValue(1);
                            assertThat(
                                            sql.queryForObject(
                                                    "SELECT COUNT(*) FROM cim_operation_attempt"
                                                        + " WHERE"
                                                        + " late_evidence_ref='ORIGINAL_START_QUERY'",
                                                    Integer.class))
                                    .isOne();
                        });
    }
}
