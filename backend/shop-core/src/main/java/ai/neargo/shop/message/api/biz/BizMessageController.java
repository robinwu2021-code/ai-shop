package ai.neargo.shop.message.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.dto.MessageVOs.MessageVO;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.trade.service.AfterSaleService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端消息中心（TDD-通知与消息推送 §二期）。与 {@code /mp/message} 同形。
 *
 * <p><b>不要求任何 BizPerms</b>（登记在 BizEndpointPermTest 的 PUBLIC 表）：
 * 收件箱按当前 userNo 隔离，别人的消息本来就查不到；
 * 要 biz 权限的话，收到「新订单」通知的店员反而打不开消息中心。
 */
@Profile("api")
@RestController
public class BizMessageController {

    private final MessageService messageService;
    private final AfterSaleService afterSales;

    public BizMessageController(MessageService messageService, AfterSaleService afterSales) {
        this.messageService = messageService;
        this.afterSales = afterSales;
    }

    public record BizMessageVO(String messageNo, String type, String title, String body,
                               String link, boolean read, long at,
                               String bizType, String bizNo, String storeNo,
                               boolean actionPending) { }

    @GetMapping("/biz/message")
    public List<BizMessageVO> messages() {
        List<MessageVO> messages = messageService.list(MsgMessage.RECEIVER_STAFF);
        var ctx = BizContext.current();
        java.util.Map<String, String> ids = new java.util.HashMap<>();
        java.util.Map<String, String> stores = new java.util.HashMap<>();
        for (MessageVO m : messages) {
            if (m.link() == null || !m.link().startsWith("/pages/after-sale/index")) continue;
            String no = queryParam(m.link(), "afterSaleNo");
            if (no != null && !no.isBlank()) ids.put(m.messageNo(), no);
            String store = queryParam(m.link(), "store");
            if (store != null && !store.isBlank()) stores.put(m.messageNo(), store);
        }
        java.util.Set<String> pending = ctx.can(BizPerms.AFTERSALE) && ctx.merchantNo() != null
                ? afterSales.merchantPendingNos(ctx.merchantNo(), ctx.currentStoreNo(), ids.values())
                : java.util.Set.of();
        return messages.stream().map(m -> new BizMessageVO(m.messageNo(), m.type(), m.title(),
                m.body(), m.link(), m.read(), m.at(), ids.containsKey(m.messageNo())
                ? "AFTER_SALE" : null, ids.get(m.messageNo()), stores.get(m.messageNo()),
                ids.containsKey(m.messageNo()) && pending.contains(ids.get(m.messageNo())))).toList();
    }

    private static String queryParam(String link, String key) {
        int mark = link.indexOf('?');
        if (mark < 0) return null;
        for (String item : link.substring(mark + 1).split("&")) {
            int eq = item.indexOf('=');
            if (eq > 0 && key.equals(item.substring(0, eq))) {
                return java.net.URLDecoder.decode(item.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** 未读角标。b-app 30s 轮询用，只给一个数。 */
    @GetMapping("/biz/message/unread-count")
    public long unreadCount() {
        return messageService.unreadCount(MsgMessage.RECEIVER_STAFF);
    }

    @PostMapping("/biz/message/{messageNo}/read")
    public List<MessageVO> read(@PathVariable String messageNo) {
        return messageService.markRead(MsgMessage.RECEIVER_STAFF, messageNo);
    }

    @PostMapping("/biz/message/read-all")
    public List<MessageVO> readAll() {
        return messageService.markAllRead(MsgMessage.RECEIVER_STAFF);
    }

    /**
     * 商家在小程序里授权订阅消息后上报（TDD-微信订阅消息优先 AC8）。与 {@code /mp/message/subscribe} 同一张额度表：
     * 店主的商家账号与 C 端同一个 user_no，发送时按这个号查小程序 openid、扣这里攒的额度。
     * App 里调不起 {@code uni.requestSubscribeMessage}，根本不会走到这里。
     */
    @PostMapping("/biz/message/subscribe")
    public void subscribe(@RequestBody SubscribeReq req) {
        messageService.subscribe(req.templateIds(), Boolean.TRUE.equals(req.accepted()));
    }

    public record SubscribeReq(List<String> templateIds, Boolean accepted) {
    }
}
