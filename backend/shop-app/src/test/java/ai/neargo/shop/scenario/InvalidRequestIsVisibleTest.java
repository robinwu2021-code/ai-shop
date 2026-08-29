package ai.neargo.shop.scenario;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * <b>入参被拒必须在日志里看得见 —— 而且不能把 body 写进去。</b>
 *
 * <p>背景：业务错误一律 200 + 信封里的 code，所以 nginx 的 access.log 里它是 200；
 * 而 `GlobalExceptionHandler` 此前只有一句 `log.debug`，生产跑 INFO = 一行不打。
 * 2026-08-29 实测：线上打出好几个 10400，`grep -c 10400 app.log` 是 **0**。
 *
 * <p>这在 15 个端点接上 `@Valid` 那天（945a089e）从「无所谓」变成「要命」——
 * 线上跑着的是已发出去的 App，若某个版本真在传空值，链路会从「走到下游」
 * 变成「当场拒」，而没有任何人会知道。**「日志里没看到 400」此前什么都不证明。**
 *
 * <p>两条断言缺一不可：看得见（否则等于没打）、不带值（否则是往磁盘上堆 PII）。
 */
@SpringBootTest
@ActiveProfiles("test")
class InvalidRequestIsVisibleTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 入参被拒要打一条 WARN（带路径与字段名），且**不含 body 里的值**")
    void rejectedRequestIsLoggedWithoutBody() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                "ai.neargo.shop.common.GlobalExceptionHandler");
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        logger.addAppender(appender);
        Level before = logger.getLevel();
        logger.setLevel(Level.INFO);   // 生产就是 INFO —— debug 在这个级别下一行都不打
        try {
            // 手机号是**真格式的假号**：它必须不出现在日志里
            mvc().perform(post("/mp/user/otp/send")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"\",\"scene\":\"13800001234\"}"))
                    .andReturn();

            List<ILoggingEvent> warns = appender.list.stream()
                    .filter(x -> x.getLevel() == Level.WARN).toList();
            assertThat(warns)
                    .as("入参被拒没有留下任何 INFO 及以上的日志 —— 生产上这类拒绝就是隐形的")
                    .isNotEmpty();

            String line = warns.get(0).getFormattedMessage();
            assertThat(line)
                    .as("这行日志要说得出是哪个接口、哪个字段，否则看见了也查不动")
                    .contains("/mp/user/otp/send").contains("phone");
            assertThat(line)
                    .as("日志里出现了 body 里的值 —— 请求体里有手机号、验证码、收货地址，"
                            + "写进日志等于把一份 PII 副本堆在磁盘上，而它比库更难管")
                    .doesNotContain("13800001234");
        } finally {
            logger.setLevel(before);
            logger.detachAppender(appender);
        }
    }
}
