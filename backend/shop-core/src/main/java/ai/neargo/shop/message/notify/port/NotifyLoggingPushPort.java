package ai.neargo.shop.message.notify.port;

import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.notify.NotifyLogWriter;
import ai.neargo.shop.spi.notify.NotifyBizType;
import ai.neargo.shop.spi.notify.PushPort;
import ai.neargo.shop.spi.notify.SendResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 给推送通道套一层发送记录。模式与 {@link NotifyLoggingSmsPort} 一致：失败也记、先记后抛。
 *
 * <p>推送尤其需要这张表：用户说「我没收到提醒」时，唯一能分清
 * 「没推」「推了但厂商没送达」「送达了但他没看见」的证据就在这里
 * （前两者看 status 与 providerMsgId，第三者只能看行为日志）。
 */
@Component
@Primary
public class NotifyLoggingPushPort implements PushPort {

    private final PushPort delegate;
    private final NotifyLogWriter writer;

    public NotifyLoggingPushPort(@Qualifier("pushGateway") PushPort delegate, NotifyLogWriter writer) {
        this.delegate = delegate;
        this.writer = writer;
    }

    /** 推送目前只有一条通用模板（标题+正文），场景差异体现在内容而不是模板 */
    private static final String TPL_PUSH = "TPL_PUSH_TEST";

    @Override
    public SendResult push(String clientId, String title, String body, String link, String level) {
        try {
            SendResult r = delegate.push(clientId, title, body, link, level);
            writer.write(SysNotifyLog.PUSH, NotifyBizType.TRADE_NOTIFY, clientId,
                    r.templateCode(), TPL_PUSH, SysNotifyLog.SENT, null, r.providerMsgId(), null);
            return r;
        } catch (RuntimeException e) {
            writer.write(SysNotifyLog.PUSH, NotifyBizType.TRADE_NOTIFY, clientId,
                    level, TPL_PUSH, SysNotifyLog.FAILED, e.getMessage(), null, null);
            throw e;
        }
    }
}
