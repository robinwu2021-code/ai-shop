package ai.neargo.shop.message;

import ai.neargo.shop.message.dto.MessageVOs.FaqVO;
import ai.neargo.shop.message.dto.MessageVOs.MessageVO;
import ai.neargo.shop.message.dto.MessageVOs.TicketVO;

import java.util.List;

/** 消息与客服（[API 清单 §2.13]）。 */
public interface MessageService {

    String TRADE = "TRADE";
    String MARKETING = "MARKETING";
    String SYSTEM = "SYSTEM";

    /**
     * 推送站内消息。
     *
     * @param dedupKey 幂等键（通常是 eventNo）。已存在则**静默跳过** ——
     *                 事件重投是正常现象，不该抛异常让投递器一直重试
     */
    void push(String userNo, String type, String title, String body, String link, String dedupKey);

    List<MessageVO> list();

    List<MessageVO> markRead(String messageNo);

    List<MessageVO> markAllRead();

    /** 订阅授权上报。同意与拒绝都记。 */
    void subscribe(List<String> templateIds, boolean accepted);

    TicketVO createTicket(String subject, String content, String orderNo);

    List<TicketVO> myTickets();

    TicketVO ticket(String ticketNo);

    List<FaqVO> faq();
}
