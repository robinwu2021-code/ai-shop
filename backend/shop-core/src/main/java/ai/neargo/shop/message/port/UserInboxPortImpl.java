package ai.neargo.shop.message.port;

import ai.neargo.shop.common.Messages;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.spi.notify.UserInboxPort;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** {@link UserInboxPort} 的消息域实现：薄薄一层，频控与防重都在 {@link MessageService#inboxMarketing}。 */
@Component
public class UserInboxPortImpl implements UserInboxPort {

    private final MessageService messageService;

    public UserInboxPortImpl(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public void pointsExpiring(PointsExpiry notice) {
        if (notice == null
                || notice.userNo() == null
                || notice.userNo().isBlank()
                || notice.points() <= 0
                || notice.dedupKey() == null
                || notice.dedupKey().isBlank()) {
            throw new IllegalArgumentException("POINTS_NOTIFICATION_INVALID");
        }
        String date =
                Instant.ofEpochMilli(notice.expireAt())
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .format(DateTimeFormatter.ISO_LOCAL_DATE);
        messageService.push(
                notice.userNo(),
                MessageService.SYSTEM,
                Messages.get("points.expiry.title"),
                Messages.get("points.expiry.body", notice.points(), date),
                null,
                notice.dedupKey());
    }

    @Override
    public boolean deliverMarketing(
            String userNo, String title, String body, String link, String dedupKey) {
        return messageService.inboxMarketing(userNo, title, body, link, dedupKey);
    }
}
