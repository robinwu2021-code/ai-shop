package ai.neargo.shop.message;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.entity.MsgSceneChannel;
import ai.neargo.shop.message.notify.SceneChannelRouting;
import ai.neargo.shop.message.notify.WxSubscribeSender;
import ai.neargo.shop.spi.user.MerchantStaffPort;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

/**
 * 业务事件 → 三端触达（原 {@code OrderEventConsumer}，二期扩为三端）。
 *
 * <p>只发收件人**必须知道**的事。C 端：钱扣了、货到了、钱退了；
 * B 端：来单了、有售后、有评价 —— 每多一条可有可无的通知，
 * 「到货了去取」「来新订单了」这两条最重要的就更容易被划走。
 *
 * <p><b>站内信必发，订阅消息尽力</b>：站内信是事实记录（落 {@code msg_message}），
 * 订阅消息是把人从微信里拉回来的加速通道，{@link WxSubscribeSender} 内部消化失败。
 *
 * <p>幂等：C 端单收件人 {@code dedupKey = eventNo}；B 端扇出给多个员工，
 * {@code dedupKey = eventNo + ":" + userNo} —— dedup 唯一索引是全局的，
 * 不带收件人的话第二个员工会被当成重投静默丢掉。
 */
@Component
public class NotificationConsumer implements OutboxConsumer {

    private static final Set<String> HANDLED = Set.of(
            "ORDER_PAID", "ORDER_ARRIVED", "SUB_ORDER_COMPLETED", "AFTER_SALE_REFUNDED",
            "SUB_ORDER_PAID", "AFTER_SALE_APPLIED", "REVIEW_CREATED");

    /** 处理的场景码 —— 场景×通道种子必须逐一覆盖，{@code SceneChannelSeedTest} 据此守卫。 */
    public static Set<String> handledScenes() {
        return HANDLED;
    }

    /** 该被「来单/售后」提醒吵到的人：站柜台的和管店的。理货/配送收到也做不了什么。 */
    private static final Set<String> ORDER_ROLES =
            Set.of(MerchantStaffPort.ROLE_MANAGER, MerchantStaffPort.ROLE_CLERK);
    private static final Set<String> AFTER_SALE_ROLES =
            Set.of(MerchantStaffPort.ROLE_MANAGER, MerchantStaffPort.ROLE_CS);
    /** 评价给店主 + 商家客服（回评价是客服的活），别吵到站柜台的。 */
    private static final Set<String> REVIEW_ROLES = Set.of(MerchantStaffPort.ROLE_CS);

    private final MessageService messageService;
    private final WxSubscribeSender wxSender;
    private final ai.neargo.shop.message.notify.PushSender pushSender;
    private final MerchantStaffPort merchantStaffPort;
    private final SceneChannelRouting routing;
    private final ObjectMapper json;

    public NotificationConsumer(MessageService messageService, WxSubscribeSender wxSender,
                                ai.neargo.shop.message.notify.PushSender pushSender,
                                MerchantStaffPort merchantStaffPort,
                                SceneChannelRouting routing, ObjectMapper json) {
        this.messageService = messageService;
        this.wxSender = wxSender;
        this.pushSender = pushSender;
        this.merchantStaffPort = merchantStaffPort;
        this.routing = routing;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) {
        return HANDLED.contains(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        JsonNode payload = json.readTree(event.getPayload());
        String scene = event.getEventType();
        switch (scene) {
            // ------------------------------------------------------------ C 端
            case "ORDER_PAID" -> {
                String userNo = text(payload, "userNo");
                String link = "/pages/order/index?orderNo=" + event.getAggregateId();
                messageService.push(userNo, MessageService.TRADE,
                        "支付成功", "订单已支付，商家备货后可凭取货码到店自提",
                        link, event.getEventNo());
                cPush(scene, userNo, "支付成功", "订单已支付，商家备货后可凭取货码到店自提", link);
            }
            case "ORDER_ARRIVED" -> {
                String userNo = text(payload, "userNo");
                int count = payload.get("subOrderNos") == null ? 1 : payload.get("subOrderNos").size();
                // 一人多单时点开落到订单列表；单单直达详情
                String link = count == 1
                        ? "/pages/order/index?orderNo=" + event.getAggregateId()
                        : "/pages/orders/index";
                String arrivedBody = count == 1
                        ? "您的包裹已到自提点，请凭取货码取货"
                        : "您的 " + count + " 件包裹已到自提点，请凭取货码取货";
                messageService.push(userNo, MessageService.TRADE, "到货了", arrivedBody,
                        link, event.getEventNo());
                // 微信 page 路径不带前导斜杠；站内信 link 带 —— 两端各按各的约定
                if (routing.enabled(scene, MsgSceneChannel.AUD_C_USER, MsgSceneChannel.CH_WXSUB)) {
                    wxSender.orderArrived(userNo, count, link.substring(1));
                }
                // 到货是 C 端最重要的一条；级别由配置决定（默认 NORMAL，不把买家从睡梦中叫醒）
                cPush(scene, userNo, "到货了", arrivedBody, link);
            }
            case "SUB_ORDER_COMPLETED" -> {
                String userNo = text(payload, "userNo");
                String link = "/pages/order/index?orderNo=" + event.getAggregateId();
                messageService.push(userNo, MessageService.TRADE,
                        "已取货", "订单已完成，欢迎评价", link, event.getEventNo());
                cPush(scene, userNo, "已取货", "订单已完成，欢迎评价", link);
            }
            case "AFTER_SALE_REFUNDED" -> {
                String userNo = text(payload, "userNo");
                String link = "/pages/after-sale/index?afterSaleNo=" + event.getAggregateId();
                messageService.push(userNo, MessageService.TRADE,
                        "退款已处理", "退款将原路退回，到账时间以支付渠道为准",
                        link, event.getEventNo());
                if (routing.enabled(scene, MsgSceneChannel.AUD_C_USER, MsgSceneChannel.CH_WXSUB)) {
                    long refundMinor = payload.get("refundMinor") == null
                            ? 0L : payload.get("refundMinor").asLong();
                    wxSender.refunded(userNo, "%.2f元".formatted(refundMinor / 100.0), link.substring(1));
                }
                cPush(scene, userNo, "退款已处理", "退款将原路退回，到账时间以支付渠道为准", link);
            }
            // ------------------------------------------------------------ B 端
            case "SUB_ORDER_PAID" -> fanOutToStaff(event, text(payload, "entityNo"), ORDER_ROLES,
                    "新订单", "有新的订单待备货，记得按时送到自提点",
                    "/pages/orders/index?tab=PAID");
            case "AFTER_SALE_APPLIED" -> fanOutToStaff(event, text(payload, "entityNo"), AFTER_SALE_ROLES,
                    "新的售后申请", "买家提交了售后申请，尽早处理更容易协商解决",
                    "/pages/after-sale/index");
            case "REVIEW_CREATED" -> {
                int rating = payload.get("rating") == null ? 5 : payload.get("rating").asInt();
                // 差评单独点名：混在普通评价里会被当成例行夸奖划掉
                fanOutToStaff(event, text(payload, "entityNo"), REVIEW_ROLES,
                        rating <= 2 ? "收到差评" : "收到新评价",
                        rating <= 2 ? "有一条 " + rating + " 星评价，回复得当能挽回大多数顾客"
                                : "有顾客发表了新评价",
                        "/pages/reviews/index");
            }
            default -> {
                // supports() 已经过滤，走到这里说明两处不一致 —— 什么都不做比乱发消息强
            }
        }
    }

    /**
     * C 端 App 推送：仅当运营为该场景开了 PUSH 通道时发，级别由配置决定。
     * 站内信在各 case 里已必发，这里只是加速通道。
     */
    private void cPush(String scene, String userNo, String title, String body, String link) {
        if (!routing.enabled(scene, MsgSceneChannel.AUD_C_USER, MsgSceneChannel.CH_PUSH)) {
            return;
        }
        if (MsgSceneChannel.LEVEL_RING.equals(routing.pushLevel(scene, MsgSceneChannel.AUD_C_USER))) {
            pushSender.ring(MsgMessage.RECEIVER_USER, userNo, title, body, link);
        } else {
            pushSender.notify(MsgMessage.RECEIVER_USER, userNo, title, body, link);
        }
    }

    /**
     * B 端扇出：店主 + 持角色员工，dedupKey 带收件人。受众为空就静默作罢（店还没配人）。
     *
     * <p>站内信必发；App 推送与级别（NORMAL/RING）由运营的场景×通道配置决定 ——
     * 「新订单响铃、其余常规」这条规则从硬编码搬进了 {@code msg_scene_channel}。
     */
    private void fanOutToStaff(SysOutbox event, String entityNo, Set<String> roles,
                               String title, String body, String link) {
        String scene = event.getEventType();
        boolean push = routing.enabled(scene, MsgSceneChannel.AUD_B_STAFF, MsgSceneChannel.CH_PUSH);
        boolean ring = push
                && MsgSceneChannel.LEVEL_RING.equals(routing.pushLevel(scene, MsgSceneChannel.AUD_B_STAFF));
        List<String> userNos = merchantStaffPort.staffUserNos(entityNo, roles);
        for (String userNo : userNos) {
            messageService.pushTo(MsgMessage.RECEIVER_STAFF, userNo, MessageService.TRADE,
                    title, body, link, event.getEventNo() + ":" + userNo);
            if (!push) {
                continue;
            }
            if (ring) {
                pushSender.ring(MsgMessage.RECEIVER_STAFF, userNo, title, body, link);
            } else {
                pushSender.notify(MsgMessage.RECEIVER_STAFF, userNo, title, body, link);
            }
        }
    }

    private String text(JsonNode payload, String field) {
        JsonNode node = payload == null ? null : payload.get(field);
        return node == null ? null : node.asString();
    }
}
