package ai.neargo.shop.message.api.ops;

import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.dto.MessageVOs.MessageVO;
import ai.neargo.shop.message.entity.MsgMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
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
}
