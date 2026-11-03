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
