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
}
