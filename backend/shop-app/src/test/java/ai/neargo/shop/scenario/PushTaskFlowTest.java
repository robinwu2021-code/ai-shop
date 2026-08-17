package ai.neargo.shop.scenario;

import ai.neargo.shop.notify.port.StubPushGateway;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.entity.MsgPushToken;
import ai.neargo.shop.message.entity.NotifyPushTask;
import ai.neargo.shop.message.notify.NotifyPushTaskService;
import ai.neargo.shop.message.notify.PushTokenBinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台营销广播（设计：触达推送中台 · N6）。
 *
 * <p>走真实链路：绑设备 → 建任务(预估) → 下发 → 装了 App 的人收到推送；QUEUED 才可取消。
 */
@SpringBootTest
@ActiveProfiles("test")
class PushTaskFlowTest {

    @Autowired
    private NotifyPushTaskService taskService;
    @Autowired
    private PushTokenBinder binder;
    @Autowired
    private StubPushGateway stub;

    @BeforeEach
    void bindDevice() {
        stub.clear();
        // 造两个「装了 App」的消费者
        binder.register(MsgMessage.RECEIVER_USER, "U-NPT-1", MsgPushToken.APP_ANDROID, "GETUI", "cid-npt-1");
        binder.register(MsgMessage.RECEIVER_USER, "U-NPT-2", MsgPushToken.APP_ANDROID, "GETUI", "cid-npt-2");
    }

    @Test
    @DisplayName("★★★ 建任务预估→下发→装了 App 的人收到；预估≈实发")
    void createEstimateDispatch() {
        NotifyPushTask t = taskService.create("双十一预热", NotifyPushTask.AUD_ALL_APP_USER,
                "活动来了", "点进来看看今天的秒杀", "/pages/activity/1111", null, "ST-OPS");

        assertThat(t.getStatus()).isEqualTo(NotifyPushTask.STATUS_QUEUED);
        assertThat(t.getEstimatedCount()).as("预估≥造的两个 App 用户").isGreaterThanOrEqualTo(2);

        stub.clear();
        int done = taskService.dispatchDue();
        assertThat(done).as("这一轮至少处理了这个任务").isGreaterThanOrEqualTo(1);

        // 装了 App 的两个人都收到了（stub 记下）
        assertThat(stub.sent()).extracting(StubPushGateway.Sent::clientId)
                .contains("cid-npt-1", "cid-npt-2");
        // 营销广播不响铃
        assertThat(stub.sent()).allSatisfy(s -> assertThat(s.level()).isEqualTo("NORMAL"));
    }

    @Test
    @DisplayName("★★ 定时任务未到点不发；QUEUED 才可取消，已完成的取消不了")
    void scheduledNotDueAndCancelRules() {
        NotifyPushTask future = taskService.create("明天的", NotifyPushTask.AUD_ALL_APP_USER,
                "标题", "正文", null,
                java.time.LocalDateTime.now().plusDays(1), "ST-OPS");
        stub.clear();
        taskService.dispatchDue();
        assertThat(stub.sent()).as("未到点的任务这一轮不该发").isEmpty();

        // QUEUED 可取消
        NotifyPushTask cancelled = taskService.cancel(future.getTaskNo(), "ST-OPS");
        assertThat(cancelled.getStatus()).isEqualTo(NotifyPushTask.STATUS_CANCELLED);

        // 已取消的再取消 → 拒绝（只有 QUEUED 能取消）
        assertThatThrownBy(() -> taskService.cancel(future.getTaskNo(), "ST-OPS"))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }
}
