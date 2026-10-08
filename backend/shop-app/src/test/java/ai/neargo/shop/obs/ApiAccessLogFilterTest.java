package ai.neargo.shop.obs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口耗时与慢日志（TDD-接口耗时与慢日志 AC1/2/3/5/7）。
 *
 * <p><b>为什么起真端口而不是 MockMvc</b>：这个过滤器是用
 * {@code FilterRegistrationBean} 全局注册的（`SecurityConfig` 有 5 条链，挂进去要挂五遍、
 * 还漏掉不匹配任何链的请求）。而 {@code MockMvc.webAppContextSetup} <b>不装</b>
 * 这样注册的过滤器 —— 用 MockMvc 写这组用例会全绿而一个字节都没测到。
 *
 * <p>阈值压到 1ms：真实请求都会超过它，于是「慢日志有没有写」这件事可以稳定地验，
 * 不用靠造一个真的慢接口（那种用例会在快机器上随机变绿）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "shop.obs.access-log=true",
        "shop.obs.slow-ms=1",
        "shop.obs.exclude=/actuator/**,/ops/stream",
})
@DisplayName("接口耗时：访问行与慢日志")
class ApiAccessLogFilterTest {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private ListAppender<ILoggingEvent> access;
    private ListAppender<ILoggingEvent> slow;

    private ListAppender<ILoggingEvent> attach(String name) {
        var logger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(name);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        return appender;
    }

    @BeforeEach
    void setUp() {
        access = attach("api.access");
        slow = attach("api.slow");
    }

    @AfterEach
    void tearDown() {
        var ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ctx.getLogger("api.access").detachAppender(access);
        ctx.getLogger("api.slow").detachAppender(slow);
    }

    private void get(String path) throws Exception {
        http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private List<String> lines(ListAppender<ILoggingEvent> a) {
        return a.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("★★★ AC1 每个请求记一行，含方法、接口、状态码、耗时")
    void everyRequestGetsOneLineWithDuration() throws Exception {
        get("/mp/goods/G20260101000000000001");

        assertThat(lines(access)).as("访问行要有").isNotEmpty();
        String line = lines(access).getLast();
        assertThat(line).startsWith("api m=GET ");
        assertThat(line).containsPattern(" s=\\d{3} ");
        assertThat(line).as("耗时是这条日志存在的全部理由").containsPattern(" ms=\\d+");
        assertThat(line).as("rid 用来把访问行与慢日志那行对上").containsPattern(" rid=\\w+");
    }

    @Test
    @DisplayName("★★★ AC2 超过阈值的额外进慢日志，两行靠 rid 对得上")
    void slowRequestsAlsoGoToSlowLog() throws Exception {
        get("/mp/goods/G20260101000000000001");

        assertThat(lines(slow)).as("阈值 1ms，真实请求必定超过").isNotEmpty();
        String slowLine = lines(slow).getLast();
        assertThat(slowLine).startsWith("api m=GET ");
        // 同一个请求的两行必须带同一个 rid —— 否则报告脚本去重不掉，慢比例会被高估
        String rid = slowLine.replaceAll(".* rid=(\\w+).*", "$1");
        assertThat(lines(access)).anySatisfy(l -> assertThat(l).contains("rid=" + rid));
    }

    @Test
    @DisplayName("★★★ AC3 认证失败也要记，且路径里的号段要脱敏 —— 否则同一接口散成几千行")
    void unauthenticatedRequestsAreMaskedNotExploded() throws Exception {
        // /biz/** 没带令牌 → 401，到不了 dispatcher，BEST_MATCHING_PATTERN 为空
        get("/biz/goods/G20260101000000000001");

        String line = lines(access).getLast();
        assertThat(line).as("401 也要记 —— 查「谁在被拒」靠的就是它").contains(" s=401 ");
        assertThat(line).as("号段要换成 *，不然每个商品一行、聚合不起来")
                .doesNotContain("G20260101000000000001");
        assertThat(line).contains(" p=/biz/goods/*");
    }

    @Test
    @DisplayName("★★★ AC5 健康探针不记 —— 部署脚本一直在打它")
    void actuatorIsExcluded() throws Exception {
        get("/actuator/health");
        assertThat(lines(access)).as("/actuator/** 一行都不该有")
                .noneSatisfy(l -> assertThat(l).contains("/actuator"));
    }

    @Test
    @DisplayName("★★ AC4 不记查询串 —— 那里面有地址和手机号")
    void queryStringIsNeverLogged() throws Exception {
        get("/mp/goods/G20260101000000000001?phone=13800000000&addr=%E6%B7%B1%E5%9C%B3");

        assertThat(lines(access).getLast()).doesNotContain("13800000000").doesNotContain("phone");
    }
}
