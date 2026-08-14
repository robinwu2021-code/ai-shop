package ai.neargo.shop.message.api.mp;

import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.dto.MessageVOs.FaqVO;
import ai.neargo.shop.message.dto.MessageVOs.MessageVO;
import ai.neargo.shop.message.dto.MessageVOs.TicketVO;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 消息中心与客服（[API 清单 §2.13]）。帮助中心游客可看，其余需登录。 */
@Profile("api")
@RestController
@Validated
public class MpMessageController {

    private final MessageService messageService;

    public MpMessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping("/mp/message")
    public List<MessageVO> messages() {
        return messageService.list(MsgMessage.RECEIVER_USER);
    }

    /** 未读角标。轮询端点，只给一个数 —— 拉整个列表数未读是把带宽当角标用。 */
    @GetMapping("/mp/message/unread-count")
    public long unreadCount() {
        return messageService.unreadCount(MsgMessage.RECEIVER_USER);
    }

    @PostMapping("/mp/message/{messageNo}/read")
    public List<MessageVO> read(@PathVariable String messageNo) {
        return messageService.markRead(MsgMessage.RECEIVER_USER, messageNo);
    }

    @PostMapping("/mp/message/read-all")
    public List<MessageVO> readAll() {
        return messageService.markAllRead(MsgMessage.RECEIVER_USER);
    }

    @PostMapping("/mp/message/subscribe")
    public void subscribe(@RequestBody SubscribeReq req) {
        messageService.subscribe(req.templateIds(), Boolean.TRUE.equals(req.accepted()));
    }

    @GetMapping("/mp/ticket")
    public List<TicketVO> tickets() {
        return messageService.myTickets();
    }

    @PostMapping("/mp/ticket")
    public TicketVO createTicket(@RequestBody CreateTicketReq req) {
        return messageService.createTicket(req.subject(), req.content(), req.orderNo());
    }

    @GetMapping("/mp/ticket/{ticketNo}")
    public TicketVO ticket(@PathVariable String ticketNo) {
        return messageService.ticket(ticketNo);
    }

    /** 帮助中心。**游客可看** —— 还没登录的人也会有疑问。 */
    @GetMapping("/mp/help/faq")
    public List<FaqVO> faq() {
        return messageService.faq();
    }

    public record SubscribeReq(List<String> templateIds, Boolean accepted) {
    }

    public record CreateTicketReq(@NotBlank String subject, @NotBlank String content, String orderNo) {
    }
}
