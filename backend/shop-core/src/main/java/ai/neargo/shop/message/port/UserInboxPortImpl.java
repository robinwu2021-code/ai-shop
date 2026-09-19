package ai.neargo.shop.message.port;

import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.spi.notify.UserInboxPort;
import org.springframework.stereotype.Component;

/** {@link UserInboxPort} 的消息域实现：薄薄一层，频控与防重都在 {@link MessageService#inboxMarketing}。 */
@Component
public class UserInboxPortImpl implements UserInboxPort {

    private final MessageService messageService;

    public UserInboxPortImpl(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public boolean deliverMarketing(String userNo, String title, String body, String link, String dedupKey) {
        return messageService.inboxMarketing(userNo, title, body, link, dedupKey);
    }
}
