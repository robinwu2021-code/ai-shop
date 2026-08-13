package ai.neargo.shop.message.notify;

import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.notify.port.NotifyLoggingMailPort;
import ai.neargo.shop.message.notify.port.NotifyLoggingSmsPort;
import ai.neargo.shop.spi.notify.MailPort;
import ai.neargo.shop.spi.notify.NotifyBizType;
import ai.neargo.shop.spi.notify.SendResult;
import ai.neargo.shop.spi.notify.SmsPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 记录装饰器的两条不变量。
 *
 * <p><b>「失败也记一条」只有在这里才验得了</b>：集成测试里通道是桩，桩不会失败，
 * 所以那条断言在集成层写不出来 —— 而它恰恰是这张表存在的全部理由
 * （只记成功的话，它回答不了「他为什么没收到」）。
 *
 * <p>用手写的假 delegate 与假 writer，不引 mock 框架：两个接口各一个方法，
 * 手写比配 mock 短，且断言的是**真实调用参数**而不是 mock 的录像。
 */
@DisplayName("发送记录装饰器")
class NotifyLoggingPortTest {

    /** 捕获写进记录表的那几个字段。继承而不是 mock —— write() 是 public 非 final */
    private static final class CapturingWriter extends NotifyLogWriter {

        record Row(String channel, String bizType, String target, String templateCode,
                   String status, String error, String providerMsgId, String operatorNo) {
        }

        final List<Row> rows = new ArrayList<>();

        CapturingWriter() {
            super(null);   // 不会用到 mapper：write 被整个覆盖
        }

        @Override
        public void write(String channel, String bizType, String targetPlain, String templateCode,
                          String status, String error, String providerMsgId, String operatorNo) {
            rows.add(new Row(channel, bizType, targetPlain, templateCode, status, error,
                    providerMsgId, operatorNo));
        }
    }

    @Test
    @DisplayName("★★★ 通道抛异常时先记一条 FAILED 再把异常抛出去 —— 不能吞")
    void failureIsRecordedThenRethrown() {
        CapturingWriter writer = new CapturingWriter();
        MailPort exploding = (to, subject, body) -> {
            throw new MailPort.MailException("SMTP 认证失败");
        };
        MailPort port = new NotifyLoggingMailPort(exploding, writer);

        assertThatThrownBy(() -> port.send("a@neargo.ai", "主题", "正文",
                NotifyBizType.OPS_INIT_PASSWORD, "ST-ADMIN"))
                .as("**必须原样抛出**：吞掉会让 createStaff 以为邮件发出去了，"
                        + "于是留下一个「已建号但没人知道密码」的账号")
                .isInstanceOf(MailPort.MailException.class);

        assertThat(writer.rows).hasSize(1);
        CapturingWriter.Row row = writer.rows.getFirst();
        assertThat(row.status()).isEqualTo(SysNotifyLog.FAILED);
        assertThat(row.error()).as("失败原因要留下 —— 它是排查时唯一的线索")
                .contains("SMTP 认证失败");
        assertThat(row.bizType()).isEqualTo(NotifyBizType.OPS_INIT_PASSWORD);
        assertThat(row.operatorNo()).isEqualTo("ST-ADMIN");
    }

    @Test
    @DisplayName("★★★ 短信同理：通道拒绝时记 FAILED 并抛")
    void smsFailureIsRecordedThenRethrown() {
        CapturingWriter writer = new CapturingWriter();
        SmsPort exploding = (phone, code) -> {
            throw new SmsPort.SmsException("isv.TEMPLATE_MISSING_PARAMETERS", false);
        };
        SmsPort port = new NotifyLoggingSmsPort(exploding, writer);

        assertThatThrownBy(() -> port.sendOtp("13900001111", "123456"))
                .isInstanceOf(SmsPort.SmsException.class);

        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.getFirst().status()).isEqualTo(SysNotifyLog.FAILED);
        assertThat(writer.rows.getFirst().error()).contains("TEMPLATE_MISSING_PARAMETERS");
        // 自动发出的没有操作人 —— 这一列为空是正常路径，不是漏填
        assertThat(writer.rows.getFirst().operatorNo()).isNull();
    }

    @Test
    @DisplayName("★★ 成功时把通道回执一起记下 —— providerMsgId 是找通道对账的唯一线索")
    void successRecordsProviderReceipt() {
        CapturingWriter writer = new CapturingWriter();
        SmsPort ok = (phone, code) -> SendResult.of("BizId-123", "SMS_474945291");
        SmsPort port = new NotifyLoggingSmsPort(ok, writer);

        port.sendOtp("13900001111", "123456");

        CapturingWriter.Row row = writer.rows.getFirst();
        assertThat(row.status()).isEqualTo(SysNotifyLog.SENT);
        assertThat(row.providerMsgId()).isEqualTo("BizId-123");
        assertThat(row.templateCode()).isEqualTo("SMS_474945291");
        assertThat(row.error()).isNull();
    }

    @Test
    @DisplayName("★★ 装饰器把明文交给 writer —— 掩码是 writer 的职责，两处各写一遍必然分叉")
    void decoratorPassesPlaintextAndWriterMasks() {
        CapturingWriter writer = new CapturingWriter();
        SmsPort port = new NotifyLoggingSmsPort((p, c) -> SendResult.none(), writer);

        port.sendOtp("13900001111", "123456");

        assertThat(writer.rows.getFirst().target())
                .as("装饰器传明文，掩码只在 NotifyLogWriter 里做一次")
                .isEqualTo("13900001111");
    }
}
