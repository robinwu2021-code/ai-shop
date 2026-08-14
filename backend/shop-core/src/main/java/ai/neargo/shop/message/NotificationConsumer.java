package ai.neargo.shop.message;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.message.entity.MsgMessage;
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
    private final ObjectMapper json;

    public NotificationConsumer(MessageService messageService, WxSubscribeSender wxSender,
                                ai.neargo.shop.message.notify.PushSender pushSender,
                                MerchantStaffPort merchantStaffPort, ObjectMapper json) {
        this.messageService = messageService;
        this.wxSender = wxSender;
        this.pushSender = pushSender;
        this.merchantStaffPort = merchantStaffPort;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) {
        return HANDLED.contains(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        JsonNode payload = json.readTree(event.getPayload());
        switch (event.getEventType()) {
            // ------------------------------------------------------------ C 端
            case "ORDER_PAID" -> messageService.push(
                    text(payload, "userNo"), MessageService.TRADE,
                    "支付成功", "订单已支付，商家备货后可凭取货码到店自提",
                    "/pages/order/index?orderNo=" + event.getAggregateId(),
                    event.getEventNo());
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
                wxSender.orderArrived(userNo, count, link.substring(1));
                // 到货是 C 端最重要的一条，但不响铃：买家不需要被从睡梦中叫醒去取货
                pushSender.notify(MsgMessage.RECEIVER_USER, userNo, "到货了", arrivedBody, link);
            }
            case "SUB_ORDER_COMPLETED" -> messageService.push(
                    text(payload, "userNo"), MessageService.TRADE,
                    "已取货", "订单已完成，欢迎评价",
                    "/pages/order/index?orderNo=" + event.getAggregateId(),
                    event.getEventNo());
            case "AFTER_SALE_REFUNDED" -> {
                String userNo = text(payload, "userNo");
                String link = "/pages/after-sale/index?afterSaleNo=" + event.getAggregateId();
                messageService.push(userNo, MessageService.TRADE,
                        "退款已处理", "退款将原路退回，到账时间以支付渠道为准",
                        link, event.getEventNo());
                long refundMinor = payload.get("refundMinor") == null
                        ? 0L : payload.get("refundMinor").asLong();
                wxSender.refunded(userNo, "%.2f元".formatted(refundMinor / 100.0), link.substring(1));
            }
            // ------------------------------------------------------------ B 端
            // 新订单是**唯一响铃**的一条：商家不盯屏幕也要知道来单了（B-N-1）。
            // 每条都响等于没有响 —— 其余经营事件一律常规级
            case "SUB_ORDER_PAID" -> fanOutToStaff(event, text(payload, "entityNo"), ORDER_ROLES,
                    "新订单", "有新的订单待备货，记得按时送到自提点",
                    "/pages/orders/index?tab=PAID", true);
            case "AFTER_SALE_APPLIED" -> fanOutToStaff(event, text(payload, "entityNo"), AFTER_SALE_ROLES,
                    "新的售后申请", "买家提交了售后申请，尽早处理更容易协商解决",
                    "/pages/after-sale/index", false);
            case "REVIEW_CREATED" -> {
                int rating = payload.get("rating") == null ? 5 : payload.get("rating").asInt();
                // 差评单独点名：混在普通评价里会被当成例行夸奖划掉
                fanOutToStaff(event, text(payload, "entityNo"), REVIEW_ROLES,
                        rating <= 2 ? "收到差评" : "收到新评价",
                        rating <= 2 ? "有一条 " + rating + " 星评价，回复得当能挽回大多数顾客"
                                : "有顾客发表了新评价",
                        "/pages/reviews/index", false);
            }
            default -> {
                // supports() 已经过滤，走到这里说明两处不一致 —— 什么都不做比乱发消息强
            }
        }
    }

    /**
     * B 端扇出：店主 + 持角色员工，dedupKey 带收件人。受众为空就静默作罢（店还没配人）。
     *
     * @param ring 是否响铃级推送。只有「新订单」是 true
     */
    private void fanOutToStaff(SysOutbox event, String entityNo, Set<String> roles,
                               String title, String body, String link, boolean ring) {
        List<String> userNos = merchantStaffPort.staffUserNos(entityNo, roles);
        for (String userNo : userNos) {
            messageService.pushTo(MsgMessage.RECEIVER_STAFF, userNo, MessageService.TRADE,
                    title, body, link, event.getEventNo() + ":" + userNo);
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
