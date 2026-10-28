package ai.neargo.shop.spi.pay;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 调用方一行 {@code CallSources.current()} 拿到来源（TDD-支付服务-接入守卫模块pay-guard §2.3 A；开发计划 M2.1）。
 * 三种上下文：HTTP 请求（由 web 层绑定）、定时任务、Outbox 消费 —— 都没绑的线程也给得出来源，只是标 UNKNOWN。
 */
class CallSourcesTest {

    @BeforeEach
    void service() {
        CallSources.configure("shop-app");
    }

    @AfterEach
    void reset() {
        CallSources.configure(null);
    }

    @Test
    @DisplayName("★★★ HTTP 请求：上游接口、来源端、终端用户 IP、操作人、追踪号都来自绑定的请求；UA 只留摘要")
    void httpOrigin() throws Exception {
        CallSource s;
        try (var ignored = CallSources.bind(CallSources.Origin.http("req-abc", "POST /mp/orders/{orderNo}/pay", "APP_C",
                "223.104.1.1", "Mozilla/5.0 (iPhone)", "U-1001"))) {
            s = CallSources.current();
        }

        assertThat(s.service()).isEqualTo("shop-app");
        assertThat(s.instance()).isNotBlank();
        assertThat(s.serverIp()).isNotBlank();
        assertThat(s.traceId()).isEqualTo("req-abc");
        assertThat(s.originApi()).isEqualTo("POST /mp/orders/{orderNo}/pay");
        assertThat(s.originClient()).isEqualTo("APP_C");
        assertThat(s.originIp()).isEqualTo("223.104.1.1");
        assertThat(s.operatorNo()).isEqualTo("U-1001");
        assertThat(s.originUaDigest()).isEqualTo(sha256("Mozilla/5.0 (iPhone)")).doesNotContain("iPhone");
        assertThat(Instant.parse(s.invokedAt())).isNotNull();
    }

    @Test
    @DisplayName("★★★ 定时任务：来源端 JOB、上游接口是任务名、操作人 SYSTEM；跑完解绑")
    void jobOrigin() {
        CallSource inside = CallSources.asJob("pay-order-compensation", CallSources::current);

        assertThat(inside.originClient()).isEqualTo(CallSources.JOB);
        assertThat(inside.originApi()).isEqualTo("pay-order-compensation");
        assertThat(inside.operatorNo()).isEqualTo(CallSources.SYSTEM);
        assertThat(inside.traceId()).isNotBlank();
        assertThat(CallSources.current().originClient()).as("跑完解绑").isEqualTo(CallSources.UNKNOWN);
    }

    @Test
    @DisplayName("★★★ Outbox 消费：来源端 OUTBOX、上游接口是事件类型")
    void outboxOrigin() {
        CallSource inside = CallSources.asOutbox("ORDER_PAID", CallSources::current);

        assertThat(inside.originClient()).isEqualTo(CallSources.OUTBOX);
        assertThat(inside.originApi()).isEqualTo("ORDER_PAID");
        assertThat(inside.operatorNo()).isEqualTo(CallSources.SYSTEM);
    }

    @Test
    @DisplayName("★★ 嵌套绑定：内层结束恢复外层 —— 请求里同步跑一段任务逻辑后，来源回到那个请求")
    void nestedBindingRestores() {
        try (var ignored = CallSources.bind(CallSources.Origin.http("req-outer", "GET /x", "APP_BIZ", null, null, "U-2"))) {
            CallSources.asJob("inner", CallSources::current);
            assertThat(CallSources.current().traceId()).isEqualTo("req-outer");
        }
        assertThat(CallSources.current().originClient()).isEqualTo(CallSources.UNKNOWN);
    }

    @Test
    @DisplayName("★★ 什么都没绑：照样给出来源（服务、实例、新追踪号），来源端标 UNKNOWN —— 不抛")
    void unboundStillHasSource() {
        CallSource s = CallSources.current();

        assertThat(s.service()).isEqualTo("shop-app");
        assertThat(s.originClient()).isEqualTo(CallSources.UNKNOWN);
        assertThat(s.traceId()).isNotBlank();
        assertThat(CallSources.current().traceId()).as("每次调用各一个追踪号").isNotEqualTo(s.traceId());
    }

    @Test
    @DisplayName("★ 没配服务名：记 UNKNOWN，不是空")
    void unconfiguredService() {
        CallSources.configure(null);
        assertThat(CallSources.current().service()).isEqualTo(CallSources.UNKNOWN);
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
