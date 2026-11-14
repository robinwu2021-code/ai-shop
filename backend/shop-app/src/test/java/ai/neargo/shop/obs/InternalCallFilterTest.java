package ai.neargo.shop.obs;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.config.obs.InternalCallFilter;
import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSourceCodec;
import ai.neargo.shop.spi.pay.CallSources;
import ai.neargo.shop.spi.pay.InternalHeaders;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 业务侧的内部入口（ADR-045 G9；TDD-支付服务-接入守卫模块pay-guard §2.7.4；开发计划 M2.6）：
 * 与支付侧 {@code InternalGuard} 同一套规则 —— 只识别、不拒绝；另把收到的来源接力下去：
 * 处理这次内部调用时再调别的服务（比如回头调支付），带的是同一个追踪号与上游接口。
 */
class InternalCallFilterTest {

    private static final CallSource FROM_PAY = new CallSource("pay-svc", null, "pay-01", "10.0.2.5", "req-pay-77",
            "POST /mp/orders/{orderNo}/pay", "APP_C", "223.104.1.1", "ab".repeat(32), "U-1", "2026-10-28T08:00:00Z");

    private final InternalCallFilter filter = new InternalCallFilter();

    private static MockHttpServletRequest request(String caller, String source) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/internal/core/entitlement/grant");
        if (caller != null) {
            r.addHeader(InternalHeaders.CALLER, caller);
        }
        if (source != null) {
            r.addHeader(InternalHeaders.CALL_SOURCE, source);
        }
        return r;
    }

    @Test
    @DisplayName("★★★ 带来源：放行；请求属性里有调用方与来源；处理期间 CallSources.current() 接力的是上游那一份，结束解绑")
    void relaysTheSource() throws Exception {
        List<CallSource> seen = new ArrayList<>();
        MockHttpServletRequest req = request("PAY_SVC", CallSourceCodec.encode(FROM_PAY));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, (rq, rs) -> seen.add(CallSources.current()));

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(req.getAttribute(InternalCallFilter.CALLER_ATTRIBUTE)).isEqualTo("PAY_SVC");
        assertThat(req.getAttribute(InternalCallFilter.SOURCE_ATTRIBUTE)).isEqualTo(FROM_PAY);
        assertThat(seen).singleElement().satisfies(s -> {
            assertThat(s.traceId()).isEqualTo("req-pay-77");
            assertThat(s.originApi()).isEqualTo("POST /mp/orders/{orderNo}/pay");
            assertThat(s.originClient()).isEqualTo("APP_C");
            assertThat(s.operatorNo()).isEqualTo("U-1");
        });
        assertThat(CallSources.current().originClient()).isEqualTo(CallSources.UNKNOWN);
    }

    @Test
    @DisplayName("★★★ 没带、带坏了、连调用方都没有：照样放行，记 UNKNOWN —— 各端点原有的令牌校验不受影响")
    void neverRejects() throws Exception {
        for (MockHttpServletRequest req : List.of(request(null, null), request("JOB_WORKER", "%%%bad"))) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            boolean[] reached = {false};
            filter.doFilter(req, resp, (rq, rs) -> reached[0] = true);

            assertThat(reached[0]).isTrue();
            assertThat(resp.getStatus()).isEqualTo(200);
            assertThat(((CallSource) req.getAttribute(InternalCallFilter.SOURCE_ATTRIBUTE)).service())
                    .isEqualTo(CallSources.UNKNOWN);
        }
    }

    @Test
    @DisplayName("★★ 只管 /internal/**：别的路径不碰")
    void onlyInternalPaths() {
        assertThat(filter.shouldNotFilterPath("/mp/goods/G1")).isTrue();
        assertThat(filter.shouldNotFilterPath("/internal/job/x/run")).isFalse();
    }
}
