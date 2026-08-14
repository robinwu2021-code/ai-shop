package ai.neargo.shop.message.dto;

/** 消息与客服的对外结构。 */
public final class MessageVOs {

    private MessageVOs() {
    }

    public record MessageVO(String messageNo, String type, String title, String body,
                            String link, boolean read, long at) {
    }

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

    public record TicketVO(String ticketNo, String subject, String content, String orderNo,
                           String status, String reply, long createdAt, Long repliedAt) {
    }

    public record FaqVO(String question, String answer, String category) {
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
