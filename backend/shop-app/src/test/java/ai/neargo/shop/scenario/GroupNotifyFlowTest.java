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
    /**
     * <b>每个用例一对独立用户号。</b> 原本用固定的 U-GB-1/2 并在 AfterEach 删站内信，
     * 结果踩到一个**生产缺陷**：{@code uk_msg_dedup} 是单列唯一键（不含 deleted），
     * 而 {@code MessageServiceImpl#pushTo} 的查重走 MyBatis-Plus 的逻辑删除过滤 ——
     * 查重看不见软删的行，唯一键看得见，于是「查重说不存在 → insert → 撞唯一键」。
     * 事件因此无限重投，把 {@code sys_outbox.retrying} 顶起来，
     * 最后变红的是别人家的 {@code OpsLinkHealthFlowTest}。
     *
     * <p>这里不再删站内信，也就不制造软删行；缺陷本身另行处理。
     */
    private String u1;
    private String u2;

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
        long seq = System.nanoTime();
        group = "GB-NOTIFY-" + seq;
        u1 = "U-GB-" + seq + "-1";
        u2 = "U-GB-" + seq + "-2";
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
        for (String u : List.of(u1, u2)) {
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
        // **不删站内信** —— 软删会留下占着 uk_msg_dedup 的行（见 u1/u2 的注释）。
        // 用例级唯一的收件人号已经保证了互不干扰。
    }

    @Test
    @DisplayName("★★★ 成团 → 全团都收到，不只是开团人")
    void formedReachesEveryMember() {
        notifier.settled(group, MktGroupBuy.FORMED);
        drain();

        assertThat(inboxOf(u1)).as("开团人没收到").isNotEmpty();
        assertThat(inboxOf(u2)).as("参团人没收到 —— 扇出只发给了一个人").isNotEmpty();
        assertThat(inboxOf(u1).getFirst().getTitle()).isEqualTo("拼团成功");
    }

    @Test
    @DisplayName("★★★ 未成团要把退款说出来 —— 人关心的不是「没成」，是「我的钱呢」")
    void failedSaysRefund() {
        notifier.settled(group, MktGroupBuy.FAILED);
        drain();

        var msg = inboxOf(u1).getFirst();
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
        int first = inboxOf(u1).size();

        // 第 3、第 4 个人付款：成团那条原子 UPDATE 会再走一遍，团照旧是 FORMED
        notifier.settled(group, MktGroupBuy.FORMED);
        notifier.settled(group, MktGroupBuy.FORMED);
        drain();

        assertThat(inboxOf(u1)).as("挂在「状态是 FORMED」上的话，这里会变成 3 条").hasSize(first);
    }

    @Test
    @DisplayName("★★ 没有成员的团：不发，也不报错")
    void emptyGroupIsSilent() {
        memberMapper.delete(Wrappers.<MktGroupMember>lambdaQuery().eq(MktGroupMember::getGroupNo, group));

        notifier.settled(group, MktGroupBuy.FORMED);
        drain();

        assertThat(inboxOf(u1)).isEmpty();
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
