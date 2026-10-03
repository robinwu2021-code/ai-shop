package ai.neargo.shop.scenario;

import ai.neargo.shop.message.NotifyScene;
import ai.neargo.shop.spi.trade.OrderEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 履约链上的通知（TDD-通知与消息推送 §8.8）。
 *
 * <p><b>为什么要有这一批</b>：{@code ship} 与 {@code delivered} 此前**连事件都不发**，
 * 于是商家配送与快递这两条链，买家从下单到收货一条消息都收不到 ——
 * 而 2026-09-29 查证，线上真实成交全走商家配送，自提零使用。
 * 有通知的那条恰恰没人用。
 *
 * <p>这里测的是**说的话对不对**，不是「有没有发」：三条链走到终态的方式完全不同，
 * 共用一句「已取货」的话，收到快递的人会以为自己去过某个自提点。
 */
@SpringBootTest
@ActiveProfiles("test")
class ShipNotifyFlowTest {

    @Autowired
    private ai.neargo.shop.event.OutboxEventBus eventBus;
    @Autowired
    private ai.neargo.shop.event.OutboxDispatcher dispatcher;
    @Autowired
    private ai.neargo.shop.message.mapper.MessageMappers.MessageMapper messageMapper;

    private static final String USER = "U-SHIPNOTIFY";

    @BeforeEach
    void drain() {
        for (int i = 0; i < 50 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
        messageMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.message.entity.MsgMessage>lambdaQuery()
                .eq(ai.neargo.shop.message.entity.MsgMessage::getReceiverNo, USER));
    }

    @Test
    @DisplayName("★★★ 快递发货 → 站内信带上快递公司与单号（没有单号等于什么都没说）")
    void expressShipCarriesTrackingNumber() {
        publishShipped("SUB-SHIP-1", "EXPRESS", "SF", "SF1234567890");

        var msgs = inboxOf(USER);
        assertThat(msgs).as("发货一条通知都没有 —— 这正是此前的状态").isNotEmpty();
        assertThat(msgs.getFirst().getTitle()).isEqualTo("已发货");
        assertThat(msgs.getFirst().getBody())
                .as("**单号是这条通知的全部价值**，没有它「已发货」只说了买家本来就在等的事")
                .contains("SF1234567890").contains("SF");
    }

    @Test
    @DisplayName("★★★ 商家自送没有单号 → 说的是「开始配送」，不是一条缺了单号的「已发货」")
    void merchantDeliverySaysOnTheWay() {
        publishShipped("SUB-SHIP-2", "MERCHANT_DELIVERY", null, null);

        var msgs = inboxOf(USER);
        assertThat(msgs).isNotEmpty();
        assertThat(msgs.getFirst().getTitle()).isEqualTo("开始配送");
        assertThat(msgs.getFirst().getBody())
                .as("自送那条的价值在时间 —— 他要在家")
                .contains("电话");
    }

    @Test
    @DisplayName("★★★ 走到终态的说法按履约方式分 —— 收快递的人没去过任何自提点")
    void completionWordingDependsOnFulfillment() {
        publishCompleted("SUB-DONE-1", "STORE_PICKUP");
        assertThat(inboxOf(USER).getFirst().getTitle()).isEqualTo("已取货");

        drain();
        publishCompleted("SUB-DONE-2", "MERCHANT_DELIVERY");
        assertThat(inboxOf(USER).getFirst().getTitle()).isEqualTo("已送达");

        drain();
        publishCompleted("SUB-DONE-3", "EXPRESS");
        assertThat(inboxOf(USER).getFirst().getTitle()).isEqualTo("已签收");
    }

    @Test
    @DisplayName("★★ 不认识的履约方式回落成中性说法，不硬套自提")
    void unknownFulfillmentFallsBackToNeutral() {
        publishCompleted("SUB-DONE-4", "SOMETHING_NEW");

        assertThat(inboxOf(USER).getFirst().getTitle())
                .as("回落成「已取货」的话，新履约方式一上线就在对用户说错话")
                .isEqualTo("订单已完成");
    }

    @Test
    @DisplayName("★★ 新场景必须在 NotifyScene.ALL 里 —— 不在的话事件根本不进消费者")
    void sceneIsRegistered() {
        assertThat(NotifyScene.ALL).contains(NotifyScene.SUB_ORDER_SHIPPED);
    }

    // ------------------------------------------------------------------ helpers

    private void publishShipped(String subNo, String fulfillment, String company, String no) {
        eventBus.publish(new OrderEvents.SubOrderShipped(
                subNo, "SO-SHIPNOTIFY", "M-SHIPNOTIFY", USER, fulfillment, company, no));
        drainOnce();
    }

    private void publishCompleted(String subNo, String fulfillment) {
        eventBus.publish(new OrderEvents.SubOrderCompleted(
                subNo, "SO-SHIPNOTIFY", "M-SHIPNOTIFY", USER, fulfillment));
        drainOnce();
    }

    private void drainOnce() {
        for (int i = 0; i < 10 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
    }

    private List<ai.neargo.shop.message.entity.MsgMessage> inboxOf(String userNo) {
        return messageMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.message.entity.MsgMessage>lambdaQuery()
                .eq(ai.neargo.shop.message.entity.MsgMessage::getReceiverNo, userNo)
                .orderByDesc(ai.neargo.shop.message.entity.MsgMessage::getId));
    }
}
