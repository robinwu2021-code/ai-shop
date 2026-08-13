package ai.neargo.shop.message.notify;

import ai.neargo.shop.auth.RequestMetaContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.Masks;
import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.mapper.MessageMappers.NotifyLogMapper;
import ai.neargo.common.data.scope.DataScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 往 {@code sys_notify_log} 写一条。两个装饰器共用。
 *
 * <p><b>{@code REQUIRES_NEW}</b>：发送记录必须与业务事务解耦。
 * 新建运营账号时邮件发失败要回滚建号（不能留一个没人知道密码的账号），
 * 但**那条失败记录不能跟着回滚掉** —— 回滚了就等于「发失败」这件事从未发生，
 * 而它恰恰是最需要被看到的一条。
 *
 * <p><b>写日志本身失败绝不能影响发送</b>：记录是辅助，通道是主线。
 * 这里吞掉异常并打 ERROR —— 唯一一处该吞的地方。
 */
@Component
public class NotifyLogWriter {

    private static final Logger log = LoggerFactory.getLogger(NotifyLogWriter.class);

    private final NotifyLogMapper mapper;

    public NotifyLogWriter(NotifyLogMapper mapper) {
        this.mapper = mapper;
    }

    /** 目标地址由调用方传**明文**，这里负责掩码——省得每个调用点各写一遍、各写各的口径。 */
    public void write(String channel, String bizType, String targetPlain, String templateCode,
                      String status, String error, String providerMsgId, String operatorNo) {
        try {
            SysNotifyLog row = new SysNotifyLog();
            row.setLogNo(BizKey.next(BizKey.NOTIFY_LOG));
            row.setChannel(channel);
            row.setBizType(bizType);
            row.setTarget(SysNotifyLog.MAIL.equals(channel)
                    ? Masks.email(targetPlain) : Masks.phone(targetPlain));
            row.setTemplateCode(truncate(templateCode, 64));
            row.setStatus(status);
            row.setError(truncate(error, 512));
            row.setProviderMsgId(truncate(providerMsgId, 64));
            row.setOperatorNo(operatorNo);
            RequestMetaContext.Meta meta = RequestMetaContext.current();
            row.setClientIp(meta == null ? null : meta.ip());
            row.setCreatedAt(LocalDateTime.now());
            // 这张表没有数据域概念（它是平台侧的运维记录），绕开拦截器
            DataScopeContext.executeWithoutScope(() -> mapper.insert(row));
        } catch (RuntimeException e) {
            log.error("[notify] 写发送记录失败 channel={} biz={} —— 不影响发送本身",
                    channel, bizType, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeInNewTx(String channel, String bizType, String targetPlain, String templateCode,
                             String status, String error, String providerMsgId, String operatorNo) {
        write(channel, bizType, targetPlain, templateCode, status, error, providerMsgId, operatorNo);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
