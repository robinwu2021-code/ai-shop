package ai.neargo.shop.message.notify.port;

import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.notify.NotifyLogWriter;
import ai.neargo.shop.spi.notify.MailPort;
import ai.neargo.shop.spi.notify.SendResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 给邮件通道套一层发送记录。理由同 {@link NotifyLoggingSmsPort}。
 *
 * <p>邮件这一侧的记录尤其要紧：它的消费方是**运营端密码交付**——
 * 「新同事说没收到初始密码」时，这张表是唯一能区分
 * 「发了但进了垃圾箱」与「压根没发出去」的地方。
 * 两者的处置完全不同：前者让他去翻垃圾箱，后者要重置并查 SMTP。
 */
@Component
@Primary
public class NotifyLoggingMailPort implements MailPort {

    private final MailPort delegate;
    private final NotifyLogWriter writer;

    public NotifyLoggingMailPort(@Qualifier("mailGateway") MailPort delegate, NotifyLogWriter writer) {
        this.delegate = delegate;
        this.writer = writer;
    }

    @Override
    public SendResult send(String to, String subject, String body) {
        return send(to, subject, body, SysNotifyLog.BIZ_TEST, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>override 接口的五参版而不是另起一个方法名</b>：调用方（platform 域）
     * 只该看见 {@code spi.notify.MailPort}，看不见这个装饰器 ——
     * 让它依赖具体实现类，platform 就依赖上了 message 域，两个域再也拆不开。
     * 架构守卫当场把这件事拦下来了。
     */
    @Override
    public SendResult send(String to, String subject, String body,
                           String bizType, String operatorNo) {
        return send(to, subject, body, bizType, operatorNo, null);
    }

    /**
     * 带业务模板号的重载。**只给 message 域内部用**（MailTemplatePortImpl 走模板发信时）——
     * 不放进 {@code MailPort} SPI：别的域发信时并不知道模板号，
     * 逼它们传一个 null 只是把噪音推给调用方。
     *
     * @param templateNo 平台业务模板号；自由文本发送传 null
     */
    public SendResult send(String to, String subject, String body,
                           String bizType, String operatorNo, String templateNo) {
        try {
            SendResult r = delegate.send(to, subject, body);
            // 邮件没有通道方模板号，把**主题**记进 templateCode 列 —— 列表页要能一眼看出这是哪类邮件
            writer.write(SysNotifyLog.MAIL, bizType, to, subject,
                    templateNo, SysNotifyLog.SENT, null, r.providerMsgId(), operatorNo);
            return r;
        } catch (RuntimeException e) {
            writer.write(SysNotifyLog.MAIL, bizType, to, subject,
                    templateNo, SysNotifyLog.FAILED, e.getMessage(), null, operatorNo);
            throw e;
        }
    }
}
