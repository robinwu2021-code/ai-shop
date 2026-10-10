package ai.neargo.shop.message;

import ai.neargo.shop.common.ExpressCompanies;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.entity.MchNotifyPref;
import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.spi.notify.NotifyBizType;
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
import java.util.Map;
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
    private final ai.neargo.shop.message.notify.WeComOrderAlert weComOrderAlert;
    private final ai.neargo.shop.message.notify.MerchantNotifyPrefs prefs;
    private final ai.neargo.shop.message.notify.MerchantNotifyRecipients recipients;
    private final ai.neargo.shop.spi.notify.SmsPort smsPort;
    private final ai.neargo.shop.spi.notify.MailPort mailPort;
    private final ai.neargo.shop.message.notify.NotifyLogWriter logWriter;
    private final ai.neargo.shop.message.notify.ShipRecipientNotify shipNotify;

    public NotificationConsumer(MessageService messageService, WxSubscribeSender wxSender,
                                ai.neargo.shop.message.notify.PushSender pushSender,
                                MerchantStaffPort merchantStaffPort,
                                ai.neargo.shop.spi.user.StoreFavoritePort storeFavoritePort,
                                SceneChannelRouting routing, ObjectMapper json,
                                SubOrderBuyerPort buyerPort,
                                ai.neargo.shop.message.notify.WeComOrderAlert weComOrderAlert,
                                ai.neargo.shop.message.notify.MerchantNotifyPrefs prefs,
                                ai.neargo.shop.message.notify.MerchantNotifyRecipients recipients,
                                ai.neargo.shop.spi.notify.SmsPort smsPort,
                                ai.neargo.shop.spi.notify.MailPort mailPort,
                                ai.neargo.shop.message.notify.NotifyLogWriter logWriter,
                                ai.neargo.shop.message.notify.ShipRecipientNotify shipNotify) {
        this.messageService = messageService;
        this.wxSender = wxSender;
        this.pushSender = pushSender;
        this.merchantStaffPort = merchantStaffPort;
        this.storeFavoritePort = storeFavoritePort;
        this.routing = routing;
        this.json = json;
        this.buyerPort = buyerPort;
        this.weComOrderAlert = weComOrderAlert;
        this.prefs = prefs;
        this.recipients = recipients;
        this.smsPort = smsPort;
        this.mailPort = mailPort;
        this.logWriter = logWriter;
        this.shipNotify = shipNotify;
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
                /*
                 * 快递发货：再给**收件人**发一条带短链的物流短信（TDD-收件人物流触达 §2）。
                 * 只对快递单发 —— 自送/自提没有「物流轨迹」可看，短信的价值在那条链。
                 * 整条链封在 ShipRecipientNotify 里、自己吞异常，不影响上面已发的站内信与推送。
                 */
                if (byExpress) {
                    shipNotify.notify(nz(text(payload, "subOrderNo"), text(payload, "orderNo")));
                }
                /*
                 * 微信订阅消息**只发商家配送的「开始配送」**（TDD-微信订阅消息优先 AC5）：
                 * 快递发货那条微信支付单由微信「发货信息录入」推、线下单由物流「揽收」推，再发就重复；
                 * 商家配送的单微信只在「已送达」上报时推，出发那一刻没人告诉买家。
                 */
                if (!byExpress && routing.enabled(scene, MsgSceneChannel.AUD_C_USER, MsgSceneChannel.CH_WXSUB)) {
                    wxSender.fielded(userNo, WxSubscribePort.SCENE_DELIVERY_START, Map.of(
                            "orderNo", nz(text(payload, "subOrderNo"), ""),
                            "status", "配送中",
                            "tip", "商家已出发，请保持电话畅通",
                            "time", String.valueOf(System.currentTimeMillis())), link.substring(1));
                }
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
                if (routing.enabled(scene, MsgSceneChannel.AUD_C_USER, MsgSceneChannel.CH_WXSUB)) {
                    wxSender.fielded(userNo, WxSubscribePort.SCENE_AFTER_SALE_RESULT, Map.of(
                            "orderNo", nz(text(payload, "subOrderNo"), ""),
                            "result", "未通过",
                            "reason", why == null || why.isBlank() ? "可在售后详情里申请平台介入" : why,
                            "time", String.valueOf(System.currentTimeMillis())), link.substring(1));
                }
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
                if (routing.enabled(scene, MsgSceneChannel.AUD_C_USER, MsgSceneChannel.CH_WXSUB)) {
                    wxSender.fielded(userNo, WxSubscribePort.SCENE_RETURN_WAIT, Map.of(
                            "afterSaleNo", event.getAggregateId(),
                            "status", "待寄回",
                            "tip", "请尽快寄回并填单号，超时自动关闭",
                            "time", String.valueOf(System.currentTimeMillis())), link.substring(1));
                }
            }
            case NotifyScene.GROUP_FORMED -> fanOutToGroup(event, payload, true);
            case NotifyScene.GROUP_FAILED -> fanOutToGroup(event, payload, false);
            // ------------------------------------------------------------ B 端
            case NotifyScene.SUB_ORDER_PAID -> {
                /*
                 * 深链落到**订单详情页**（通知就是针对这一单），带上 storeNo 与 src=notify：
                 * 详情按当前门店过滤，而点通知的人当前选的店多半不是这单的店 ——
                 * 不对齐门店会跨店 NOT_FOUND。src=notify 让详情页把这当成深链、做门店对齐，
                 * 与「从列表正常点进来」区分开（后者当前门店已对，不用切）。
                 * storeNo 为空（老数据）时省掉 store 参数，详情页回落到「不限门店查」。
                 */
                String paidStore = text(payload, "storeNo");
                String detailLink = "/pages/order/index?orderNo=" + nz(text(payload, "subOrderNo"), "")
                        + (paidStore == null || paidStore.isBlank() ? "" : "&store=" + paidStore)
                        + "&src=notify";
                fanOutToStaff(event, text(payload, "entityNo"), paidStore, ORDER_ROLES,
                        "新订单", "有新的订单待备货，记得按时送到自提点",
                        detailLink,
                        // 来单：App 响铃与微信**都发**（wxFirst=false）—— 厂商通道没报备，App 在后台收不到（通知 TDD §13.3②）
                        new WxStaff(WxSubscribePort.SCENE_MCH_NEW_ORDER, Map.of(
                                "orderNo", nz(text(payload, "subOrderNo"), ""),
                                "amount", yuan(payload.path("payAmount").asLong(0)),
                                "time", String.valueOf(System.currentTimeMillis()),
                                "tip", "有新订单，请及时备货"), false));
                /*
                 * 来单这一条**多条腿同时走**（TDD-来单四渠道与商家通知设置 AC1）：
                 * 上面 fanOutToStaff 里的站内信 / 微信订阅 / App 推送，加这里三条。
                 *
                 * 为什么要这么多条腿：微信订阅**一次授权只够一条**，商家不进小程序就没额度；
                 * App 的厂商通道没报备，退到后台就收不到；企微群、短信与邮件配过一次就一直能发。
                 * 来单是全链路最怕漏的一条，任一条单独都不够可靠。
                 *
                 * **按 {@link #STORE_CHANNEL_ORDER} 逐条走，每条各自吞掉自己的失败。**
                 * 顺序写成一份名单而不是靠这几行的书写次序：靠书写次序的话，
                 * 谁调一下行序就变了，而且 diff 里看不出「顺序变了」这件事。
                 */
                String entityNo = text(payload, "entityNo");
                String storeNo = text(payload, "storeNo");
                String subOrderNo = text(payload, "subOrderNo");
                long paid = payload.path("payAmount").asLong(0);
                for (String ch : STORE_CHANNEL_ORDER) {
                    /*
                     * 开关关着、或这家店没填地址 → **什么都不做，也不留痕**。
                     * 那不是一次失败的发送，是「这条通道对这家店不存在」——
                     * 记下来的话，绝大多数没配企微群的门店每单都会刷一行「没配」，
                     * 真正的失败就埋在里面了。配了却发不出去才写 FAILED。
                     */
                    if (!prefs.on(storeNo, scene, ch)) {
                        continue;
                    }
                    try {
                        switch (ch) {
                            case MchNotifyPref.CH_WEBHOOK ->
                                    weComOrderAlert.paid(entityNo, storeNo, subOrderNo, paid);
                            case MchNotifyPref.CH_SMS ->
                                    smsOrderPaid(entityNo, storeNo, subOrderNo, paid);
                            case MchNotifyPref.CH_MAIL ->
                                    mailOrderPaid(entityNo, storeNo, subOrderNo, paid);
                            default -> log.warn("[notify] 门店级通道 {} 没有发送实现 —— "
                                    + "往 STORE_CHANNEL_ORDER 加了成员却忘了在这里加分支", ch);
                        }
                    } catch (RuntimeException e) {
                        /*
                         * 每条自己已经吞过一次，这里是兜底：**一条炸了不能影响下一条**，
                         * 更不能冒到 outbox 消费者那里 —— 那会判整条事件失败并重投，
                         * 于是站内信被发第二遍。
                         */
                        log.warn("[notify] 通道 {} 发送异常 subOrderNo={} {}", ch, subOrderNo, e.toString());
                    }
                }
            }
            case NotifyScene.AFTER_SALE_APPLIED -> fanOutToStaff(event, text(payload, "entityNo"),
                    text(payload, "storeNo"), AFTER_SALE_ROLES,
                    "新的售后申请", "买家提交了售后申请，尽早处理更容易协商解决",
                    "/pages/after-sale/index",
                    new WxStaff(WxSubscribePort.SCENE_MCH_AFTER_SALE, Map.of(
                            "orderNo", nz(text(payload, "subOrderNo"), ""),
                            "status", "待处理",
                            "tip", "顾客申请了售后，请尽快处理",
                            "time", String.valueOf(System.currentTimeMillis())), true));
            case NotifyScene.REVIEW_CREATED -> {
                int rating = payload.get("rating") == null ? 5 : payload.get("rating").asInt();
                // 差评单独点名：混在普通评价里会被当成例行夸奖划掉
                fanOutToStaff(event, text(payload, "entityNo"), text(payload, "storeNo"), REVIEW_ROLES,
                        rating <= 2 ? "收到差评" : "收到新评价",
                        rating <= 2 ? "有一条 " + rating + " 星评价，回复得当能挽回大多数顾客"
                                : "有顾客发表了新评价",
                        "/pages/reviews/index",
                        new WxStaff(WxSubscribePort.SCENE_MCH_REVIEW, Map.of(
                                "rating", rating + "星",
                                "time", String.valueOf(System.currentTimeMillis()),
                                "tip", rating <= 2 ? "收到差评，及时回复能挽回顾客" : "有顾客发表了新评价"), true));
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
        boolean wx = routing.enabled(scene, MsgSceneChannel.AUD_C_USER, MsgSceneChannel.CH_WXSUB);
        Map<String, String> wxValues = Map.of(
                "groupNo", event.getAggregateId(),
                "goods", title,
                "result", formed ? "成功" : "失败",
                "remark", formed ? "已成团，等商家发货" : "未成团，款项原路退回");
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
            if (wx) {
                // 团里多数人没授权过这条 —— 没额度的静默跳过（WxSubscribeSender 的口径），不是错误
                wxSender.fielded(userNo, WxSubscribePort.SCENE_GROUP_RESULT, wxValues, link.substring(1));
            }
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

    /**
     * 商家那一路微信订阅消息（TDD-微信订阅消息优先 AC9 / AC10）。
     *
     * @param wxFirst true = 微信发出去了就不再走 App（售后、评价）；false = 两路都发（来单）
     */
    /**
     * **门店级通道的推送顺序**（用户 2026-10-10：「推送按渠道顺序逐个推送」）。
     *
     * <p>按人扇出的那三条（站内信 / 微信订阅 / App 推送）在 {@link #fanOutToStaff} 里，
     * 它们的顺序由那个方法固定；这份名单管的是**按店发**的三条。
     * 两组分开是因为扇出粒度不同，不是因为顺序分了两处 —— 完整顺序是：
     *
     * <pre>
     *   站内信 → 微信订阅 → App 推送   （按人，fanOutToStaff）
     *     → 企微群 → 短信 → 邮件        （按店，这份名单）
     * </pre>
     *
     * <p><b>企微群排第一</b>：它是三条里最可靠的（配过一次就一直能发，没有额度、
     * 没有模板审批、没有计费）。短信排在邮件前面是因为它更「吵」—— 店主没在看屏幕时，
     * 短信比邮件更可能被注意到。
     *
     * <p>加一条通道就在这里加一个成员并在 switch 里加一支；
     * 只加成员不加分支的话，那一支会落到 default 的 WARN 上，不会静默。
     */
    private static final List<String> STORE_CHANNEL_ORDER =
            List.of(MchNotifyPref.CH_WEBHOOK, MchNotifyPref.CH_SMS, MchNotifyPref.CH_MAIL);

    private record WxStaff(String scene, Map<String, String> values, boolean wxFirst) {
    }

    /**
     * 微信订阅消息点开后要跳的商家页 —— **必须先过 `_entry`**（c-app/scripts/with-biz.mjs）。
     *
     * <p>并包小程序跑的是 c-app 的 App.vue，b-app 的 {@code onLaunch → merchant.restore()}
     * 不执行；商家令牌（btk_）只在 `_entry` 里从 C 端会话换取、商家 store 也只在那里 restore。
     * 直接深链到 {@code pkg-biz/pages/orders/index} 会绕过它 —— 落地页的 {@code this.token} 为空，
     * 于是 {@code loadScope} 取不到权限、{@code perms} 恒空、{@code can()} 全 false，
     * 页面被自己的 {@code :denied} 锁成「没有权限」（2026-10-10 真机实测）。
     *
     * <p>所以把真正的目标页塞进 `_entry` 的 {@code redirect}：`_entry` 先把会话建好，
     * 再 reLaunch 到它。{@code link} 形如 {@code /pages/orders/index?tab=PAID}，
     * 去掉前导斜杠后整段 URL 编码，微信打开时会自动解码还原成 {@code redirect} 的值。
     */
    private static String mpBizPage(String link) {
        String target = link.startsWith("/") ? link.substring(1) : link;
        return "pkg-biz/_entry/index?redirect="
                + java.net.URLEncoder.encode(target, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String yuan(long minor) {
        return "%.2f元".formatted(minor / 100.0);
    }

    /**
     * @param storeNo 这件事发生在哪家店 —— **门店级开关要它**
     *                （TDD-来单四渠道与商家通知设置 §2.2）。历史 outbox payload 里
     *                可能为 null（售后与评价的 storeNo 是 2026-10-10 才加进事件的），
     *                那时回落成只受平台总闸管，与改造前一致
     */
    private void fanOutToStaff(SysOutbox event, String entityNo, String storeNo, Set<String> roles,
                               String title, String body, String link, WxStaff wxStaff) {
        String scene = event.getEventType();
        /*
         * **两级串联**：平台总闸（notify_scene_channel，运营配）× 商家分闸
         * （mch_notify_pref，店主配）。只问 prefs.on(...) 这一个方法 ——
         * 自己先问 routing 再问商家的话，串联顺序会在各个调用点分叉
         * （TDD-来单四渠道与商家通知设置 §2.2）。
         */
        boolean push = prefs.on(storeNo, scene, MsgSceneChannel.CH_PUSH);
        boolean ring = push
                && MsgSceneChannel.LEVEL_RING.equals(routing.pushLevel(scene, MsgSceneChannel.AUD_B_STAFF));
        boolean wx = wxStaff != null && prefs.on(storeNo, scene, MsgSceneChannel.CH_WXSUB);
        List<String> userNos = merchantStaffPort.staffUserNos(entityNo, roles);
        for (String userNo : userNos) {
            messageService.pushTo(MsgMessage.RECEIVER_STAFF, userNo, MessageService.TRADE,
                    title, body, link, event.getEventNo() + ":" + userNo);
            /*
             * 店主的商家账号与 C 端同一个 user_no，小程序 openid 现成；店员多半查不到 openid → 静默跳过、只走 App。
             * 没额度 / 没模板同样静默跳过 —— 那正是「微信优先、没发出去才回落 App」里「没发出去」的那一半。
             */
            boolean wxSent = wx && wxSender.fielded(userNo, wxStaff.scene(), wxStaff.values(), mpBizPage(link));
            if (!push || (wxSent && wxStaff.wxFirst())) {
                continue;
            }
            if (ring) {
                pushSender.ring(MsgMessage.RECEIVER_STAFF, userNo, title, body, link);
            } else {
                pushSender.notify(MsgMessage.RECEIVER_STAFF, userNo, title, body, link);
            }
        }
    }

    /**
     * 来单短信。**店主的登录手机号 + 这家店自填的最多两个**，逐个发、逐个吞失败。
     *
     * <p>店主那一个恒发、删不掉（真源是 {@code mch_account.login_phone}）；
     * 额外的在 {@code mch_notify_recipient.sms_phones} 里（TDD §2.5）。
     *
     * <p><b>去重</b>：店主很可能把自己的号又填了一遍，而短信按条计费，
     * 发两遍既花钱又像系统出错。
     *
     * <p>模板没报备时每个号会写一行 {@code tpl_unconfigured} —— 那是刻意的：
     * 它是「为什么商家没收到短信」唯一的答案，静默跳过的话这个问题在线上无从回答。
     */
    private void smsOrderPaid(String entityNo, String storeNo, String subOrderNo, long payAmountMinor) {
        java.util.LinkedHashSet<String> to = new java.util.LinkedHashSet<>();
        merchantStaffPort.ownerPhone(entityNo).ifPresent(to::add);
        to.addAll(recipients.extraPhones(storeNo));
        if (to.isEmpty()) {
            /*
             * 开了短信却没有收件人（自营店主没登录手机号、也没在设置页填额外号）——
             * **记一行 FAILED 让它在消息记录表里看得见**（用户 2026-10-10：
             * 「不管是否通过，如果错误，展示在消息记录表即可」），而不是静默跳过。
             * 这改了最初「没号可发不是失败、不留痕」的口径：店主开了短信开关，
             * 「为什么没人收到」就该在日志里有答案。不影响其余出口。
             */
            logWriter.write(SysNotifyLog.SMS, NotifyBizType.TRADE_NOTIFY, "-", null,
                    "TPL_SMS_ORDER_PAID", SysNotifyLog.FAILED, "no_recipient", null, null);
            return;
        }
        String yuan = "%.2f".formatted(payAmountMinor / 100.0);
        for (String phone : to) {
            try {
                smsPort.sendOrderPaid(phone, subOrderNo, yuan);
            } catch (RuntimeException e) {
                // 一个号发不出去不该拖累其余的 —— 逐个 try，否则第一个号有问题后面都收不到
                log.warn("[notify] 来单短信没发出去 subOrderNo={} {}", subOrderNo, e.toString());
            }
        }
    }

    /**
     * 来单邮件。发到这家店填的那个地址；没填就不发。
     *
     * <p>**五条通道里生产上唯一即开即用的一条** —— 邮件的四项配置早就齐了，
     * 不像短信要等阿里云报备模板。所以它是「短信还发不出去」期间的实际主力。
     *
     * <p>内容是企微那四行的纯文本版：主题一眼看出金额与门店，正文给细节。
     * 失败吞掉 —— 四条出口各自独立（留痕在 {@code sys_notify_log}，由 MailPort 的装饰器写）。
     */
    private void mailOrderPaid(String entityNo, String storeNo, String subOrderNo, long payAmountMinor) {
        String to = recipients.email(storeNo).orElse(null);
        if (to == null) {
            return;     // 没填地址 —— 不是失败，没必要留痕
        }
        try {
            String amount = "￥%.2f".formatted(payAmountMinor / 100.0);
            mailPort.send(to, "新订单 " + amount,
                    weComOrderAlert.plainText(entityNo, storeNo, subOrderNo, payAmountMinor));
        } catch (RuntimeException e) {
            log.warn("[notify] 来单邮件没发出去 subOrderNo={} {}", subOrderNo, e.toString());
        }
    }

    private String text(JsonNode payload, String field) {
        JsonNode node = payload == null ? null : payload.get(field);
        return node == null ? null : node.asString();
    }
}
