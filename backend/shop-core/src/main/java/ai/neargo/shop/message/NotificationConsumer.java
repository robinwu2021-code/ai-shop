package ai.neargo.shop.message;

import ai.neargo.shop.common.ExpressCompanies;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.entity.MsgSceneChannel;
import ai.neargo.shop.message.notify.SceneChannelRouting;
import ai.neargo.shop.message.notify.WxSubscribeSender;
import ai.neargo.shop.spi.notify.WxSubscribePort;
import ai.neargo.shop.spi.trade.SubOrderBuyerPort;
import ai.neargo.shop.spi.user.MerchantStaffPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>站内信必发，订阅消息尽力</b>：站内信是事实记录（落 {@code notify_message}），
 * 订阅消息是把人从微信里拉回来的加速通道，{@link WxSubscribeSender} 内部消化失败。
 *
 * <p>幂等：C 端单收件人 {@code dedupKey = eventNo}；B 端扇出给多个员工，
 * {@code dedupKey = eventNo + ":" + userNo} —— dedup 唯一索引是全局的，
 * 不带收件人的话第二个员工会被当成重投静默丢掉。
 */
@Component
public class NotificationConsumer implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    /** 处理的场景码 —— 场景×通道种子必须逐一覆盖，{@code SceneChannelSeedTest} 据此守卫。 */
    public static Set<String> handledScenes() {
        return NotifyScene.ALL;
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
    private final ai.neargo.shop.spi.user.StoreFavoritePort storeFavoritePort;
    private final SceneChannelRouting routing;
    private final ObjectMapper json;
    private final SubOrderBuyerPort buyerPort;

    public NotificationConsumer(MessageService messageService, WxSubscribeSender wxSender,
                                ai.neargo.shop.message.notify.PushSender pushSender,
                                MerchantStaffPort merchantStaffPort,
                                ai.neargo.shop.spi.user.StoreFavoritePort storeFavoritePort,
                                SceneChannelRouting routing, ObjectMapper json,
                                SubOrderBuyerPort buyerPort) {
        this.messageService = messageService;
        this.wxSender = wxSender;
        this.pushSender = pushSender;
        this.merchantStaffPort = merchantStaffPort;
        this.storeFavoritePort = storeFavoritePort;
        this.routing = routing;
        this.json = json;
        this.buyerPort = buyerPort;
    }

    @Override
    public boolean supports(String eventType) {
        return NotifyScene.ALL.contains(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        JsonNode payload = json.readTree(event.getPayload());
        String scene = event.getEventType();
        switch (scene) {
            // ------------------------------------------------------------ C 端
            case NotifyScene.ORDER_PAID -> {
                String userNo = text(payload, "userNo");
                String link = "/pages/order/index?orderNo=" + event.getAggregateId();
                messageService.push(userNo, MessageService.TRADE,
                        "支付成功", "订单已支付，商家备货后可凭取货码到店自提",
                        link, event.getEventNo());
                cPush(scene, userNo, "支付成功", "订单已支付，商家备货后可凭取货码到店自提", link);
            }
            case NotifyScene.ORDER_ARRIVED -> {
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
            case NotifyScene.SUB_ORDER_COMPLETED -> {
                String userNo = text(payload, "userNo");
                String link = "/pages/order/index?orderNo=" + event.getAggregateId();
                /*
                 * **文案按履约方式分。** 三条链走到终态的方式完全不同：
                 * 自提是他自己去拿的，配送是送到他手上的，快递是签收的。
                 * 共用一句「已取货」的话，收到快递的人会以为自己去过某个自提点 ——
                 * 这正是「新履约方式落进老分支」那类缺陷：不报错，只是说错话。
                 */
                String doneTitle = doneTitleOf(text(payload, "fulfillment"));
                messageService.push(userNo, MessageService.TRADE,
                        doneTitle, "订单已完成，欢迎评价", link, event.getEventNo());
                cPush(scene, userNo, doneTitle, "订单已完成，欢迎评价", link);
            }
            case NotifyScene.SUB_ORDER_SHIPPED -> {
                String userNo = text(payload, "userNo");
                String link = "/pages/order/index?orderNo=" + text(payload, "orderNo");
                String expressNo = text(payload, "expressNo");
                /*
                 * **快递单号是这条通知的全部价值**：没有它，「已发货」只说了一件
                 * 买家本来就在等的事。自送没有单号，说的是「正在送」——
                 * 那一条的价值在时间（他要在家）。
                 */
                boolean byExpress = expressNo != null && !expressNo.isBlank();
                String title = byExpress ? "已发货" : "开始配送";
                String body = byExpress
                        ? "%s %s，可在订单里查看物流".formatted(
                                nz(text(payload, "expressCompany"), "快递"), expressNo)
                        : "商家已出发，请保持电话畅通";
                messageService.push(userNo, MessageService.TRADE, title, body,
                        link, event.getEventNo());
                cPush(scene, userNo, title, body, link);
            }
            case NotifyScene.WAYBILL_PROGRESSED -> waybill(event, payload, text(payload, "status"));
            case NotifyScene.WAYBILL_SIGNED -> waybill(event, payload, "DELIVERED");
            case NotifyScene.AFTER_SALE_REFUNDED -> {
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
            case NotifyScene.NEW_GOODS_ON_SALE -> fanOutToFollowers(event, payload);
            case NotifyScene.AFTER_SALE_REJECTED -> {
                String userNo = text(payload, "userNo");
                String link = "/pages/after-sale/index?afterSaleNo=" + event.getAggregateId();
                /*
                 * **把理由带上。** 只说「被拒了」，买家不知道下一步能做什么 ——
                 * 他要么放弃（我们少了一次挽回），要么来问客服（多一通电话）。
                 */
                String why = text(payload, "remark");
                String body = why == null || why.isBlank()
                        ? "商家未同意本次申请，可在售后详情里申请平台介入"
                        : "商家说明：" + why;
                messageService.push(userNo, MessageService.TRADE, "售后未通过", body,
                        link, event.getEventNo());
                cPush(scene, userNo, "售后未通过", body, link);
            }
            case NotifyScene.AFTER_SALE_RETURN_WAIT -> {
                String userNo = text(payload, "userNo");
                String link = "/pages/after-sale/index?afterSaleNo=" + event.getAggregateId();
                /*
                 * **要说「有时限」**：不寄回会被 AfterSaleTimeoutJob 自动关单，
                 * 而买家会以为「同意了就等着收钱」。
                 */
                String body = "商家已同意退货，请尽快寄回并填写快递单号，超时申请会自动关闭";
                messageService.push(userNo, MessageService.TRADE, "请寄回商品", body,
                        link, event.getEventNo());
                cPush(scene, userNo, "请寄回商品", body, link);
            }
            case NotifyScene.GROUP_FORMED -> fanOutToGroup(event, payload, true);
            case NotifyScene.GROUP_FAILED -> fanOutToGroup(event, payload, false);
            // ------------------------------------------------------------ B 端
            case NotifyScene.SUB_ORDER_PAID -> fanOutToStaff(event, text(payload, "entityNo"), ORDER_ROLES,
                    "新订单", "有新的订单待备货，记得按时送到自提点",
                    "/pages/orders/index?tab=PAID");
            case NotifyScene.AFTER_SALE_APPLIED -> fanOutToStaff(event, text(payload, "entityNo"), AFTER_SALE_ROLES,
                    "新的售后申请", "买家提交了售后申请，尽早处理更容易协商解决",
                    "/pages/after-sale/index");
            case NotifyScene.REVIEW_CREATED -> {
                int rating = payload.get("rating") == null ? 5 : payload.get("rating").asInt();
                // 差评单独点名：混在普通评价里会被当成例行夸奖划掉
                fanOutToStaff(event, text(payload, "entityNo"), REVIEW_ROLES,
                        rating <= 2 ? "收到差评" : "收到新评价",
                        rating <= 2 ? "有一条 " + rating + " 星评价，回复得当能挽回大多数顾客"
                                : "有顾客发表了新评价",
                        "/pages/reviews/index");
            }
            default -> {
                /*
                 * 共用一组常量消掉的是**拼写**不一致，消不掉**遗漏**：
                 * 往 {@link NotifyScene#ALL} 加一个成员却忘了在这里加分支，
                 * `supports()` 照样放行，事件进来之后落到这里 —— 什么都不做，零报错。
                 * （上一版这里写的是「走不到」，把范围说宽了。）
                 *
                 * 所以这一支有两层用途，缺一不可：
                 *   · 兜底日志 —— 真漏了的时候至少线上有一行指名道姓的记录；
                 *   · 而「别漏」本身由 {@code NotifySceneCoverageTest} 在测试期拦下：
                 *     ALL 里的每个成员都必须在本 switch 里有一个 case。
                 *     日志是最后一道，不是唯一一道。
                 */
                log.warn("[notify] 未登记的场景码 {}，事件 {} 未处理 —— 去 NotifyScene 里补",
                        scene, event.getEventNo());
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
     * 「新订单响铃、其余常规」这条规则从硬编码搬进了 {@code notify_scene_channel}。
     */
    /**
     * 新品开售 → 扇出给该店收藏者（TDD-C 端裂变与商家招募 §10）。
     *
     * <p><b>与 {@link #fanOutToStaff} 的区别不只是收件人</b>：
     * 那条是「必须知道的事」，站内信必发；这条是收藏者的一次**预约**，
     * 站内信默认关着（V356 种子 {@code INAPP enabled=0}）——
     * 上新塞进消息中心会稀释「到货了去取」那几条。
     *
     * <p><b>dedupKey 必须带 userNo</b>：dedup 唯一索引是全局的，
     * 不带的话第二个收藏者会被当成重投静默丢掉（同 fanOutToStaff 的注释）。
     */
    /**
     * 团有结果了 → 扇出给全团。
     *
     * <p><b>名单来自事件本身</b>（团规模有界），不另开跨域查询。
     *
     * <p><b>dedupKey 必须带 userNo</b>：dedup 唯一索引是全局的，
     * 不带的话第二个团员会被当成重投静默丢掉（同 {@link #fanOutToStaff}）。
     */
    private void fanOutToGroup(SysOutbox event, JsonNode payload, boolean formed) {
        String scene = event.getEventType();
        var arr = payload.get("userNos");
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return;   // 没有名单就没人可通知 —— 不是错误
        }
        String title = nz(text(payload, "title"), "拼团");
        String link = "/pages/group/index?groupNo=" + event.getAggregateId();
        String msgTitle = formed ? "拼团成功" : "拼团未成团";
        /*
         * **未成团那条要把退款说出来**。人关心的不是「没成」，是「我的钱呢」——
         * 不说的话他会来问客服，而答案本来就该写在通知里。
         */
        String body = formed
                ? "「%s」已成团，等商家发货".formatted(title)
                : "「%s」人数没凑够，货款将原路退回".formatted(title);
        boolean inapp = routing.enabled(scene, MsgSceneChannel.AUD_C_USER, MsgSceneChannel.CH_INAPP);
        for (JsonNode n : arr) {
            String userNo = n.asString();
            if (userNo == null || userNo.isBlank()) {
                continue;
            }
            if (inapp) {
                messageService.push(userNo, MessageService.TRADE, msgTitle, body,
                        link, event.getEventNo() + ":" + userNo);
            }
            cPush(scene, userNo, msgTitle, body, link);
        }
        log.info("[notify] 团结果扇出 group={} 结果={} 人数={}",
                event.getAggregateId(), formed ? "成团" : "未成团", arr.size());
    }

    /**
     * 快递节点 → 买家（TDD-物流模块 批 4）。
     *
     * <p><b>只认线下付款单</b>（{@code profile = SELF}）：微信支付单的物流动态微信自己推。
     * 揽收那一档不发站内信与推送（发货时 {@code SUB_ORDER_SHIPPED} 说过了），只走订阅消息 ——
     * 那是线下单在微信里唯一能收到的一条。异常不发：多半之后又派成了，先吓人没有用。
     *
     * <p>三个节点三个订阅模板、三份额度（{@link WxSubscribePort#SCENE_WAYBILL_PICKED_UP} 的注释）。
     */
    private void waybill(SysOutbox event, JsonNode payload, String status) {
        if (!"SELF".equals(text(payload, "profile"))) {
            return;
        }
        boolean atLocker = payload.path("atLocker").asBoolean(false);
        String wxScene;
        String title;
        String body;
        String carrierName = nz(ExpressCompanies.nameOf(text(payload, "carrier")), "快递");
        String waybillNo = nz(text(payload, "waybillNo"), "");
        switch (status == null ? "" : status) {
            case "PICKED_UP" -> {
                wxScene = WxSubscribePort.SCENE_WAYBILL_PICKED_UP;
                title = "已揽收";
                body = null;
            }
            case "DELIVERING" -> {
                wxScene = WxSubscribePort.SCENE_WAYBILL_DELIVERING;
                title = atLocker ? "已到驿站" : "派件中";
                body = atLocker
                        ? "%s %s 已放到驿站或快递柜，取件码见订单里的物流信息".formatted(carrierName, waybillNo)
                        : "%s %s 正在派送，请保持电话畅通".formatted(carrierName, waybillNo);
            }
            case "DELIVERED" -> {
                wxScene = WxSubscribePort.SCENE_WAYBILL_SIGNED;
                title = "已签收";
                body = "%s %s 已签收".formatted(carrierName, waybillNo);
            }
            default -> {
                return;
            }
        }
        var buyer = buyerPort.buyerOf(text(payload, "bizRef"));
        if (buyer.isEmpty()) {
            log.warn("[notify] 运单 {} 的子单 {} 查不到，快递节点通知没发", event.getAggregateId(),
                    text(payload, "bizRef"));
            return;
        }
        String userNo = buyer.get().userNo();
        String link = "/pages/order/index?orderNo=" + buyer.get().orderNo();
        String scene = event.getEventType();
        if (body != null) {
            messageService.push(userNo, MessageService.TRADE, title, body, link, event.getEventNo());
            cPush(scene, userNo, title, body, link);
        }
        if (routing.enabled(scene, MsgSceneChannel.AUD_C_USER, MsgSceneChannel.CH_WXSUB)) {
            long at = "DELIVERED".equals(status) ? payload.path("signedAt").asLong(System.currentTimeMillis())
                    : payload.path("at").asLong(System.currentTimeMillis());
            // 派件那条模板的「备注」接在提示语位上：说清楚下一步做什么（去取件 / 留意电话），
            // 而不是一句泛泛的「点开查看」。另外两条模板没有提示语格，传了也不占位
            String tip = "DELIVERING".equals(status)
                    ? (atLocker ? "已到驿站，取件码见订单" : "派件中，请保持电话畅通") : null;
            wxSender.waybill(userNo, wxScene, new WxSubscribePort.WaybillNotice(buyer.get().orderNo(),
                    carrierName, waybillNo, title, at, tip), link.substring(1));
        }
    }

    /** 走到终态的说法，按履约方式分。**不认识的履约方式回落成中性说法**，不要硬套自提。 */
    private static String doneTitleOf(String fulfillment) {
        return switch (fulfillment == null ? "" : fulfillment) {
            case "STORE_PICKUP" -> "已取货";
            case "MERCHANT_DELIVERY" -> "已送达";
            case "EXPRESS" -> "已签收";
            default -> "订单已完成";
        };
    }

    private static String nz(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private void fanOutToFollowers(SysOutbox event, JsonNode payload) {
        String scene = event.getEventType();
        String entityNo = text(payload, "entityNo");
        List<String> userNos = storeFavoritePort.followerUserNos(entityNo);
        if (userNos.isEmpty()) {
            return;   // 没人收藏这家店：不是错误，是大多数店今天的样子
        }
        String title = text(payload, "goodsTitle");
        String link = "/pages/goods/index?goodsNo=" + event.getAggregateId();
        String body = "你收藏的店上新了：" + title;
        boolean inapp = routing.enabled(scene, MsgSceneChannel.AUD_C_USER, MsgSceneChannel.CH_INAPP);
        boolean wx = routing.enabled(scene, MsgSceneChannel.AUD_C_USER, MsgSceneChannel.CH_WXSUB);
        long onSaleAt = payload.get("onSaleAt") == null
                ? System.currentTimeMillis() : payload.get("onSaleAt").asLong();
        for (String userNo : userNos) {
            if (inapp) {
                messageService.push(userNo, MessageService.MARKETING, "店铺上新", body,
                        link, event.getEventNo() + ":" + userNo);
            }
            if (wx) {
                // 微信 page 不带前导斜杠；站内信 link 带 —— 两端各按各的约定（同到货那条）
                wxSender.newGoods(userNo, title, text(payload, "goodsDesc"), onSaleAt,
                        link.substring(1));
            }
        }
        log.info("[notify] 新品开售扇出 entity={} goods={} 收藏者={} inapp={} wxsub={}",
                entityNo, event.getAggregateId(), userNos.size(), inapp, wx);
    }

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
