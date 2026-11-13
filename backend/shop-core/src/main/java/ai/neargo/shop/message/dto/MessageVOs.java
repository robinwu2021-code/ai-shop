package ai.neargo.shop.message.dto;

/** 消息与客服的对外结构。 */
public final class MessageVOs {

    private MessageVOs() {
    }

    public record MessageVO(String messageNo, String type, String title, String body,
                            String link, boolean read, long at,
                            String bizType, String bizNo, String storeNo, String entityNo) {
        public MessageVO(String messageNo, String type, String title, String body,
                         String link, boolean read, long at) {
            this(messageNo,type,title,body,link,read,at,null,null,null,null);
        }
    }

    /** Counts only: badge authorization never loads historical payment bodies. */
    public record PaymentUnread(String entityNo, String storeNo, long unreadCount, long globalUnreadCount) { }

    /**
     * 站内信的**平台侧**记录（发送记录页的第二个 tab）。
     *
     * <p>与 {@link MessageVO} 的差别是**视角**：那个是「我的收件箱」，按当前登录者裁剪、
     * 带 body 与 link 供阅读；这个是运营在查「平台发给谁了」，要带上收件人，
     * 不带正文 —— 站内信正文可能含营销文案，列表页不需要，而少一列就少一处泄露面。
     *
     * <p><b>没有 status 列</b>：站内信入库即到达，不存在「发送中/失败」。
     * 与外发记录合成一张表的话，同一个「已发送」在两种语义之间摇摆 —— 所以分两个 tab。
     */
    public record InAppLogVO(String messageNo, String receiverType, String receiverNo,
                             String type, String title, String templateNo,
                             boolean read, long at) {
    }

    /**
     * 工单。C 端 {@code /mp/ticket} 与运营端 {@code /ops/tickets} 共用。
     *
     * <p>{@code source} 起的六个字段是商户问题回报加的（TDD-商户App问题回报与版本 F7），<b>只往尾部加、可空</b>：
     * C 端工单它们为空（{@code source=C_USER}、{@code storeDropped=false}），现有字段与顺序不动。
     */
    public record TicketVO(String ticketNo, String subject, String content, String orderNo,
                           String status, String reply, long createdAt, Long repliedAt,
                           String source, String category, String storeNo, boolean storeDropped,
                           String clientVersion, String clientPlatform) {
    }

    /**
     * 商户「我的回报」一条（{@code GET /biz/feedback/mine}）。
     *
     * <p><b>不复用 {@link TicketVO}</b>：App 用 {@code DateTime.tryParse} 读时间，要的是 ISO-8601 字符串，
     * 而 TicketVO 的时间是毫秒数（C 端与运营端已经按它在读）。
     *
     * @param status    OPEN / REPLIED / CLOSED（App 要补「已结案」的显示）
     * @param createdAt ISO-8601 UTC，如 {@code 2026-11-08T09:20:00Z}
     * @param repliedAt 同上；没回复为 null
     */
    public record FeedbackVO(String feedbackNo, String category, String description, String status,
                             String reply, String storeNo, String createdAt, String repliedAt) {
    }

    /**
     * 回报附带的一条诊断：只有这四个字段，其余在入库前丢掉（F4）。
     *
     * @param at 带时区的原值归一成 UTC（{@code …Z}）；不带时区的<b>原样</b>（设备本地时间，不猜时区）；
     *           解析不了为 null
     */
    public record DiagnosticVO(String at, String method, String path, String outcome) {
    }

    /**
     * @param faqNo     null means C 端快照（无需 id）；ops 端总是有值
     * @param published null means C 端快照
     */
    public record FaqVO(String faqNo, String question, String answer, String category,
                        Integer sort, Boolean published) {
        /** C 端 VO —— faqNo 与 published 对外无意义，省略 */
        public static FaqVO forC(String question, String answer, String category) {
            return new FaqVO(null, question, answer, category, null, null);
        }
    }
    /** 消息模板（P-14.1.1）。sentCount 取近 30 天。 */
    public record TemplateVO(String templateNo, String name, String channel, String lang,
                             String content, String providerTemplateId,
                             boolean enabled, long sentCount) {
    }

    /**
     * 触达频控（P-14.1.4）。
     *
     * <p>两个上限都必须 &gt; 0 —— <b>0 等于没有频控，但界面上看着像配了</b>，
     * 比不配更危险：运营以为用户受着保护，实际一条都没拦。
     */
    public record NotifyQuotaVO(int dailyPerUser, int minIntervalHours) {
    }

}
