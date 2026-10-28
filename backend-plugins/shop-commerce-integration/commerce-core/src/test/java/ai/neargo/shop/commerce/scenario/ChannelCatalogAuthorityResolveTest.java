package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.IdentityQuery;
import ai.neargo.shop.commerce.policy.ChannelCatalogAuthorityPolicy;
import ai.neargo.shop.common.BizException;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import java.util.concurrent.atomic.AtomicInteger;

class ChannelCatalogAuthorityResolveTest {
    static final IdentityQuery QUERY = new IdentityQuery("E", "S", "TEST", "REMOTE_ACCOUNT", "REMOTE_STORE");
    final ChannelCatalogAuthorityServiceTest original = new ChannelCatalogAuthorityServiceTest();

    void duplicate(CatalogSynchronizationTest.State state) {
        state.sql().update("INSERT INTO cim_product_profile"
                + " (profile_no,provider_no,product_code,market,protocol_code,capability_definition,"
                + "identity_schema_version,identity_namespace,store_identity_scope,status)"
                + " VALUES('PP2','P','OTHER','TW','TEST','{}',1,'other:TW','GLOBAL','VERIFIED')");
        state.sql().update("INSERT INTO cim_account"
                + " (account_no,entity_no,profile_no,external_account_ref,active_authorization_no,status)"
                + " VALUES('ACC2','E','PP2','REMOTE_ACCOUNT','AUTH2','ACTIVE')");
        state.sql().update("INSERT INTO cim_authorization_revision"
                + " (authorization_no,entity_no,account_no,revision_no,credential_ref,granted_scopes,"
                + "capability_hash,authorized_at,expires_at,status,evidence_ref)"
                + " VALUES('AUTH2','E','ACC2',1,'vault:ref','[\"CATALOG\"]',?, ?, ?, 'GRANTED','SECOND')",
                "a".repeat(64), LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
                LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        state.sql().update("INSERT INTO cim_store_connection"
                + " (connection_no,entity_no,store_no,profile_no,account_no,external_store_ref,channel_store_key,"
                + "active_revision_no,status,identity_receipt_ref,identity_verified_at,identity_valid_until)"
                + " VALUES('CON2','E','S','PP2','ACC2','REMOTE_STORE',?,'CR2','ACTIVE','SECOND',?,?)",
                ChannelIdentity.storeKey("other:TW", false, "REMOTE_ACCOUNT", "REMOTE_STORE"),
                LocalDateTime.now(ZoneOffset.UTC), LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        state.sql().update("INSERT INTO cim_connection_revision"
                + " (revision_no,entity_no,connection_no,revision,source_owner,accept_mode,fulfillment_profile,"
                + "catalog_authority,config_json,config_hash)"
                + " VALUES('CR2','E','CON2',1,'FOOD','MANUAL','INSTANT','LOCAL','{}',?)", "f".repeat(64));
    }

    @Test
    void resolvesOnlyOneOriginalIdentityAndReturnsTheExistingCurrentGrant() {
        new CatalogSynchronizationTest().runWithoutPublishing(state -> {
            original.originalStoreKey(state);
            var facts = original.service(state, original::proof).resolve(QUERY);
            ChannelCatalogAuthorityPolicy.validateResolved(QUERY, facts, Instant.now());
            assertThat(facts.query()).isEqualTo(ChannelCatalogAuthorityServiceTest.QUERY);
            assertThat(facts.account().accountNo()).isEqualTo("ACC");
            assertThat(facts.grant().capability()).isEqualTo("CATALOG");
        });
    }

    @Test
    void rejectsUnknownForeignInternalNumberAndCaseChangedIdentitiesWithoutVerifierCalls() {
        new CatalogSynchronizationTest().runWithoutPublishing(state -> {
            original.originalStoreKey(state);
            var calls = new AtomicInteger();
            var source = original.service(state, identity -> {
                calls.incrementAndGet();
                return original.proof(identity);
            });
            for (var q : List.of(new IdentityQuery("OTHER", "S", "TEST", "REMOTE_ACCOUNT", "REMOTE_STORE"),
                    new IdentityQuery("E", "OTHER", "TEST", "REMOTE_ACCOUNT", "REMOTE_STORE"),
                    new IdentityQuery("E", "S", "OTHER", "REMOTE_ACCOUNT", "REMOTE_STORE"),
                    new IdentityQuery("E", "S", "TEST", "ACC", "REMOTE_STORE"),
                    new IdentityQuery("E", "S", "test", "REMOTE_ACCOUNT", "REMOTE_STORE"))) {
                assertThatThrownBy(() -> source.resolve(q)).isInstanceOf(BizException.class);
            }
            assertThat(calls).hasValue(0);
        });
    }

    @Test
    void twoMatchingProfilesCannotChooseAnArbitraryConnection() {
        new CatalogSynchronizationTest().runWithoutPublishing(state -> {
            original.originalStoreKey(state);
            duplicate(state);
            var calls = new AtomicInteger();
            assertThatThrownBy(() -> original.service(state, i -> {
                calls.incrementAndGet(); return original.proof(i);
            }).resolve(QUERY)).isInstanceOf(BizException.class);
            assertThat(calls).hasValue(0);
        });
    }

    @Test
    void aNewMatchingIdentityDuringVerificationInvalidatesResolution() {
        new CatalogSynchronizationTest().runWithoutPublishing(state -> {
            original.originalStoreKey(state);
            assertThatThrownBy(() -> original.service(state, i -> {
                duplicate(state); return original.proof(i);
            }).resolve(QUERY)).isInstanceOf(BizException.class);
        });
    }

    @Test
    void disabledAndWrongOwnerConnectionsNeverResolve() {
        for (String mutation : List.of("UPDATE cim_store_connection SET status='DISABLED'",
                "UPDATE cim_connection_revision SET source_owner='RETAIL'",
                "UPDATE cim_connection_revision SET fulfillment_profile='PARCEL'")) {
            new CatalogSynchronizationTest().runWithoutPublishing(state -> {
                original.originalStoreKey(state);
                state.sql().update(mutation);
                assertThatThrownBy(() -> original.service(state, original::proof).resolve(QUERY))
                        .isInstanceOf(BizException.class);
            });
        }
    }
}
