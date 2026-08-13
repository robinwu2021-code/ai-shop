package ai.neargo.shop.message.notify;

import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.spi.notify.SendResult;
import ai.neargo.shop.spi.notify.SmsPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 给短信通道套一层发送记录。
 *
 * <p><b>为什么是装饰器而不是写进各实现</b>：实现有四个（桩、阿里云，以及将来
 * 可能的第二家、第三家通道），写进去要各写一遍 —— 而**漏写的那个恰恰是最需要记录的**：
 * 刚接的新通道，正是最不确定「发没发出去」的时候。
 *
 * <p><b>失败也记，且先记后抛</b>：只记成功的话，这张表回答不了
 * 「他为什么没收到」—— 而那正是它存在的全部理由。
 */
@Component
@Primary
public class NotifyLoggingSmsPort implements SmsPort {

    private final SmsPort delegate;
    private final NotifyLogWriter writer;

    public NotifyLoggingSmsPort(@Qualifier("smsGateway") SmsPort delegate, NotifyLogWriter writer) {
        this.delegate = delegate;
        this.writer = writer;
    }

    @Override
    public SendResult sendOtp(String phone, String code) {
        return sendOtp(phone, code, SysNotifyLog.BIZ_OTP, null);
    }

    /**
     * @param bizType    让运营端的「测试发送」与真实 OTP 在记录里分得开 ——
     *                   混在一起的话，看到发送量激增时分不清是有人在刷还是有人在测
     * @param operatorNo 手动触发时是操作人；OTP 这类自动发出的传 null
     */
    public SendResult sendOtp(String phone, String code, String bizType, String operatorNo) {
        try {
            SendResult r = delegate.sendOtp(phone, code);
            writer.write(SysNotifyLog.SMS, bizType, phone, r.templateCode(),
                    SysNotifyLog.SENT, null, r.providerMsgId(), operatorNo);
            return r;
        } catch (RuntimeException e) {
            writer.write(SysNotifyLog.SMS, bizType, phone, null,
                    SysNotifyLog.FAILED, e.getMessage(), null, operatorNo);
            throw e;
        }
    }
}
