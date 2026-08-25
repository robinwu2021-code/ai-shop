package ai.neargo.shop.message.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.dto.MessageVOs.FaqVO;
import ai.neargo.shop.message.dto.MessageVOs.MessageVO;
import ai.neargo.shop.message.entity.MsgMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端通知收件箱（顶栏铃铛）。与 {@code /ops/auth/me} 同类：
 * 个人自查端点，令牌链保护即可，不配功能权限码 ——
 * 配了的话，收到「新工单」通知的客服可能反而点不开铃铛。
 */
@Profile("ops")
@RestController
@Validated
public class OpsMessageController {

    private final MessageService messageService;

    public OpsMessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping("/ops/message")
    public List<MessageVO> messages() {
        return messageService.list(MsgMessage.RECEIVER_OPS);
    }

    /** 未读角标。ops-web 15s 轮询用。 */
    @GetMapping("/ops/message/unread-count")
    public long unreadCount() {
        return messageService.unreadCount(MsgMessage.RECEIVER_OPS);
    }

    @PostMapping("/ops/message/{messageNo}/read")
    public List<MessageVO> read(@PathVariable String messageNo) {
        return messageService.markRead(MsgMessage.RECEIVER_OPS, messageNo);
    }

    @PostMapping("/ops/message/read-all")
    public List<MessageVO> readAll() {
        return messageService.markAllRead(MsgMessage.RECEIVER_OPS);
    }

    // ────────────────────────────────────────────────── 帮助中心 FAQ（P-14.2.4）

    /**
     * FAQ 列表（运营视图）。含草稿；C 端只看 published=true。
     *
     * <p>用 MESSAGE_TICKET_HANDLE：FAQ 是客服的自助工具，
     * 能处理工单的人自然也管 FAQ；另立一个码会让「我只有回工单权限」的客服
     * 连 FAQ 都打不开 —— 他需要它来回答用户的提问。
     */
    @GetMapping("/ops/faqs")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TICKET_HANDLE + "')")
    public PageData<FaqVO> faqs(@RequestParam(defaultValue = "1") long page,
                                @RequestParam(defaultValue = "50") long size) {
        return messageService.opsFaqs(page, size);
    }

    /**
     * 新建或更新 FAQ。{@code faqNo} 为空时新建（草稿），否则按号更新。
     *
     * <p>新建后需要再调一次 {@code /ops/faqs/{no}/published} 才对 C 端可见 ——
     * 这比「保存即上架」安全：内容还没审完时运营可以先保存草稿。
     */
    @PostMapping("/ops/faqs")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TICKET_HANDLE + "')")
    public FaqVO saveFaq(@RequestBody SaveFaqReq req) {
        String operator = SecurityUtils.currentUserNo();
        return messageService.saveFaq(
                new MessageService.SaveFaqCommand(req.faqNo(), req.question(), req.answer(),
                        req.category(), req.sort()),
                operator);
    }

    /** 上架/下架。{@code published=true} 时验证 answer 非空。 */
    @PostMapping("/ops/faqs/{faqNo}/published")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TICKET_HANDLE + "')")
    public FaqVO setFaqPublished(@PathVariable String faqNo, @RequestBody PublishedReq req) {
        String operator = SecurityUtils.currentUserNo();
        return messageService.setFaqPublished(faqNo, req.published(), operator);
    }

    public record SaveFaqReq(String faqNo, String question, String answer,
                             String category, Integer sort) {
    }

    public record PublishedReq(boolean published) {
    }
}
