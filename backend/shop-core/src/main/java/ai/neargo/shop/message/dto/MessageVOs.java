package ai.neargo.shop.message.dto;

/** 消息与客服的对外结构。 */
public final class MessageVOs {

    private MessageVOs() {
    }

    public record MessageVO(String messageNo, String type, String title, String body,
                            String link, boolean read, long at) {
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
