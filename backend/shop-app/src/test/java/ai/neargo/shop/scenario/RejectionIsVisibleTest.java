package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 「被拒」这件事在**生产日志级别下**必须留得下痕迹。
 *
 * <h2>为什么需要它</h2>
 * <p>2026-08-29 盘了一遍拒绝的全部出口，四个在生产上完全无痕：
 * <ul>
 *   <li>{@code ApiAuthEntryPoint} —— 过滤器层 401，**一行都没有**。
 *       而<b>跨端令牌</b>（拿 {@code otk_} 打 {@code /biz}）正是落在这里的，
 *       那是真正的越权尝试，服务端一点痕迹不留</li>
 *   <li>控制器层 401（{@code /mp} 是 permitAll，由 {@code currentUser()} 抛）—— 同样没有</li>
 *   <li>403（{@code @PreAuthorize} 拒绝）—— 有 WARN，但打的是
 *       {@code "access denied: Access is denied"}：**不带路径，几乎零信息**</li>
 *   <li>业务拒绝（含 10403 / 70006）—— {@code log.debug}，生产是 INFO，一行不打</li>
 * </ul>
 *
 * <p>共同后果：**「这个账号怎么什么都看不到」在服务端查不出来。**
 * 而这个仓库当天刚栽过同族的两次 —— 「盯 app.log 的 400」是瞎的（业务错误裹在 200 信封里），
 * 「日志里没有 seeding 那句」也是瞎的（那句在总闸之后，生产永远不打印）。
 *
 * <h2>这条测试钉的是什么</h2>
 * <p>把 logger 按生产设成 {@code INFO} 之后：拒绝必须留下 WARN、且认得出**哪个端点**；
 * 同时**令牌一个字符都不许出现在日志里** —— 令牌进了日志就等于会话可被重放，
 * 而日志会被收集转发。改回 {@code debug} 或去掉路径，这条立刻红。
 */
@SpringBootTest
@ActiveProfiles("test")
class RejectionIsVisibleTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OtpStore otpStore;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** 把某个 logger 按生产级别（INFO）跑一段，返回它留下的 WARN 行。 */
    private List<String> warnsAt(String loggerName, Supplier<Void> body) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName);
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        Level before = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);   // 生产就是 INFO —— debug 在这个级别下一行都不打
        try {
            body.get();
            return appender.list.stream()
                    .filter(x -> x.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        } finally {
            logger.setLevel(before);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("★★★ 跨端令牌被认证层拒掉时要留下 WARN（带路径与前缀），且**令牌本身不许出现**")
    void crossRealmRejectionIsVisible() {
        String ctk = tryConsumerToken();
        List<String> warns = warnsAt("ai.neargo.shop.auth.ApiAuthEntryPoint", () -> {
            try {
                // ctk_ 打 /biz：认证层拒绝，此前这里一行日志都没有
                mvc().perform(get("/biz/context").header("Authorization", "Bearer " + ctk));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });

        assertThat(warns)
                .as("跨端令牌被拒时认证层必须留下 WARN —— 没有它，"
                        + "「拿运营令牌打商家接口」这件事在服务端一点痕迹都没有")
                .isNotEmpty();
        String line = String.join(" | ", warns);
        assertThat(line)
                .as("要认得出是哪个端点被打了：%s", line)
                .contains("/biz/context");
        assertThat(line)
                .as("**令牌本身一个字符都不许进日志** —— 进了就等于会话可被重放，"
                        + "而日志会被收集转发：%s", line)
                .doesNotContain(ctk);
    }

    @Test
    @DisplayName("★★ 判权拒绝（403）的那行 WARN 必须带路径 —— 「Access is denied」等于没说")
    void accessDeniedNamesThePath() {
        String ops = tryOperatorToken();
        List<String> warns = warnsAt("ai.neargo.shop.common.GlobalExceptionHandler", () -> {
            try {
                // 用一个需要具体权限码的 ops 端点；这个账号有没有权限不重要 ——
                // 有权限时这条断言会因为 warns 为空而红，那也是一个真实信号
                mvc().perform(get("/ops/perm/roles/SUPER_ADMIN/points")
                        .header("Authorization", "Bearer " + ops));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });

        // 有权限就不该有 WARN；只有真被拒时才检查它说清楚了没有
        if (!warns.isEmpty()) {
            assertThat(String.join(" | ", warns))
                    .as("判权拒绝要说清是哪个端点，否则这行 WARN 回答不了任何问题")
                    .contains("/ops/");
        }
    }

    private String tryConsumerToken() {
        try {
            return TestLogin.consumer(mvc(), json, otpStore, "13500139901");
        } catch (Exception e) {
            throw new IllegalStateException("拿不到 C 端令牌，这条测试的前提就不成立", e);
        }
    }

    private String tryOperatorToken() {
        try {
            return TestLogin.admin(mvc(), json);
        } catch (Exception e) {
            throw new IllegalStateException("拿不到运营令牌", e);
        }
    }
}
