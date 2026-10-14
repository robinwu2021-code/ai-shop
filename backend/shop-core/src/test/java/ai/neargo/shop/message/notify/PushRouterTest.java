package ai.neargo.shop.message.notify;

import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.spi.notify.PushGateway;
import ai.neargo.shop.spi.notify.PushPort;
import ai.neargo.shop.spi.notify.PushProvider;
import ai.neargo.shop.spi.notify.SendResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多供应商推送路由的三条不变量（设计：多渠道推送与运营端触达配置 · 需求 2）。
 *
 * <p>手写假 gateway 与假 writer，不引 mock：断言的是**真实分发去向与真实记录参数**。
 */
@DisplayName("推送路由")
class PushRouterTest {

    /** 捕获写进记录表的字段（同 NotifyLoggingPortTest 的手法）。 */
    private static final class CapturingWriter extends NotifyLogWriter {
        record Row(String channel, String status, String error, String providerMsgId, String provider) {
        }

        final List<Row> rows = new ArrayList<>();

        CapturingWriter() {
            super(null);
        }

        // PushRouter 走带 provider 的 10 参重载 —— 捕获它，顺带断言 provider 归对了家
        @Override
        public void write(String channel, String bizType, String targetPlain, String templateCode,
                          String templateNo, String status, String error, String providerMsgId,
                          String operatorNo, String provider) {
            rows.add(new Row(channel, status, error, providerMsgId, provider));
        }
    }

    /** 记下自己被调到的假 gateway。 */
    private static final class FakeGateway implements PushGateway {
        final String provider;
        final boolean stub;
        final RuntimeException boom;
        String lastClientId;

        FakeGateway(String provider, boolean stub, RuntimeException boom) {
            this.provider = provider;
            this.stub = stub;
            this.boom = boom;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public boolean stub() {
            return stub;
        }

        @Override
        public SendResult push(String clientId, String title, String body, String link, String level) {
            this.lastClientId = clientId;
            if (boom != null) {
                throw boom;
            }
            return SendResult.of("task-" + provider, level);
        }
    }

    @Test
    @DisplayName("★★★ 桩在场：任何 provider 都走桩，成功记一条 SENT")
    void stubTakesEveryProvider() {
        CapturingWriter writer = new CapturingWriter();
        FakeGateway stub = new FakeGateway(PushProvider.GETUI, true, null);
        FakeGateway fcm = new FakeGateway(PushProvider.FCM, false, null);
        PushRouter router = new PushRouter(List.of(stub, fcm), writer);

        router.push(PushProvider.FCM, "cid-1", "标题", "正文", "/x", PushPort.LEVEL_NORMAL);

        assertThat(stub.lastClientId).as("桩在场时 FCM 也走桩").isEqualTo("cid-1");
        assertThat(fcm.lastClientId).as("真实 FCM 不该被调到").isNull();
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.getFirst().status()).isEqualTo(SysNotifyLog.SENT);
        assertThat(writer.rows.getFirst().providerMsgId()).isEqualTo("task-GETUI");
        assertThat(writer.rows.getFirst().provider())
                .as("记录按**请求的**供应商归家：桩顶发但这条设备是 FCM").isEqualTo(PushProvider.FCM);
    }

    @Test
    @DisplayName("★★★ 无桩：按 provider 分发到对应真实 gateway")
    void routesByProviderWhenNoStub() {
        CapturingWriter writer = new CapturingWriter();
        FakeGateway getui = new FakeGateway(PushProvider.GETUI, false, null);
        FakeGateway fcm = new FakeGateway(PushProvider.FCM, false, null);
        PushRouter router = new PushRouter(List.of(getui, fcm), writer);

        router.push(PushProvider.FCM, "cid-2", "t", "b", "/x", PushPort.LEVEL_NORMAL);

        assertThat(fcm.lastClientId).isEqualTo("cid-2");
        assertThat(getui.lastClientId).as("GETUI 不该被 FCM 的推送调到").isNull();
    }

    @Test
    @DisplayName("★★★ 无桩且该 provider 无 gateway（如 FCM 尚在 P3）：记 FAILED 并抛，站内信兜底")
    void missingGatewayRecordsFailedThenThrows() {
        CapturingWriter writer = new CapturingWriter();
        FakeGateway getui = new FakeGateway(PushProvider.GETUI, false, null);
        PushRouter router = new PushRouter(List.of(getui), writer);

        assertThatThrownBy(() ->
                router.push(PushProvider.FCM, "cid-3", "t", "b", "/x", PushPort.LEVEL_NORMAL))
                .isInstanceOf(PushPort.PushException.class);

        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.getFirst().status()).isEqualTo(SysNotifyLog.FAILED);
        assertThat(getui.lastClientId).as("找不到 FCM gateway 时不该错发给 GETUI").isNull();
    }

    @Test
    @DisplayName("★★ 真实 gateway 抛异常：先记 FAILED 再抛（原装饰器职责搬到路由）")
    void gatewayFailureRecordedThenRethrown() {
        CapturingWriter writer = new CapturingWriter();
        FakeGateway getui = new FakeGateway(PushProvider.GETUI, false,
                new PushPort.PushException("个推拒绝：cid 失效", false));
        PushRouter router = new PushRouter(List.of(getui), writer);

        assertThatThrownBy(() ->
                router.push(PushProvider.GETUI, "cid-4", "t", "b", "/x", PushPort.LEVEL_RING))
                .isInstanceOf(PushPort.PushException.class);

        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.getFirst().status()).isEqualTo(SysNotifyLog.FAILED);
        assertThat(writer.rows.getFirst().error()).contains("cid 失效");
    }
}
