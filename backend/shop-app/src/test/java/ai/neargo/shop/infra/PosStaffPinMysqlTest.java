package ai.neargo.shop.infra;

import ai.neargo.shop.auth.PasswordHasher;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.otp.service.OtpSendService;
import ai.neargo.shop.merchant.service.MerchantAccountService;
import ai.neargo.shop.merchantbridge.impl.PosPinRecoveryService;
import ai.neargo.shop.user.service.AuthService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import ai.neargo.shop.merchant.service.impl.PosStaffPinServiceImpl;
import ai.neargo.shop.merchant.service.PosStaffPinService.Outcome;
import ai.neargo.shop.merchant.service.PosStaffPinService.Scope;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PosStaffPinMysqlTest {
    @Test
    void firstSetupChangeLockAndIsolation() throws Exception {
        String url = System.getenv("COMMERCE_MYSQL_URL");
        assumeTrue(url != null && url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/\\?.*"));
        String user = System.getenv("COMMERCE_MYSQL_USER");
        String password = System.getenv("COMMERCE_MYSQL_PASSWORD");
        String schema = "pos_pin_test_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        root.execute("CREATE DATABASE " + schema);
        try {
            var data = new DriverManagerDataSource(url.replace("/?", "/" + schema + "?"), user, password);
            JdbcTemplate db = new JdbcTemplate(data);
            db.execute(new ClassPathResource("db/migration/V698__pos_staff_pin.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            db.execute(new ClassPathResource("db/migration/V700__pos_staff_pin_policy.sql").getContentAsString(StandardCharsets.UTF_8));
            var tx = new TransactionTemplate(new DataSourceTransactionManager(data));
            var hasher = new PasswordHasher();
            var api = new PosStaffPinServiceImpl(db, hasher, Base64.getEncoder().encodeToString(new byte[32]));
            var off = new PosStaffPinServiceImpl(db, hasher, "");
            Scope staff = new Scope("MAIN", "E1", "S1", "MCH", "A1");
            Scope other = new Scope("MAIN", "E1", "S1", "MCH", "A2");
            Scope otherStore = new Scope("MAIN", "E1", "S2", "MCH", "A1");

            assertThat(off.status(staff).available()).isFalse();
            assertThat(api.status(staff).configured()).isFalse();
            assertThat(tx.execute(s -> api.verify(staff, "8642")).outcome()).isEqualTo(Outcome.DISABLED);
            tx.executeWithoutResult(s -> api.setEnabled(staff, true));
            assertThat(api.status(otherStore).enabled()).isTrue();
            assertThat(tx.execute(s -> api.save(staff, "1234", null)).outcome()).isEqualTo(Outcome.WEAK);
            assertThat(tx.execute(s -> api.save(staff, "8642", null)).outcome()).isEqualTo(Outcome.SAVED);
            assertThat(api.status(staff).configured()).isTrue();
            String stored = db.queryForObject("SELECT pin_hash FROM mch_pos_staff_pin", String.class);
            assertThat(stored).startsWith("$2");
            assertThat(hasher.matches("8642", stored)).isFalse();
            assertThat(tx.execute(s -> api.verify(other, "8642")).outcome()).isEqualTo(Outcome.NOT_SET);
            assertThat(tx.execute(s -> api.verify(otherStore, "8642")).outcome()).isEqualTo(Outcome.NOT_SET);

            assertThat(tx.execute(s -> api.save(staff, "7915", null)).outcome()).isEqualTo(Outcome.CURRENT_REQUIRED);
            assertThat(tx.execute(s -> api.save(staff, "7915", "8642")).outcome()).isEqualTo(Outcome.SAVED);
            assertThat(tx.execute(s -> api.verify(staff, "8642")).remaining()).isEqualTo(2);
            assertThat(tx.execute(s -> api.verify(staff, "8642")).remaining()).isEqualTo(1);
            assertThat(tx.execute(s -> api.verify(staff, "8642")).outcome()).isEqualTo(Outcome.LOCKED);
            assertThat(tx.execute(s -> api.verify(staff, "7915")).outcome()).isEqualTo(Outcome.LOCKED);
            db.update("UPDATE mch_pos_staff_pin SET locked_until_ms=?", System.currentTimeMillis() - 1);
            assertThat(tx.execute(s -> api.verify(staff, "7915")).outcome()).isEqualTo(Outcome.MATCH);
            assertThat(tx.execute(s -> api.verify(staff, "8642")).remaining()).isEqualTo(2);
            db.execute(new ClassPathResource("db/migration/V699__pos_staff_pin_recovery.sql").getContentAsString(StandardCharsets.UTF_8));
            LoginUser caller = mock(LoginUser.class);
            var accounts = mock(MerchantAccountService.class);
            var auth = mock(AuthService.class);
            var otp = mock(OtpSendService.class);
            when(accounts.account(caller)).thenReturn(new MerchantAccountService.Account(
                    "U1", "A1", "+886912345000", "synthetic@example.invalid", true, false, null, false, false, List.of()));
            when(auth.verify(any())).thenAnswer(invocation -> {
                AuthService.LoginCommand command = invocation.getArgument(0);
                return new AuthService.VerifiedPrincipal("PHONE", "+886912345000",
                        "test-secret".equals(command.credential()) ? "U1" : "U2");
            });
            when(otp.send(any(), any())).thenReturn(new OtpSendService.OtpSendResult(300, 60));
            when(otp.verifyAndConsume(any(), any())).thenAnswer(invocation -> {
                OtpSendService.VerifyCommand command = invocation.getArgument(0);
                return "654321".equals(command.code()) && "POS_PIN_RECOVERY".equals(command.scene());
            });
            var recovery = new PosPinRecoveryService(db, api, accounts, auth, otp);
            String session = "test-session";
            assertThat(recovery.methods(staff, caller)).containsExactly(PosPinRecoveryService.Method.PASSWORD,
                    PosPinRecoveryService.Method.PHONE_OTP, PosPinRecoveryService.Method.EMAIL_OTP);
            var passwordChallenge = tx.execute(t -> recovery.challenge(staff, caller, session,
                    PosPinRecoveryService.Method.PASSWORD, "127.0.0.1"));
            assertThat(recovery.verify(staff, caller, session, passwordChallenge.challengeId(), "wrong-secret", null)
                    .remaining()).isEqualTo(4);
            assertThat(recovery.verify(staff, caller, "different-session", passwordChallenge.challengeId(), "test-secret", null)
                    .outcome()).isEqualTo("EXPIRED");
            var proof = recovery.verify(staff, caller, session, passwordChallenge.challengeId(), "test-secret", null);
            assertThat(proof.outcome()).isEqualTo("VERIFIED");
            assertThat(tx.execute(t -> api.save(otherStore, "9516", null)).outcome()).isEqualTo(Outcome.SAVED);
            assertThat(tx.execute(t -> api.save(other, "9516", null)).outcome()).isEqualTo(Outcome.SAVED);
            assertThatThrownBy(() -> tx.execute(t -> recovery.reset(other, session, proof.resetToken(), "5816")))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> tx.execute(t -> recovery.reset(otherStore, session, proof.resetToken(), "5816")))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> tx.execute(t -> recovery.reset(staff, "different-session", proof.resetToken(), "5816")))
                    .isInstanceOf(BizException.class);
            assertThat(tx.execute(t -> recovery.reset(staff, session, proof.resetToken(), "5816")).outcome())
                    .isEqualTo(Outcome.SAVED);
            assertThat(tx.execute(t -> api.verify(staff, "5816")).outcome()).isEqualTo(Outcome.MATCH);
            assertThat(tx.execute(t -> api.verify(staff, "7915")).outcome()).isEqualTo(Outcome.WRONG);
            assertThatThrownBy(() -> tx.execute(t -> recovery.reset(staff, session, proof.resetToken(), "7931")))
                    .isInstanceOf(BizException.class);
            var emailChallenge = tx.execute(t -> recovery.challenge(staff, caller, session,
                    PosPinRecoveryService.Method.EMAIL_OTP, null));
            assertThat(emailChallenge.maskedRecipient()).endsWith("alid");
            var otpProof = recovery.verify(staff, caller, session, emailChallenge.challengeId(), "654321", null);
            assertThat(otpProof.outcome()).isEqualTo("VERIFIED");
            db.update("UPDATE mch_pos_pin_recovery SET reset_expires_at_ms=0 WHERE challenge_id=?", emailChallenge.challengeId());
            assertThatThrownBy(() -> tx.execute(t -> recovery.reset(staff, session, otpProof.resetToken(), "7931")))
                    .isInstanceOf(BizException.class);
            tx.executeWithoutResult(s -> api.setEnabled(staff, false));
            assertThat(api.status(otherStore).enabled()).isFalse();
            assertThat(tx.execute(t -> api.verify(staff, "5816")).outcome()).isEqualTo(Outcome.DISABLED);

        } finally {
            root.execute("DROP DATABASE " + schema);
        }
    }
}
