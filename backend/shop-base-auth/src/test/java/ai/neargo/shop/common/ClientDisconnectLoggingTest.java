package ai.neargo.shop.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * 客户端断开不该记成 ERROR，也不该再往断开的连接上写一个「成功」。
 *
 * <p>走真的 MockMvc + 真的 {@link GlobalExceptionHandler}，因为要证的是
 * 「抛回给 Spring 之后它确实收住了」—— 直调 {@code onAny} 只能证明抛了，证不了没人再往外冒。
 */
@DisplayName("客户端断开：不记 ERROR、不写包体")
class ClientDisconnectLoggingTest {

    @RestController
    static class Boom {
        @GetMapping("/sse-gone")
        Object sseGone() throws IOException {
            throw new AsyncRequestNotUsableException("Servlet container error notification for disconnected client");
        }

        @GetMapping("/pipe-broken")
        Object pipeBroken() {
            throw new HttpMessageNotWritableException("Could not write JSON: ServletOutputStream failed to write",
                    new IOException("Broken pipe"));
        }

        @GetMapping("/real-bug")
        Object realBug() {
            throw new IllegalStateException("真故障");
        }
    }

    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
        mvc = MockMvcBuilders.standaloneSetup(new Boom())
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseWrapper())
                .build();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private long errors() {
        return appender.list.stream().filter(ev -> ev.getLevel() == Level.ERROR).count();
    }

    @Test
    @DisplayName("★★★ SSE 断开（运营端关页签）：零 ERROR、不写包体")
    void sseDisconnectIsQuiet() throws Exception {
        MvcResult r = mvc.perform(get("/sse-gone")).andReturn();

        assertThat(errors()).as("关个页签就记一条 ERROR，真故障会被淹掉").isZero();
        assertThat(r.getResponse().getContentAsString())
                .as("连接已断，再写一个 ok(null) 只会再报一次，而且包体说的是「成功」").isEmpty();
    }

    @Test
    @DisplayName("★★ 写到一半 Broken pipe（手机切后台）：零 ERROR")
    void brokenPipeIsQuiet() throws Exception {
        MvcResult r = mvc.perform(get("/pipe-broken")).andReturn();

        assertThat(errors()).isZero();
        assertThat(r.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("★★★ 对照：真故障照样 ERROR + INTERNAL_ERROR 包体 —— 否则上面两条的零可能只是日志没接上")
    void realFailureStillLogsError() throws Exception {
        MvcResult r = mvc.perform(get("/real-bug")).andReturn();

        assertThat(errors()).isEqualTo(1);
        assertThat(r.getResponse().getContentAsString())
                .contains("\"code\":" + ErrorCode.INTERNAL_ERROR.code());
    }
}
