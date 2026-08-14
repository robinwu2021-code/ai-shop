package ai.neargo.shop.message.api.biz;

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

    public BizMessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping("/biz/message")
    public List<MessageVO> messages() {
        return messageService.list(MsgMessage.RECEIVER_STAFF);
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
}
