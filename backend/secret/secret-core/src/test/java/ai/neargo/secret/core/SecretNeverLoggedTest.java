package ai.neargo.secret.core;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyRegistry.NewKey;
import ai.neargo.secret.api.KeyRegistry.NewVersion;
import ai.neargo.secret.api.SecretNamespace;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 守卫（完成度复核 §3.5）：把根日志开到 TRACE，走一遍全部生命周期（含 MyBatis 打印 SQL 参数、含各种失败分支），
 * 抽样的每一行日志与每一条异常消息里<b>都不得出现密钥材料</b>：明文、主密钥 —— 原文、Base64、十六进制三种写法都查。
 *
 * <p>MyBatis 在 DEBUG 下会把 SQL 参数原样打印，于是「明文从没进过 SQL」在这里被直接验证：
 * 一旦有人把明文或主密钥写进任何一列，这条就红。
 */
class SecretNeverLoggedTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger root;
    private Level rootLevel;

    @BeforeEach
    void captureEverything() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLevel = root.getLevel();
        root.setLevel(Level.TRACE);
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void release() {
        root.detachAppender(appender);
        root.setLevel(rootLevel);
    }

    @Test
    @DisplayName("★★★ 日志抽样（TRACE，含 SQL 参数与失败分支）里没有任何密钥材料")
    void noKeyMaterialInAnyLogLine() {
        SecretFixture f = new SecretFixture("SANDBOX");
        String mkA = f.addMasterKey("pay-sandbox-a", SecretNamespace.PAY, true);
        f.start();
        byte[] field = "S-ID-4401-9988-7766".getBytes(StandardCharsets.UTF_8);
        byte[] v1 = "uploaded-hmac-material-v1-zz".getBytes(StandardCharsets.UTF_8);
        List<String> thrown = new ArrayList<>();

        var ref = f.vault.seal(new KeyOwner(SecretNamespace.PAY, "ONBOARDING_MATERIAL", 1), field);
        f.vault.open(SecretNamespace.PAY, ref);
        long keyId = f.registry.register(new NewKey(new KeyOwner(SecretNamespace.PAY, "PARTNER_APP", 9),
                KeyPurpose.INBOUND_HMAC, "p9", null, "HMAC_SHA256", "SYMMETRIC_SECRET", "ops-a"));
        var r1 = f.registry.addVersion(keyId, new NewVersion(v1, null, "UPLOADED", null, null, null, "ops-a"));
        f.registry.markVerified(r1, "e", "ops-a");
        catching(thrown, () -> f.registry.enable(r1, "ops-a", null));
        f.registry.enable(r1, "ops-b", null);
        var issued = f.registry.generate(keyId, 32, "ops-a", "rotate");
        f.registry.markVerified(issued.ref(), "e", "ops-a");
        f.registry.enable(issued.ref(), "ops-b", null);
        f.registry.verificationVersions(keyId).forEach(f.registry::materialOf);
        f.registry.materialOf(r1);
        catching(thrown, () -> f.vault.open(SecretNamespace.LOGISTICS, ref));
        String mkB = f.addMasterKey("pay-sandbox-b", SecretNamespace.PAY, true);
        f.start();
        f.clock.advance(Duration.ofDays(30));
        f.lifecycle.runOnce();
        f.registry.revoke(r1, "ops-c", "drill");
        catching(thrown, () -> f.registry.materialOf(r1));

        List<String> needles = new ArrayList<>();
        for (byte[] secret : List.of(field, v1, issued.plaintext(), Base64.getDecoder().decode(mkA),
                Base64.getDecoder().decode(mkB))) {
            needles.add(new String(secret, StandardCharsets.ISO_8859_1));
            needles.add(Base64.getEncoder().encodeToString(secret));
            needles.add(HexFormat.of().formatHex(secret));
        }
        List<String> sampled = new ArrayList<>(thrown);
        for (ILoggingEvent e : appender.list) {
            sampled.add(e.getFormattedMessage());
            for (IThrowableProxy t = e.getThrowableProxy(); t != null; t = t.getCause()) {
                sampled.add(t.getMessage());
            }
        }

        assertThat(appender.list).as("对照量：TRACE 下一行日志都没抓到，那这条量的是空气").hasSizeGreaterThan(20);
        assertThat(thrown).as("对照量：失败分支要真的走到").hasSize(3);
        for (String line : sampled) {
            for (String needle : needles) {
                assertThat(line).as("日志 / 异常里出现了密钥材料").doesNotContain(needle);
            }
        }
    }

    private static void catching(List<String> sink, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            sink.add(String.valueOf(e.getMessage()));
        }
    }
}
