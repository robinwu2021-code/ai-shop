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

    // ---------------------------------------------------------------- 平台侧（P-14.2）

    /**
     * 平台工单列表。
     *
     * <p>此前**平台端一条工单接口都没有**：用户能提单、能查自己的单，
     * 而客服没有任何入口看到它们。工单落库之后就没有人再碰过它。
     *
     * @param status 为空给全部；传 {@code OPEN} 就是待处理队列
     */
    List<TicketVO> opsTickets(String status);

    /**
     * 客服回复。工单从 {@code OPEN} → {@code REPLIED}。
     *
     * <p>{@code msg_ticket} 建表时就留了 {@code reply} / {@code replied_at} /
     * {@code replied_by} 三个字段，注释写着「记客服身份 —— 代客操作要能追到人」，
     * 但**全仓库没有任何代码写过它们**，连契约里都没定义过这个动作。
     * 用户那边 {@code TicketVO} 一直在展示 {@code reply}，于是他会反复点开看有没有回复。
     *
     * @param operatorNo 客服的 staffNo。**必填**，不记的话事后无法追责
     */
    TicketVO replyTicket(String ticketNo, String reply, String operatorNo);

    /**
     * 关闭工单。
     *
     * <p>允许从 {@code OPEN} 直接关（用户自己解决了、或是重复提单），
     * 不强制先回复 —— 强制的话客服会为了关单而敷衍回一句，
     * 那比直接关掉更糟：用户会收到一条没有信息量的回复。
     */
    TicketVO closeTicket(String ticketNo, String operatorNo);
}
