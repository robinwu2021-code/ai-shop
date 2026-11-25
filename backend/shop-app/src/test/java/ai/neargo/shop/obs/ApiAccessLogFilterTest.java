package ai.neargo.shop.obs;

import ai.neargo.shop.common.RequestLogContext;
import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSourceCodec;
import ai.neargo.shop.common.ApiAccessLogFilter;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口耗时与慢日志（TDD-接口耗时与慢日志 AC1-5、AC7）。
 *
 * <p><b>不起 Spring 上下文 —— 这是改过一次的决定，理由值得留着。</b>
 *
 * <p>第一版用 {@code @SpringBootTest(RANDOM_PORT)} + {@code @TestPropertySource}
 * 走真实链路（因为 MockMvc 装不到 {@code FilterRegistrationBean} 注册的过滤器）。
 * 那样写本身是对的，**但它新建了一个 Spring 上下文** —— 全量测试里上下文缓存有上限，
 * 多一个就挤掉一个旧的，于是 {@code TopicFlowTest} 开始报 {@code /ops/topics} 判权拒绝。
 *
 * <p>实测：全量 2443 跑时红 2 条；<b>只把本类排除掉，2441 跑 0 失败</b>。
 * 而本类单独跑、`TopicFlowTest` 单独跑，两边都是绿的 ——
 * 典型的「单独跑绿、全量红」，代价由别人的用例承担。
 *
 * <p>所以行为判据改成**纯单元**：过滤器就是一个
 * {@code (request, response, chain) -> void}，不需要容器也能把它的每条规则验穿。
 * 「它到底有没有被装进真实应用」另由 {@link ObsWiringTest} 守，那个类复用已有上下文、
 * 不新建。
 */
@DisplayName("接口耗时：访问行与慢日志")
class ApiAccessLogFilterTest {

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

    private List<String> lines(ListAppender<ILoggingEvent> a) {
        return a.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** 跑一次过滤器。{@code template} 为空 = 模拟「没到 dispatcher」（认证就被拒的请求） */
    private void run(ApiAccessLogFilter filter, String method, String uri, String template,
                     int status, String storeHeader) throws Exception {
        var req = new MockHttpServletRequest(method, uri);
        if (template != null) {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, template);
        }
        if (storeHeader != null) {
            req.addHeader("X-Store-No", storeHeader);
        }
        var resp = new MockHttpServletResponse();
        resp.setStatus(status);
        filter.doFilter(req, resp, (rq, rs) -> { });
    }

    private ApiAccessLogFilter filter(long slowMs) {
        return new ApiAccessLogFilter(true, slowMs, List.of("/actuator/**", "/ops/stream"));
    }

    @Test
    @DisplayName("★★★ AC1 每个请求记一行，含方法、接口、状态码、耗时、rid")
    void everyRequestGetsOneLineWithDuration() throws Exception {
        run(filter(1000), "GET", "/mp/goods/G1", "/mp/goods/{goodsNo}", 200, null);

        assertThat(lines(access)).hasSize(1);
        String line = lines(access).getFirst();
        assertThat(line).startsWith("api m=GET p=/mp/goods/{goodsNo} s=200 ");
        assertThat(line).as("耗时是这条日志存在的全部理由").containsPattern(" ms=\\d+");
        assertThat(line).as("rid 用来把访问行与慢日志那行对上").containsPattern(" rid=\\w+");
    }

    @Test
    @DisplayName("★★★ AC2 超过阈值的额外进慢日志，两行共用同一个 rid")
    void slowRequestsAlsoGoToSlowLog() throws Exception {
        // 阈值 0 = 任何请求都算慢，于是「慢的那条写没写」可以确定地验，
        // 不用造一个真的慢接口（那种用例在快机器上会随机变绿）
        run(filter(0), "GET", "/mp/goods/G1", "/mp/goods/{goodsNo}", 200, null);

        assertThat(lines(slow)).hasSize(1);
        String rid = lines(slow).getFirst().replaceAll(".* rid=(\\w+).*", "$1");
        // 两行必须同 rid —— 否则报告脚本去重不掉，慢比例会被系统性高估
        assertThat(lines(access).getFirst()).contains("rid=" + rid);
    }

    @Test
    @DisplayName("★★★ AC7 没到阈值就不进慢日志 —— 阈值真的在起作用")
    void fastRequestsStayOutOfSlowLog() throws Exception {
        run(filter(9_999_999), "GET", "/mp/goods/G1", "/mp/goods/{goodsNo}", 200, null);

        assertThat(lines(access)).as("访问行照记").hasSize(1);
        assertThat(lines(slow)).as("阈值高到不可能触发，慢日志必须是空的").isEmpty();
    }

    @Test
    @DisplayName("★★★ AC3 拿不到模板时只记录固定分类")
    void rawPathIsMaskedWhenTemplateMissing() throws Exception {
        // 认证失败的请求到不了 dispatcher，BEST_MATCHING_PATTERN 为空
        run(filter(1000), "GET", "/biz/goods/opaque-secret-uuid", null, 401, null);

        String line = lines(access).getFirst();
        assertThat(line).as("401 也要记 —— 查「谁在被拒」靠的就是它").contains(" s=401 ");
        assertThat(line).as("分类不得包含原始路径")
                .doesNotContain("opaque-secret-uuid");
        assertThat(line).contains(" p=/biz/SECURITY_REJECTED");
    }

    @Test
    @DisplayName("★★ AC3 有模板时原样用模板，不要去脱敏它")
    void templateIsUsedAsIs() throws Exception {
        run(filter(1000), "POST", "/biz/goods/save", "/biz/goods/save", 200, null);
        assertThat(lines(access).getFirst()).contains(" p=/biz/goods/save ");
    }

    @Test
    @DisplayName("★★★ AC5 健康探针与 SSE 不计 —— 探针是噪声，SSE 一挂几分钟会淹掉慢日志")
    void excludedPathsAreNotLogged() throws Exception {
        var f = filter(0);
        run(f, "GET", "/actuator/health", null, 200, null);
        run(f, "GET", "/ops/stream", null, 200, null);

        assertThat(lines(access)).as("两条都不该记").isEmpty();
        assertThat(lines(slow)).isEmpty();
    }

    @Test
    @DisplayName("★★ AC4 不记查询串 —— 那里面有地址和手机号")
    void queryStringIsNeverLogged() throws Exception {
        var req = new MockHttpServletRequest("GET", "/mp/goods/G1");
        req.setQueryString("phone=13800000000&addr=%E6%B7%B1%E5%9C%B3");
        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/mp/goods/{goodsNo}");
        filter(1000).doFilter(req, new MockHttpServletResponse(), (rq, rs) -> { });

        assertThat(lines(access).getFirst()).doesNotContain("13800000000").doesNotContain("phone");
    }

    @Test
    @DisplayName("★★ 请求头只能参与业务选择，日志只能采信已确认门店")
    void rawStoreHeaderIsNotLogged() throws Exception {
        run(filter(1000), "GET", "/biz/orders", "/biz/orders", 200, "ST20260101000000000009");
        assertThat(lines(access).getFirst()).doesNotContain("ST20260101000000000009");
    }

    @Test
    @DisplayName("★★ 关掉全量行只剩慢日志 —— 这是生产可能的配置")
    void accessLogCanBeTurnedOff() throws Exception {
        var f = new ApiAccessLogFilter(false, 0, List.of());
        run(f, "GET", "/mp/goods/G1", "/mp/goods/{goodsNo}", 200, null);

        assertThat(lines(access)).isEmpty();
        assertThat(lines(slow)).as("慢日志不受那个开关影响").hasSize(1);
    }

    @Test
    void untrustedInternalMetadataIsNotLogged() throws Exception {
        var req = new MockHttpServletRequest("POST", "/internal/core/entitlement/grant");
        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/internal/core/entitlement/grant");
        req.setAttribute(ai.neargo.shop.config.obs.InternalCallFilter.CALLER_ATTRIBUTE, "caller-secret");
        req.setAttribute(ai.neargo.shop.config.obs.InternalCallFilter.SOURCE_ATTRIBUTE,
                new ai.neargo.shop.spi.pay.CallSource("service-secret", null, "instance-secret", null,
                        "trace-secret", "POST /path-secret", "APP_C", null, null, null, null));
        filter(1000).doFilter(req, new MockHttpServletResponse(), (rq, rs) -> { });

        assertThat(lines(access).getFirst()).doesNotContain("-secret");
    }

    @Test
    void requestIdIsGeneratedAndSharedWithTheResponse() throws Exception {
        var req = new MockHttpServletRequest("GET", "/ops/stores");
        req.addHeader("X-Request-Id", "client-chosen-id");
        var response = new MockHttpServletResponse();

        filter(1000).doFilter(req, response, (rq, rs) -> { });

        String id = response.getHeader("X-Request-Id");
        assertThat(id).matches("[0-9a-f]{32}");
        assertThat(lines(access)).singleElement().asString().contains("rid=" + id).doesNotContain("client-chosen-id");
    }

    @Test
    void separatesBusinessRejectionFromHttpStatusAndUsesAuthorizedScope() throws Exception {
        var req = new MockHttpServletRequest("GET", "/biz/stores");
        req.addHeader("X-Store-No", "UNAUTHORIZED");
        filter(1000).doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {
            rq.setAttribute(RequestLogContext.BUSINESS_CODE, 10403);
            rq.setAttribute(RequestLogContext.STORE_NO, "AUTHORIZED");
        });

        assertThat(lines(access)).singleElement().asString()
                .contains("s=200", "bizCode=10403", "outcome=REJECTED", "store=AUTHORIZED")
                .doesNotContain("store=UNAUTHORIZED");
    }

    @Test
    void onlyTrustedInternalAndJobCallsInheritCorrelation() throws Exception {
        String internalId = "0123456789abcdef0123456789abcdef";
        String jobId = "1b9769ce-77c2-4da8-974b-9ab0bc44d133";
        var filter = new ApiAccessLogFilter(true, 1000, List.of(), "test-service-token", "test-job-token");
        var internal = new MockHttpServletRequest("POST", "/internal/food/order");
        internal.addHeader("X-Call-Source", CallSourceCodec.encode(
                new CallSource("shop-app", null, null, null, internalId, null, null, null, null, null, null)));
        internal.addHeader("X-Internal-Token", "test-service-token");
        var internalResponse = new MockHttpServletResponse();
        filter.doFilter(internal, internalResponse, (rq, rs) -> {});
        assertThat(internalResponse.getHeader("X-Request-Id")).isEqualTo(internalId);

        var forged = new MockHttpServletRequest("POST", "/internal/food/order");
        forged.addHeader("X-Call-Source", internal.getHeader("X-Call-Source"));
        forged.addHeader("X-Internal-Token", "invalid-token");
        var forgedResponse = new MockHttpServletResponse();
        filter.doFilter(forged, forgedResponse, (rq, rs) -> {});
        assertThat(forgedResponse.getHeader("X-Request-Id")).isNotEqualTo(internalId);

        var job = new MockHttpServletRequest("POST", "/internal/job/cleanup/run");
        job.addHeader("X-Request-Id", jobId);
        job.addHeader("X-Job-Token", "test-job-token");
        var jobResponse = new MockHttpServletResponse();
        filter.doFilter(job, jobResponse, (rq, rs) -> {});
        assertThat(jobResponse.getHeader("X-Request-Id")).isEqualTo(jobId);
        assertThat(lines(access)).hasSize(3).noneMatch(line -> line.contains("token"));
    }

    @Test
    void recordsAsyncCompletionOnlyAfterFinalStatus() throws Exception {
        var req = new MockHttpServletRequest("GET", "/ops/stores");
        req.setAsyncSupported(true);
        var response = new MockHttpServletResponse();

        filter(1000).doFilter(req, response, (rq, rs) -> rq.startAsync(rq, rs));
        assertThat(lines(access)).isEmpty();

        response.setStatus(503);
        req.getAsyncContext().complete();
        assertThat(lines(access)).singleElement().asString().contains(" s=503 ");
    }
}
