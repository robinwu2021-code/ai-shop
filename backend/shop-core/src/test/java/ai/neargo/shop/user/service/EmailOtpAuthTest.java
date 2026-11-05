package ai.neargo.shop.user.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.auth.PasswordHasher;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.common.ratelimit.InMemoryRateLimiter;
import ai.neargo.shop.common.ratelimit.OtpSendGuard;
import ai.neargo.shop.spi.notify.MailPort;
import ai.neargo.shop.spi.notify.SendResult;
import ai.neargo.shop.spi.notify.SmsPort;
import ai.neargo.shop.spi.user.WxAuthPort;
import ai.neargo.shop.spi.user.WxPhonePort;
import ai.neargo.shop.spi.marketing.FissionPort;
import ai.neargo.shop.user.IdentityType;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.entity.UsrIdentity;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import ai.neargo.shop.user.mapper.UserMappers.IdentityMapper;
import ai.neargo.shop.user.service.impl.AuthServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TDD-B端邮箱验证码注册登录 AC1–AC11。移植自 codex/ai-shop-codex 的 stash@{0}，
 * 去掉依赖账号市场字段的两条，拆分「桩一律拒绝」那条（本地需要可测，见 TDD §6 偏差）。
 */
class EmailOtpAuthTest {
    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter();
    private final OtpStore store = new OtpStore(limiter);
    private final MailPort mail = mock(MailPort.class);
    private final OtpSendGuard guard = mock(OtpSendGuard.class);
    private final EmailOtpService email = new ai.neargo.shop.user.service.impl.EmailOtpServiceImpl(mail, store, guard, false, false, 60);
    private final UserMapper users = mock(UserMapper.class);
    private final IdentityMapper identities = mock(IdentityMapper.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final TokenStore tokens = new MemoryTokenStore(Duration.ofHours(1));
    private final Map<String, UsrAccount> accounts = new HashMap<>();
    private final Map<String, UsrIdentity> credentials = new HashMap<>();
    private AuthServiceImpl auth;

    @BeforeEach
    void setUp() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "email-auth-test");
        TableInfoHelper.initTableInfo(assistant, UsrIdentity.class);
        TableInfoHelper.initTableInfo(assistant, UsrAccount.class);
        when(mail.send(anyString(), anyString(), anyString(), eq("OTP"), isNull())).thenReturn(SendResult.none());
        when(users.insert(any(UsrAccount.class))).thenAnswer(inv -> {
            UsrAccount account = inv.getArgument(0);
            accounts.put(account.getUserNo(), account);
            return 1;
        });
        when(identities.insert(any(UsrIdentity.class))).thenAnswer(inv -> {
            UsrIdentity identity = inv.getArgument(0);
            credentials.put(identity.getIdentityType() + ":" + identity.getIdentityValue(), identity);
            return 1;
        });
        when(users.selectOne(any())).thenAnswer(inv -> {
            AbstractWrapper<?, ?, ?> query = inv.getArgument(0);
            query.getSqlSegment();
            return query.getParamNameValuePairs().values().stream().map(accounts::get)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        });
        when(identities.selectOne(any())).thenAnswer(inv -> {
            AbstractWrapper<?, ?, ?> query = inv.getArgument(0);
            query.getSqlSegment();
            var values = query.getParamNameValuePairs().values();
            return credentials.values().stream().filter(i -> values.contains(i.getIdentityType())
                    && (values.contains(i.getIdentityValue()) || values.contains(i.getUserNo())))
                    .findFirst().orElse(null);
        });
        auth = new AuthServiceImpl(users, identities, mock(WxPhonePort.class), mock(MpSessionService.class),
                tokens, store, guard, mock(SmsPort.class), mock(WxAuthPort.class), hasher, limiter,
                mock(PersonService.class), "", mock(FissionPort.class), mock(OtpTestPhoneService.class), email);
    }

    private String send() {
        email.send("Person@Example.COM", null);
        return store.peek(EmailOtpService.key("person@example.com")).orElseThrow();
    }

    @Test
    void sendsThroughExistingMailAndReturnsRealExpiryAndResendMetadata() {
        var result = email.send("Person@Example.COM", null);
        assertThat(result.expiresInSeconds()).isEqualTo(OtpStore.TTL.toSeconds());
        assertThat(result.resendAfterSeconds()).isEqualTo(60);
        String code = store.peek(EmailOtpService.key("person@example.com")).orElseThrow();
        assertThat(code).matches("[0-9]{6}");
        verify(mail).send(eq("person@example.com"), anyString(), contains(code), eq("OTP"), isNull());
        verify(guard).check(argThat(key -> key.startsWith("email:") && !key.contains("person")));
    }

    @Test
    void stubIsRefusedUnlessExplicitlyAllowed() {
        // 生产与本地跑同一组 profile，分不开；所以「走桩」必须显式放行，默认当作没有邮件通道
        assertThatThrownBy(() -> new ai.neargo.shop.user.service.impl.EmailOtpServiceImpl(mail, store, guard, true, false, 60).send("person@example.com", null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE));
        verifyNoInteractions(mail, guard);
        assertThat(store.peek(EmailOtpService.key("person@example.com"))).isEmpty();
    }

    @Test
    void deliveryFailureNeverStoresValidOtp() {
        when(mail.send(anyString(), anyString(), anyString(), eq("OTP"), isNull()))
                .thenThrow(new MailPort.MailException("recipient person@example.com rejected"));
        assertThatThrownBy(() -> email.send("person@example.com", null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE))
                .hasNoCause();
        assertThat(store.peek(EmailOtpService.key("person@example.com"))).isEmpty();
    }

    @Test
    void allowedStubLogsCodeAtDebugWithMaskedAddress() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ai.neargo.shop.user.service.impl.EmailOtpServiceImpl.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        var level = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        logger.addAppender(appender);
        try {
            new ai.neargo.shop.user.service.impl.EmailOtpServiceImpl(mail, store, guard, true, true, 60).send("Person@Example.com", null);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(level);
        }
        String code = store.peek(EmailOtpService.key("person@example.com")).orElseThrow();
        assertThat(appender.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.DEBUG);
            assertThat(e.getFormattedMessage()).contains(code).doesNotContain("person@example.com");
        });
    }

    @Test
    void bodyFollowsRequestedScriptAndDefaultsToTraditional() {
        email.send("hant@example.com", null);
        verify(mail).send(eq("hant@example.com"), contains("登入驗證碼"), contains("分鐘"), eq("OTP"), isNull());
        email.send("hans@example.com", "zh-Hans");
        verify(mail).send(eq("hans@example.com"), contains("登录验证码"), contains("分钟"), eq("OTP"), isNull());
        email.send("cn@example.com", "zh-CN");
        verify(mail).send(eq("cn@example.com"), contains("登录验证码"), anyString(), eq("OTP"), isNull());
    }

    @Test
    void resendIsRateLimitedBeforeCodeReplacement() {
        var realGuard = new OtpSendGuard(limiter, true, 60, 10, 20, 15);
        var service = new ai.neargo.shop.user.service.impl.EmailOtpServiceImpl(mail, store, realGuard, false, false, 60);
        service.send("person@example.com", null);
        String original = store.peek(EmailOtpService.key("person@example.com")).orElseThrow();
        assertThatThrownBy(() -> service.send("PERSON@example.com", null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.OTP_TOO_FREQUENT));
        assertThat(store.peek(EmailOtpService.key("person@example.com"))).contains(original);
        verify(mail, times(1)).send(anyString(), anyString(), anyString(), eq("OTP"), isNull());
    }

    @Test
    void emailCodeIsSingleUseAndCannotConsumePhoneCode() {
        String code = send();
        store.save("person@example.com", "000000");
        assertThat(email.verify("PERSON@example.com", code)).isEqualTo("person@example.com");
        assertThatThrownBy(() -> email.verify("person@example.com", code)).isInstanceOf(BizException.class);
        assertThat(store.verifyAndConsume("person@example.com", "000000")).isTrue();
    }

    @Test
    void repeatedWrongAttemptsInvalidateEmailCode() {
        String code = send();
        String wrong = "000000".equals(code) ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> email.verify("person@example.com", wrong))
                    .isInstanceOf(BizException.class);
        }
        assertThatThrownBy(() -> email.verify("person@example.com", wrong))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.OTP_LOCKED));
        assertThatThrownBy(() -> email.verify("person@example.com", code)).isInstanceOf(BizException.class);
    }

    @Test
    void expiredEmailCodeCannotAuthenticate() {
        Instant initial = Instant.now();
        Instant expired = initial.plus(OtpStore.TTL).plusSeconds(1);
        try (var clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(initial);
            String code = send();
            clock.when(Instant::now).thenReturn(expired);
            assertThatThrownBy(() -> email.verify("person@example.com", code)).isInstanceOf(BizException.class);
        }
    }

    @Test
    void sameCodeCanOnlyBeConsumedByOneConcurrentRequest() throws Exception {
        for (int round = 0; round < 16; round++) {
            store.save("race", "123456");
            var barrier = new CyclicBarrier(16);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
                for (int i = 0; i < 16; i++) {
                    results.add(executor.submit(() -> {
                        barrier.await();
                        try { return store.verifyAndConsume("race", "123456"); }
                        catch (BizException locked) { return false; }
                    }));
                }
                int successes = 0;
                for (var result : results) { if (result.get()) { successes++; } }
                assertThat(successes).isEqualTo(1);
            }
        }
    }

    @Test
    void repeatedEmailLoginUsesSameRealAccountWithoutPhoneMerge() {
        store.save("13800000000", "123456");
        var phone = auth.login(new AuthService.LoginCommand("PHONE_OTP", "13800000000", "123456", null, null, true));
        var first = auth.login(new AuthService.LoginCommand("EMAIL_OTP", "Person@Example.COM", send(), null, null, true));
        var second = auth.login(new AuthService.LoginCommand("EMAIL_OTP", "PERSON@example.com", send(), null, null, null));
        assertThat(first.user().userNo()).isEqualTo(second.user().userNo()).isNotEqualTo(phone.user().userNo());
        assertThat(first.user().phone()).isNull();
        assertThat(accounts).hasSize(2);
        assertThat(credentials).containsKeys("EMAIL:person@example.com", "PHONE:13800000000");
    }

    @Test
    void firstRegistrationRequiresAgreementAndExistingLoginDoesNot() {
        assertThatThrownBy(() -> auth.login(new AuthService.LoginCommand("EMAIL_OTP", "person@example.com", send(), null, null, false)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThat(accounts).isEmpty();
        assertThat(credentials).isEmpty();
        // 已注册的人再登录不必再勾协议（App 上协议勾选只拦首登）
        var first = auth.login(new AuthService.LoginCommand("EMAIL_OTP", "person@example.com", send(), null, null, true));
        var again = auth.login(new AuthService.LoginCommand("EMAIL_OTP", "person@example.com", send(), null, null, null));
        assertThat(again.user().userNo()).isEqualTo(first.user().userNo());
        assertThat(accounts).hasSize(1);
    }

    @Test
    void passwordLoginResolvesEmailIdentity() {
        var first = auth.login(new AuthService.LoginCommand("EMAIL_OTP", "person@example.com", send(), null, null, true));
        UsrIdentity password = new UsrIdentity();
        password.setIdentityType(IdentityType.PASSWORD);
        password.setIdentityValue("HASH");
        password.setUserNo(first.user().userNo());
        credentials.put("PASSWORD:HASH", password);
        when(hasher.matches("correct-password", "HASH")).thenReturn(true);
        var second = auth.login(new AuthService.LoginCommand("PASSWORD", "PERSON@example.com", "correct-password", null, null, null));
        assertThat(second.user().userNo()).isEqualTo(first.user().userNo());
        assertThat(accounts).hasSize(1);
    }
    @Test
    void emailLoginAuditNeverContainsRawAddress() {
        assertThat(ai.neargo.shop.auth.LoginAuditor.maskPrincipal("Person@Example.com"))
                .isEqualTo("***@***");
    }

}
