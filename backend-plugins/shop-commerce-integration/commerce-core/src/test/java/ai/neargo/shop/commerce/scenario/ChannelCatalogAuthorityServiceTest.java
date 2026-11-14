package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Query;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProviderMapper;
import ai.neargo.shop.commerce.policy.ChannelCatalogAuthorityPolicy;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.service.impl.CatalogPersistence;
import ai.neargo.shop.commerce.service.impl.ChannelCatalogAuthorityServiceImpl;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class ChannelCatalogAuthorityServiceTest {
    static final Query QUERY = new Query("E", "S", "CON", "CR");

    ChannelCatalogAuthorityServiceImpl service(
            CatalogSynchronizationTest.State state, ChannelConnectionIdentityPort verifier) {
        var c = state.context();
        return new ChannelCatalogAuthorityServiceImpl(
                c.getBean(CatalogPersistence.class),
                c.getBean(ProviderMapper.class),
                () -> verifier,
                c.getBean("transactionManager", PlatformTransactionManager.class),
                Clock.systemUTC());
    }

    void originalStoreKey(CatalogSynchronizationTest.State state) {
        state.sql().update("UPDATE cim_connection_revision SET config_json=\'{}\' FORMAT JSON");
        state.sql()
                .update(
                        "UPDATE cim_store_connection SET channel_store_key=? WHERE"
                            + " connection_no='CON'",
                        ChannelIdentity.storeKey(
                                "test:TW", false, "REMOTE_ACCOUNT", "REMOTE_STORE"));
    }

    @Test
    void currentOriginalCatalogGrantIsCapturedWithoutCredentialOrPublisher() {
        new CatalogSynchronizationTest()
                .runWithoutPublishing(
                        state -> {
                            originalStoreKey(state);
                            var source =
                                    service(
                                            state,
                                            identity -> {
                                                assertThat(
                                                                TransactionSynchronizationManager
                                                                        .isActualTransactionActive())
                                                        .isFalse();
                                                return new ChannelConnectionIdentityPort.Proof(
                                                        ChannelConnectionIdentityPort.Status
                                                                .VERIFIED,
                                                        identity,
                                                        "REMOTE_PROOF",
                                                        Instant.now(),
                                                        Instant.now().plusSeconds(60));
                                            });
                            var facts = source.capture(QUERY);
                            assertThat(facts.provider().providerCode()).isEqualTo("TEST");
                            assertThat(facts.profile().market()).isEqualTo("TW");
                            assertThat(facts.connection().sourceOwner()).isEqualTo("FOOD");
                            var json =
                                    JsonMapper.builder()
                                            .findAndAddModules()
                                            .build()
                                            .writeValueAsString(facts);
                            assertThat(json)
                                    .doesNotContain("credentialRef", "vault:ref", "grantedScopes");
                        });
    }

    ChannelConnectionIdentityPort.Proof proof(ChannelConnectionIdentityPort.Identity identity) {
        return new ChannelConnectionIdentityPort.Proof(
                ChannelConnectionIdentityPort.Status.VERIFIED,
                identity,
                "FRESH",
                Instant.now(),
                Instant.now().plusSeconds(20));
    }

    @Test
    void invalidOriginalStateNeverCallsVerifier() {
        var mutations =
                List.of(
                        "UPDATE cim_provider SET status='DISABLED'",
                        "UPDATE cim_product_profile SET status='DRAFT'",
                        "UPDATE cim_account SET status='DISABLED'",
                        "UPDATE cim_store_connection SET status='DISABLED'",
                        "UPDATE cim_authorization_revision SET status='REVOKED'",
                        "UPDATE cim_authorization_revision SET granted_scopes='[\"ORDER\"]'",
                        "UPDATE cim_authorization_revision SET credential_ref=''",
                        "UPDATE cim_authorization_revision SET expires_at='2000-01-01 00:00:00'",
                        "UPDATE cim_account SET profile_no='OTHER'",
                        "UPDATE cim_store_connection SET channel_store_key='WRONG'",
                        "UPDATE cim_connection_revision SET catalog_authority='REMOTE'",
                        "UPDATE cim_product_profile SET market='unknown'",
                        "UPDATE cim_product_profile SET version=9007199254740992");
        for (var mutation : mutations)
            new CatalogSynchronizationTest()
                    .runWithoutPublishing(
                            state -> {
                                originalStoreKey(state);
                                state.sql().update(mutation);
                                var calls = new AtomicInteger();
                                assertThatThrownBy(
                                                () ->
                                                        service(
                                                                        state,
                                                                        i -> {
                                                                            calls.incrementAndGet();
                                                                            return proof(i);
                                                                        })
                                                                .capture(QUERY))
                                        .as(mutation)
                                        .isInstanceOf(BizException.class);
                                assertThat(calls).hasValue(0);
                            });
    }

    @Test
    void exactScopeRevisionAndMissingVerifierFailClosed() {
        new CatalogSynchronizationTest()
                .runWithoutPublishing(
                        state -> {
                            originalStoreKey(state);
                            for (var q :
                                    List.of(
                                            new Query("OTHER", "S", "CON", "CR"),
                                            new Query("E", "OTHER", "CON", "CR"),
                                            new Query("E", "S", "CON", "OLD"))) {
                                assertThatThrownBy(() -> service(state, this::proof).capture(q))
                                        .isInstanceOf(BizException.class);
                            }
                            assertThatThrownBy(() -> service(state, null).capture(QUERY))
                                    .isInstanceOf(BizException.class);
                            var tx =
                                    new TransactionTemplate(
                                            state.context()
                                                    .getBean(
                                                            "transactionManager",
                                                            PlatformTransactionManager.class));
                            tx.executeWithoutResult(
                                    status ->
                                            assertThatThrownBy(
                                                            () ->
                                                                    service(state, this::proof)
                                                                            .capture(QUERY))
                                                    .isInstanceOf(BizException.class));
                        });
    }

    @Test
    void freshProofAndEveryComparedOriginalChangeAreRequired() {
        for (var mutation :
                List.of(
                        "UPDATE cim_account SET version=version+1",
                        "UPDATE cim_authorization_revision SET credential_ref='vault:changed'",
                        "UPDATE cim_connection_revision SET config_json='{\"changed\":true}'",
                        "UPDATE cim_authorization_revision SET"
                            + " granted_scopes='[\"CATALOG\",\"ORDER\"]'",
                        "UPDATE cim_connection_revision SET source_owner='RETAIL'")) {
            new CatalogSynchronizationTest()
                    .runWithoutPublishing(
                            state -> {
                                originalStoreKey(state);
                                assertThatThrownBy(
                                                () ->
                                                        service(
                                                                        state,
                                                                        i -> {
                                                                            state.sql()
                                                                                    .update(
                                                                                            mutation);
                                                                            return proof(i);
                                                                        })
                                                                .capture(QUERY))
                                        .as(mutation)
                                        .isInstanceOf(BizException.class);
                            });
        }
        new CatalogSynchronizationTest()
                .runWithoutPublishing(
                        state -> {
                            originalStoreKey(state);
                            for (var status :
                                    List.of(
                                            ChannelConnectionIdentityPort.Status.UNKNOWN,
                                            ChannelConnectionIdentityPort.Status.UNSUPPORTED)) {
                                assertThatThrownBy(
                                                () ->
                                                        service(
                                                                        state,
                                                                        i ->
                                                                                new ChannelConnectionIdentityPort
                                                                                        .Proof(
                                                                                        status,
                                                                                        i,
                                                                                        "R",
                                                                                        Instant
                                                                                                .now(),
                                                                                        Instant
                                                                                                .now()
                                                                                                .plusSeconds(
                                                                                                        60)))
                                                                .capture(QUERY))
                                        .isInstanceOf(BizException.class);
                            }
                            assertThatThrownBy(
                                            () ->
                                                    service(
                                                                    state,
                                                                    i ->
                                                                            new ChannelConnectionIdentityPort
                                                                                    .Proof(
                                                                                    ChannelConnectionIdentityPort
                                                                                            .Status
                                                                                            .VERIFIED,
                                                                                    i,
                                                                                    "R",
                                                                                    Instant.now()
                                                                                            .minusSeconds(
                                                                                                    60),
                                                                                    Instant.now()
                                                                                            .minusSeconds(
                                                                                                    1)))
                                                            .capture(QUERY))
                                    .isInstanceOf(BizException.class);
                            assertThatThrownBy(
                                            () ->
                                                    service(
                                                                    state,
                                                                    i ->
                                                                            new ChannelConnectionIdentityPort
                                                                                    .Proof(
                                                                                    ChannelConnectionIdentityPort
                                                                                            .Status
                                                                                            .VERIFIED,
                                                                                    i,
                                                                                    "R",
                                                                                    Instant.now()
                                                                                            .plusSeconds(
                                                                                                    60),
                                                                                    Instant.now()
                                                                                            .plusSeconds(
                                                                                                    90)))
                                                            .capture(QUERY))
                                    .isInstanceOf(BizException.class);
                            assertThatThrownBy(
                                            () ->
                                                    service(
                                                                    state,
                                                                    i ->
                                                                            new ChannelConnectionIdentityPort
                                                                                    .Proof(
                                                                                    ChannelConnectionIdentityPort
                                                                                            .Status
                                                                                            .VERIFIED,
                                                                                    null,
                                                                                    "R",
                                                                                    Instant.now(),
                                                                                    Instant.now()
                                                                                            .plusSeconds(
                                                                                                    60)))
                                                            .capture(QUERY))
                                    .isInstanceOf(BizException.class);
                        });
    }

    @Test
    void evidenceExpiresWithOriginalProofAndValidationRejectsTampering() {
        new CatalogSynchronizationTest()
                .runWithoutPublishing(
                        state -> {
                            originalStoreKey(state);
                            var facts = service(state, this::proof).capture(QUERY);
                            ChannelCatalogAuthorityPolicy.validate(QUERY, facts, Instant.now());
                            assertThat(facts.validUntil()).isEqualTo(facts.identity().validUntil());
                            assertThatThrownBy(
                                            () ->
                                                    ChannelCatalogAuthorityPolicy.validate(
                                                            QUERY, facts, facts.validUntil()))
                                    .isInstanceOf(IllegalStateException.class);
                            assertThatThrownBy(
                                            () ->
                                                    ChannelCatalogAuthorityPolicy.validate(
                                                            new Query("E", "OTHER", "CON", "CR"),
                                                            facts,
                                                            Instant.now()))
                                    .isInstanceOf(IllegalStateException.class);
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT count(*) FROM"
                                                                + " cim_catalog_scope",
                                                            Integer.class))
                                    .isZero();
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT count(*) FROM cim_operation",
                                                            Integer.class))
                                    .isZero();
                        });
    }
}
