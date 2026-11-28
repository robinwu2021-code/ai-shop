package ai.neargo.shop.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.otp.service.OtpSendService;
import ai.neargo.shop.merchant.service.MerchantAccountService;
import ai.neargo.shop.merchant.service.PosStaffPinService;
import ai.neargo.shop.merchant.service.PosStaffPinService.Outcome;
import ai.neargo.shop.merchant.service.PosStaffPinService.Result;
import ai.neargo.shop.merchant.service.PosStaffPinService.Scope;
import ai.neargo.shop.merchantbridge.impl.PosPinRecoveryService;
import ai.neargo.shop.merchantbridge.impl.PosPinRecoveryService.Method;
import ai.neargo.shop.user.service.AuthService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 收银员 PIN 找回落在一次性票据 {@code auth_ticket} 上（平台基础库换表 I，原 {@code mch_pos_pin_recovery}）：
 * 挑战与重置各一行，表结构取平台测试建表脚本（新库设计）。
 */
class PosPinRecoveryTicketTest {
    private final Scope staff = new Scope("MAIN", "E1", "S1", "MCH", "A1");
    private final Scope other = new Scope("MAIN", "E1", "S1", "MCH", "A2");
    private final Scope otherStore = new Scope("MAIN", "E1", "S2", "MCH", "A1");
    private final String session = "test-session";
    private JdbcTemplate db;
    private PosPinRecoveryService recovery;
    private LoginUser caller;

    @BeforeEach
    void setup() throws Exception {
        db = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:pin-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        var ddl = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS auth_ticket \\(.*?\n\\);")
                .matcher(Files.readString(Path.of("src/test/resources/schema-platform-test.sql")));
        assertThat(ddl.find()).as("平台测试建表脚本里没有 auth_ticket").isTrue();
        db.execute(ddl.group());
        db.execute("CREATE TABLE mch_pos_staff_pin(tenant_no varchar(32),entity_no varchar(32),store_no varchar(32),"
                + "subject_kind varchar(16),actor_no varchar(64))");
        for (Scope scope : List.of(staff, other, otherStore)) {
            db.update("INSERT INTO mch_pos_staff_pin VALUES(?,?,?,?,?)", scope.tenant(), scope.entity(), scope.store(),
                    scope.subjectKind(), scope.actor());
        }
        var pins = mock(PosStaffPinService.class);
        when(pins.status(any())).thenReturn(new PosStaffPinService.Status(true, true, true));
        when(pins.enabled(any())).thenReturn(true);
        when(pins.reset(any(), any())).thenReturn(Result.of(Outcome.SAVED));
        caller = mock(LoginUser.class);
        var accounts = mock(MerchantAccountService.class);
        when(accounts.account(caller)).thenReturn(new MerchantAccountService.Account(
                "U1", "A1", "+886912345000", "synthetic@example.invalid", true, false, null, false, false, List.of()));
        var auth = mock(AuthService.class);
        when(auth.verify(any())).thenAnswer(invocation -> {
            AuthService.LoginCommand command = invocation.getArgument(0);
            return new AuthService.VerifiedPrincipal("PHONE", "+886912345000",
                    "test-secret".equals(command.credential()) ? "U1" : "U2");
        });
        var otp = mock(OtpSendService.class);
        when(otp.send(any(), any())).thenReturn(new OtpSendService.OtpSendResult(300, 60));
        when(otp.verifyAndConsume(any(), any())).thenAnswer(invocation -> {
            OtpSendService.VerifyCommand command = invocation.getArgument(0);
            return "654321".equals(command.code()) && "POS_PIN_RECOVERY".equals(command.scene());
        });
        recovery = new PosPinRecoveryService(db, pins, accounts, auth, otp);
    }

    @Test
    void passwordChallengeIssuesAOneShotResetTicketBoundToSessionAndStaff() {
        assertThat(recovery.methods(staff, caller)).containsExactly(Method.PASSWORD, Method.PHONE_OTP, Method.EMAIL_OTP);
        var challenge = recovery.challenge(staff, caller, session, Method.PASSWORD, "127.0.0.1");
        assertThat(recovery.challenge(staff, caller, session, Method.PASSWORD, "127.0.0.1").challengeId())
                .as("同一会话重复要挑战拿回同一个号").isEqualTo(challenge.challengeId());
        assertThatThrownBy(() -> recovery.challenge(staff, caller, "different-session", Method.PASSWORD, null))
                .isInstanceOf(BizException.class).extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM auth_ticket WHERE purpose='PIN_CHALLENGE'", Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT ticket_hash FROM auth_ticket", String.class))
                .as("只存哈希").isNotEqualTo(challenge.challengeId()).hasSize(64);

        assertThat(recovery.verify(staff, caller, session, challenge.challengeId(), "wrong-secret", null).remaining()).isEqualTo(4);
        assertThat(recovery.verify(staff, caller, "different-session", challenge.challengeId(), "test-secret", null).outcome())
                .isEqualTo("EXPIRED");
        var proof = recovery.verify(staff, caller, session, challenge.challengeId(), "test-secret", null);
        assertThat(proof.outcome()).isEqualTo("VERIFIED");
        assertThat(recovery.verify(staff, caller, session, challenge.challengeId(), "test-secret", null).outcome())
                .as("挑战验过即作废").isEqualTo("EXPIRED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM auth_ticket WHERE purpose='PIN_RESET' AND source_ticket_hash IS NOT NULL",
                Long.class)).isEqualTo(1);

        assertThatThrownBy(() -> recovery.reset(other, session, proof.resetToken(), "5816")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> recovery.reset(otherStore, session, proof.resetToken(), "5816")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> recovery.reset(staff, "different-session", proof.resetToken(), "5816")).isInstanceOf(BizException.class);
        assertThat(recovery.reset(staff, session, proof.resetToken(), "5816").outcome()).isEqualTo(Outcome.SAVED);
        assertThatThrownBy(() -> recovery.reset(staff, session, proof.resetToken(), "7931"))
                .as("重置票用一次即作废").isInstanceOf(BizException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM auth_ticket WHERE consumed_at IS NULL", Long.class))
                .as("改成功后这名员工所有未用的票一并作废").isZero();
    }

    @Test
    void attemptsAreCappedPerChallengeAndAnExpiredResetTicketIsRefused() {
        var challenge = recovery.challenge(staff, caller, session, Method.PASSWORD, null);
        for (int i = 4; i >= 0; i--) {
            assertThat(recovery.verify(staff, caller, session, challenge.challengeId(), "wrong-secret", null).remaining()).isEqualTo(i);
        }
        assertThat(recovery.verify(staff, caller, session, challenge.challengeId(), "test-secret", null).outcome())
                .as("5 次用完").isEqualTo("EXPIRED");

        var email = recovery.challenge(staff, caller, session, Method.EMAIL_OTP, null);
        assertThat(email.maskedRecipient()).endsWith("alid");
        assertThat(email.resendAfterSeconds()).isEqualTo(60);
        var proof = recovery.verify(staff, caller, session, email.challengeId(), "654321", null);
        assertThat(proof.outcome()).isEqualTo("VERIFIED");
        db.update("UPDATE auth_ticket SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE purpose='PIN_RESET'");
        assertThatThrownBy(() -> recovery.reset(staff, session, proof.resetToken(), "7931")).isInstanceOf(BizException.class);
    }
}
