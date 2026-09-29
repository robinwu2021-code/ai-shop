package ai.neargo.shop.scenario;

import ai.neargo.shop.marketing.group.entity.MktGroupBuy;
import ai.neargo.shop.marketing.group.entity.MktGroupMember;
import ai.neargo.shop.message.NotifyScene;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 团有结果了要通知全团（TDD-通知与消息推送 §10）。
 *
 * <p><b>拼团是唯一「开团人必须离开去等结果」的玩法</b> —— 他把链接转出去就退出小程序了，
 * 团成没成、钱退没退，此前只能自己想起来回去看：成团那条原子 UPDATE 与
 * {@code GroupExpireJob} 都不发事件，连站内信都没有（2026-09-29 查证）。
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupNotifyFlowTest {

    /**
     * <b>每个用例一个团号。</b> mapper 的 delete 是逻辑删除（deleted=1），
     * 而 {@code uk_group_no} 是**不带 deleted 的**唯一键 —— 清理过的行照样占着号，
     * 下一个用例插同一个号就撞。这种失败报的是「唯一键冲突」，
     * 与被测的东西毫无关系，很容易被当成「测试不稳」。
     */
    private String group;
    private static final String U1 = "U-GB-1";
    private static final String U2 = "U-GB-2";

    @Autowired
    private ai.neargo.shop.marketing.group.GroupSettleNotifier notifier;
    @Autowired
    private ai.neargo.shop.event.OutboxDispatcher dispatcher;
    @Autowired
    private ai.neargo.shop.marketing.group.mapper.GroupMappers.GroupBuyMapper groupMapper;
    @Autowired
    private ai.neargo.shop.marketing.group.mapper.GroupMappers.GroupMemberMapper memberMapper;
    @Autowired
    private ai.neargo.shop.message.mapper.MessageMappers.MessageMapper messageMapper;

    @BeforeEach
    void seed() {
        drain();
        group = "GB-NOTIFY-" + System.nanoTime();
        MktGroupBuy g = new MktGroupBuy();
        g.setGroupNo(group);
        g.setTitle("阳光玫瑰青提 3 人团");
        g.setGoodsNo("G-GB-NOTIFY");
        g.setEntityNo("M-GB-NOTIFY");
        g.setGroupPriceMinor(3990L);
        g.setStatus(MktGroupBuy.OPEN);
        g.setMinCount(2);
        g.setJoinedCount(2);
        g.setEndAt(System.currentTimeMillis() + 3600_000L);
        groupMapper.insert(g);
        for (String u : List.of(U1, U2)) {
            MktGroupMember m = new MktGroupMember();
            m.setGroupNo(group);
            m.setUserNo(u);
            m.setJoinedAt(System.currentTimeMillis());
            memberMapper.insert(m);
        }
    }

    @AfterEach
    void cleanUp() {
        /*
         * **先把自己的事件投完，再删数据。**
         *
         * 顺序反了会留下一个跨用例的坑：站内信的 delete 是**逻辑删除**，
         * 而 {@code uk_msg_dedup} 是不带 deleted 的唯一键 —— 删过的行照样占着 dedupKey。
         * 于是本用例没投完的事件会在**下一个用例**的 drain 里被投递，
         * 撞上那条还占着的 dedup 行，表现是 outbox 反复 retry。
         *
         * 它不在本类里报错，而是把 `sys_outbox.retrying` 顶起来 ——
         * 真正变红的是别人家的 {@code OpsLinkHealthFlowTest}（它判「投递任务是不是停了」，
         * 而 retrying 非 0 会先落到 CONSUMER_FAILING）。跨类的假失败就是这么来的。
         */
        drain();
        if (group != null) {
            memberMapper.delete(Wrappers.<MktGroupMember>lambdaQuery().eq(MktGroupMember::getGroupNo, group));
            groupMapper.delete(Wrappers.<MktGroupBuy>lambdaQuery().eq(MktGroupBuy::getGroupNo, group));
        }
        for (String u : List.of(U1, U2)) {
            messageMapper.delete(Wrappers.<ai.neargo.shop.message.entity.MsgMessage>lambdaQuery()
                    .eq(ai.neargo.shop.message.entity.MsgMessage::getReceiverNo, u));
        }
    }

    @Test
    @DisplayName("★★★ 成团 → 全团都收到，不只是开团人")
    void formedReachesEveryMember() {
        notifier.settled(group, MktGroupBuy.FORMED);
        drain();

        assertThat(inboxOf(U1)).as("开团人没收到").isNotEmpty();
        assertThat(inboxOf(U2)).as("参团人没收到 —— 扇出只发给了一个人").isNotEmpty();
        assertThat(inboxOf(U1).getFirst().getTitle()).isEqualTo("拼团成功");
    }

    @Test
    @DisplayName("★★★ 未成团要把退款说出来 —— 人关心的不是「没成」，是「我的钱呢」")
    void failedSaysRefund() {
        notifier.settled(group, MktGroupBuy.FAILED);
        drain();

        var msg = inboxOf(U1).getFirst();
        assertThat(msg.getTitle()).isEqualTo("拼团未成团");
        assertThat(msg.getBody())
                .as("不说退款的话他会来问客服，而答案本来就该写在通知里")
                .contains("退回");
    }

    @Test
    @DisplayName("★★★ 后付的人不该让全团再收一遍 —— 幂等挂在 notified_at 上")
    void secondCallDoesNotNotifyAgain() {
        notifier.settled(group, MktGroupBuy.FORMED);
        drain();
        int first = inboxOf(U1).size();

        // 第 3、第 4 个人付款：成团那条原子 UPDATE 会再走一遍，团照旧是 FORMED
        notifier.settled(group, MktGroupBuy.FORMED);
        notifier.settled(group, MktGroupBuy.FORMED);
        drain();

        assertThat(inboxOf(U1)).as("挂在「状态是 FORMED」上的话，这里会变成 3 条").hasSize(first);
    }

    @Test
    @DisplayName("★★ 没有成员的团：不发，也不报错")
    void emptyGroupIsSilent() {
        memberMapper.delete(Wrappers.<MktGroupMember>lambdaQuery().eq(MktGroupMember::getGroupNo, group));

        notifier.settled(group, MktGroupBuy.FORMED);
        drain();

        assertThat(inboxOf(U1)).isEmpty();
    }

    @Test
    @DisplayName("★★ 两个场景都登记在 NotifyScene.ALL 里 —— 不在的话事件根本不进消费者")
    void scenesAreRegistered() {
        assertThat(NotifyScene.ALL)
                .contains(NotifyScene.GROUP_FORMED)
                .contains(NotifyScene.GROUP_FAILED);
    }

    // ------------------------------------------------------------------ helpers

    private void drain() {
        for (int i = 0; i < 20 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
    }

    private List<ai.neargo.shop.message.entity.MsgMessage> inboxOf(String userNo) {
        return messageMapper.selectList(Wrappers.<ai.neargo.shop.message.entity.MsgMessage>lambdaQuery()
                .eq(ai.neargo.shop.message.entity.MsgMessage::getReceiverNo, userNo)
                .orderByDesc(ai.neargo.shop.message.entity.MsgMessage::getId));
    }
}
