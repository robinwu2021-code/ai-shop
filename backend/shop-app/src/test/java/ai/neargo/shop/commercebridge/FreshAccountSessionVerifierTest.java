package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.auth.store.*;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.auth.store.DbAccountSessionVerifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;

@EnabledIfEnvironmentVariable(
        named = "COMMERCE_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
class FreshAccountSessionVerifierTest {
    @Test
    void oneConnectionBusinessPoolCannotStarveFreshSessionAndNestedMemberReads() throws Exception {
        var pools = new java.util.ArrayList<com.zaxxer.hikari.HikariDataSource>();
        javax.sql.DataSource[] raw = new javax.sql.DataSource[1];
        try (var f = new AccessMysqlFixture(Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC), source -> {
            raw[0] = source;
            for (int i = 0; i < 2; i++) {
                var pool = new com.zaxxer.hikari.HikariDataSource();
                pool.setDataSource(source);
                pool.setMaximumPoolSize(1);
                pool.setMinimumIdle(0);
                pool.setConnectionTimeout(250);
                pools.add(pool);
            }
            return new ai.neargo.shop.auth.store.FreshAccountReadDataSource(pools.get(0), pools.get(1));
        })) {
            var verifier = new DbAccountSessionVerifier(f.dao, f.loader, f.clock, f.manager);
            var token = f.store.issue(TokenStore.SessionData.of(LoginUser.merchant("MA", "staff")));
            var outer = new TransactionTemplate(f.manager);
            outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            outer.executeWithoutResult(tx -> {
                assertEquals("merchant", f.jdbc.queryForObject("SELECT name FROM mch_entity WHERE entity_no='M'", String.class));
                new org.springframework.jdbc.core.JdbcTemplate(raw[0]).update("UPDATE mch_entity SET name='fresh-governance' WHERE entity_no='M'");
                assertEquals("merchant", f.jdbc.queryForObject("SELECT name FROM mch_entity WHERE entity_no='M'", String.class));
                assertEquals("fresh-governance", verifier.withFresh(token, f.clock.instant(), session -> {
                    assertEquals("MA", session.user().userNo());
                    assertTrue(verifier.verifyFresh(token, f.clock.instant()).isPresent());
                    return f.jdbc.queryForObject("SELECT name FROM mch_entity WHERE entity_no='M'", String.class);
                }).orElseThrow());
                assertEquals("merchant", f.jdbc.queryForObject("SELECT name FROM mch_entity WHERE entity_no='M'", String.class));
            });
            try (var occupied = pools.get(1).getConnection()) {
                assertTimeout(Duration.ofSeconds(2), () -> assertThrows(
                        ai.neargo.shop.auth.store.FreshAccountReadUnavailable.class,
                        () -> verifier.verifyFresh(token, f.clock.instant())));
                assertEquals(1, f.jdbc.queryForObject("SELECT 1", Integer.class));
            }
            assertTrue(verifier.verifyFresh(token, f.clock.instant()).isPresent());
        } finally { pools.forEach(com.zaxxer.hikari.HikariDataSource::close); }
    }

    @Test
    void verifiesDatabaseRevocationAndDisabledIdentityDespiteCacheAndRepeatableReadSnapshot()
            throws Exception {
        try (var f = new AccessMysqlFixture()) {
            var verifier = new DbAccountSessionVerifier(f.dao, f.loader, f.clock, f.manager);
            var now = f.clock.instant();
            var token = f.store.issue(TokenStore.SessionData.of(LoginUser.merchant("MA", "staff")));
            var verified = verifier.verifyFresh(token, now).orElseThrow();
            assertEquals(now.plus(Duration.ofDays(30)), verified.expiresAt());
            assertEquals(TokenHash.of(token), verified.sessionFingerprint());
            assertEquals(now, verified.verifiedAt());
            var owner =
                    f.store.issue(
                            TokenStore.SessionData.of(LoginUser.merchantByUser("U", "owner")));
            assertEquals(
                    Realm.MERCHANT, verifier.verifyFresh(owner, now).orElseThrow().user().realm());
            var outer = new TransactionTemplate(f.manager);
            outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var change = new TransactionTemplate(f.manager);
            change.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            outer.executeWithoutResult(
                    tx -> {
                        assertTrue(
                                f.dao.findByHash(TokenHash.of(token))
                                        .orElseThrow()
                                        .isLive(LocalDateTime.now(f.clock)));
                        assertTrue(f.loader.load("U", SubjectKind.USR).isPresent());
                        change.executeWithoutResult(
                                t -> {
                                    f.dao.revoke(
                                            TokenHash.of(token),
                                            RevokeReason.LOGOUT,
                                            LocalDateTime.now(f.clock));
                                    f.jdbc.update(
                                            "UPDATE usr_account SET status='BANNED' WHERE"
                                                    + " user_no='U'");
                                });
                        assertTrue(f.store.get(token).isPresent());
                        assertTrue(f.store.get(owner).isPresent());
                        assertTrue(
                                f.dao.findByHash(TokenHash.of(token))
                                        .orElseThrow()
                                        .isLive(LocalDateTime.now(f.clock)));
                        assertTrue(f.loader.load("U", SubjectKind.USR).isPresent());
                        assertTrue(verifier.verifyFresh(token, now).isEmpty());
                        assertTrue(verifier.verifyFresh(owner, now).isEmpty());
                    });
        }
    }
}
