package ai.neargo.shop.message.port;

import ai.neargo.shop.message.notify.PushSender;
import ai.neargo.shop.spi.notify.UserPushPort;
import org.springframework.stereotype.Component;

/**
 * 给某个买家推一条通知（{@link UserPushPort}）。
 *
 * <p>薄薄一层：设备查找、厂商路由、失败留痕都在 {@link PushSender} 里，
 * 这里只是把「收件人是个人」这件事翻译成消息域认得的收件人类型。
 */
@Component
public class UserPushPortImpl implements UserPushPort {

    private final PushSender pushSender;

    public UserPushPortImpl(PushSender pushSender) {
        this.pushSender = pushSender;
    }

    @Override
    public boolean pushToUser(String userNo, String title, String body, String link) {
        if (userNo == null || userNo.isBlank()) {
            return false;
        }
        try {
            pushSender.notify(ai.neargo.shop.message.entity.MsgMessage.RECEIVER_USER,
                    userNo, title, body, link);
            return true;
        } catch (RuntimeException e) {
            /*
             * **不往上抛**：推送是尽力而为的通道。一条没发出去就让整批触达回滚，
             * 会让已经收到的人在重试时再收一次 —— 而重复打扰比漏发严重得多。
             */
            return false;
        }
    }
}
