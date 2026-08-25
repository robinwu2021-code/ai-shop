package ai.neargo.shop.message;

import ai.neargo.shop.message.dto.MessageVOs.FaqVO;
import ai.neargo.shop.message.dto.MessageVOs.InAppLogVO;
import ai.neargo.shop.message.dto.MessageVOs.MessageVO;
import ai.neargo.shop.message.dto.MessageVOs.NotifyQuotaVO;
import ai.neargo.shop.message.dto.MessageVOs.TemplateVO;
import ai.neargo.shop.message.dto.MessageVOs.TicketVO;

import java.util.List;

/** 消息与客服（[API 清单 §2.13]）。 */
public interface MessageService {

    String TRADE = "TRADE";
    String MARKETING = "MARKETING";
    String SYSTEM = "SYSTEM";

    /**
     * 推送站内消息给消费者（C 端收件箱）。
     *
     * @param dedupKey 幂等键（通常是 eventNo）。已存在则**静默跳过** ——
     *                 事件重投是正常现象，不该抛异常让投递器一直重试
     */
    void push(String userNo, String type, String title, String body, String link, String dedupKey);

    /**
     * 推送给任意收件人（{@code MsgMessage.RECEIVER_*}）。
     *
     * <p>同一事件扇出给多个员工时，调用方要把收件人编进 dedupKey
     * （如 {@code eventNo + ":" + receiverNo}）—— dedup 唯一索引是全局的，
     * 只用 eventNo 的话第二个收件人会被当成重投而静默丢掉。
     */
    void pushTo(String receiverType, String receiverNo, String type,
                String title, String body, String link, String dedupKey);

    /**
     * 营销消息的**唯一**入口，频控（P-14.1.4）在这里执行：
     * 模板停用、当日条数达上限、同模板未过最小间隔，任一命中就不发。
     *
     * <p>交易/待办类**不走这里** —— 到货通知被频控拦掉是事故，不是保护。
     *
     * @param templateNo {@code notify_template} 的模板号。必填：没有模板归属的营销消息
     *                   无法执行「同模板最小间隔」，频控对它就是摆设
     * @return 发出去了 true；被频控或停用拦下 false（调用方据此统计触达率）
     */
    boolean pushMarketing(String userNo, String templateNo, String title, String body,
                          String link, String dedupKey);

    /** 当前登录者在指定收件箱的消息。C 端传 USER、B 端传 STAFF、平台传 OPS。 */
    List<MessageVO> list(String receiverType);

    List<MessageVO> markRead(String receiverType, String messageNo);

    List<MessageVO> markAllRead(String receiverType);

    /** 未读数。三端角标轮询用 —— 只 count，不拉列表。 */
    long unreadCount(String receiverType);

    /** 订阅授权上报。同意与拒绝都记。 */
    void subscribe(List<String> templateIds, boolean accepted);

    TicketVO createTicket(String subject, String content, String orderNo);

    List<TicketVO> myTickets();

    TicketVO ticket(String ticketNo);

    List<FaqVO> faq();

    // ---------------------------------------------------------------- 运营侧 FAQ（P-14.2.4）

    /**
     * 所有 FAQ（含草稿）。C 端的 {@link #faq()} 只看 published=true，
     * 这一条是运营编辑视图，要看到所有条目。
     */
    ai.neargo.shop.common.PageData<FaqVO> opsFaqs(long page, long size);

    /**
     * 新建或更新 FAQ。{@code faqNo} 为空时新建，否则按号更新。
     *
     * <p>上架前答案不能为空 —— 空答案比没有条目更糟：
     * 用户点进去只看到一个空白页，而他以为自己找到了答案。
     */
    FaqVO saveFaq(SaveFaqCommand cmd, String operatorNo);

    /** 上架/下架。下架 = published 置 false；上架时验证 answer 非空。 */
    FaqVO setFaqPublished(String faqNo, boolean published, String operatorNo);

    record SaveFaqCommand(String faqNo, String question, String answer,
                          String category, Integer sort) {
    }

    // ---------------------------------------------------------------- 运营侧工单（补齐）

    /**
     * 指派工单给客服。把 {@code assigned_to} 写进工单，不改 status ——
     * 指派是「谁来处理」，status 是「处理到哪一步」，两者正交。
     */
    TicketVO assignTicket(String ticketNo, String assigneeNo, String operatorNo);

    /**
     * 记录代客操作。只写审计日志，不修改工单状态 ——
     * 操作本身已在对应的业务端点完成，这里只是留痕「是谁在工单上下文里做的」。
     */
    TicketVO addProxyAction(String ticketNo, String action, String operatorNo);

    /**
     * 平台侧的站内信记录（运营端·发送记录页的「站内信」tab）。
     *
     * <p><b>为什么不并进 sys_notify_log 那张表</b>：两者回答的不是同一个问题 ——
     * 外发记录答「发出去了吗」（有失败态、要去通道后台查回执），
     * 站内信答「他读了吗」（入库即到达，没有失败态）。
     * 合成一列的话「已发送」在两种语义之间摇摆，而运营看到它时的下一步动作完全不同。
     *
     * @param receiverType USER / STAFF / OPS，空=不筛
     * @param receiverNo   收件人编号，空=不筛。**这里不掩码** ——
     *                     站内信的收件人是平台内部标识（userNo），不是手机号邮箱
     * @param from/to      起止日 yyyy-MM-dd，含当天
     */
    ai.neargo.shop.common.PageData<InAppLogVO> opsInAppMessages(
            String receiverType, String receiverNo, String from, String to, long page, long size);

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
     * <p>{@code notify_ticket} 建表时就留了 {@code reply} / {@code replied_at} /
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

    // ---------------------------------------------------------------- 平台侧 · 触达（P-14.1）

    /** 模板列表。{@code sentCount} 取近 30 天。 */
    List<TemplateVO> opsTemplates();

    /** 停用/启用模板。停用即刻生效，引用它的推送发不出去。 */
    TemplateVO setTemplateEnabled(String templateNo, boolean enabled, String operatorNo);

    /** 当前触达频控。没配过时给一份保守默认值，而不是「不限」。 */
    NotifyQuotaVO notifyQuota();

    /**
     * 保存触达频控。
     *
     * <p>两个上限都必须 &gt; 0：**0 等于没有频控，但界面上看着像配了**，
     * 比不配更危险——运营以为用户受着保护，实际一条都没拦。
     */
    NotifyQuotaVO saveNotifyQuota(int dailyPerUser, int minIntervalHours, String operatorNo);

}
