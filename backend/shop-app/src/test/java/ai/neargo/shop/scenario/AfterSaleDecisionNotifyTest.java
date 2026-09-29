package ai.neargo.shop.scenario;

import ai.neargo.shop.message.NotifyScene;
import ai.neargo.shop.spi.trade.OrderEvents;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 售后有结果了要告诉买家（TDD-通知与消息推送 §11）。
 *
 * <p>两件都是**买家正在等的结果**，而此前一条通知都没有：
 * {@code reject} 与 {@code approve} 的退货分支都不发事件。
 * 被驳回的人不知道自己被驳回，该寄回的人不知道要寄 ——
 * 而后者**有时限**，不寄会被 {@code AfterSaleTimeoutJob} 自动关单。
 */
@SpringBootTest
@ActiveProfiles("test")
class AfterSaleDecisionNotifyTest {

    @Autowired
    private ai.neargo.shop.event.OutboxEventBus eventBus;
    @Autowired
    private ai.neargo.shop.event.OutboxDispatcher dispatcher;
    @Autowired
    private ai.neargo.shop.message.mapper.MessageMappers.MessageMapper messageMapper;

    /** 用例级唯一收件人：软删会占着 uk_msg_dedup，所以这里既不复用也不删（见 §10.6） */
    private String user;

    @BeforeEach
    void seed() {
        drain();
        user = "U-AS-" + System.nanoTime();
    }

    @Test
    @DisplayName("★★★ 驳回要把理由带上 —— 只说「被拒了」，买家不知道下一步能做什么")
    void rejectionCarriesTheReason() {
        publish(OrderEvents.AfterSaleDecided.REJECTED, "包装已拆且影响二次销售");

        var msg = inbox().getFirst();
        assertThat(msg.getTitle()).isEqualTo("售后未通过");
        assertThat(msg.getBody())
                .as("没有理由的话，他要么放弃（我们少一次挽回）要么来问客服（多一通电话）")
                .contains("包装已拆且影响二次销售");
    }

    @Test
    @DisplayName("★★ 商家没写理由也要给出下一步，不能只留一句「未通过」")
    void rejectionWithoutReasonStillTellsWhatToDo() {
        publish(OrderEvents.AfterSaleDecided.REJECTED, null);

        assertThat(inbox().getFirst().getBody())
                .as("空理由时要指一条路，而不是让他盯着一句「未通过」")
                .contains("平台介入");
    }

    @Test
    @DisplayName("★★★ 待寄回必须说「有时限」—— 否则买家以为同意了就等着收钱")
    void returnWaitSaysThereIsADeadline() {
        publish(OrderEvents.AfterSaleDecided.RETURN_WAIT, "同意退货");

        var msg = inbox().getFirst();
        assertThat(msg.getTitle()).isEqualTo("请寄回商品");
        assertThat(msg.getBody())
                .as("不寄会被 AfterSaleTimeoutJob 自动关单，而他是在毫不知情的情况下错过的")
                .contains("超时");
        assertThat(msg.getBody()).contains("快递单号");
    }

    @Test
    @DisplayName("★★ 两个场景都登记了 —— 不在 ALL 里的话事件根本不进消费者")
    void scenesAreRegistered() {
        assertThat(NotifyScene.ALL)
                .contains(NotifyScene.AFTER_SALE_REJECTED)
                .contains(NotifyScene.AFTER_SALE_RETURN_WAIT);
    }

    // ------------------------------------------------------------------ helpers

    private void publish(String decision, String remark) {
        eventBus.publish(new OrderEvents.AfterSaleDecided(
                "AS-NOTIFY-" + System.nanoTime(), "SUB-AS-NOTIFY", user, decision, remark));
        drain();
    }

    private void drain() {
        for (int i = 0; i < 20 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
    }

    private List<ai.neargo.shop.message.entity.MsgMessage> inbox() {
        var rows = messageMapper.selectList(Wrappers.<ai.neargo.shop.message.entity.MsgMessage>lambdaQuery()
                .eq(ai.neargo.shop.message.entity.MsgMessage::getReceiverNo, user)
                .orderByDesc(ai.neargo.shop.message.entity.MsgMessage::getId));
        assertThat(rows).as("一条通知都没有 —— 这正是此前的状态").isNotEmpty();
        return rows;
    }
}
