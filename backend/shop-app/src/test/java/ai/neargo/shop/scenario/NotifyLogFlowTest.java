package ai.neargo.shop.scenario;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.notify.NotifyLogService;
import ai.neargo.shop.spi.notify.MailPort;
import ai.neargo.shop.spi.notify.NotifyBizType;
import ai.neargo.shop.spi.notify.SmsPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 发送记录（P-14.3）。
 *
 * <p><b>这个类补的是一个空白</b>：装饰器最要紧的两条不变量此前只写在注释里 ——
 * 「失败也记一条」与「收件人存掩码」。而它们恰恰是这张表存在的全部理由：
 * 只记成功的话，它回答不了「他为什么没收到」；存明文的话，
 * 一张全运营可见的表就成了用户手机号与邮箱的清单。
 *
 * <p>自己一个内存库：{@code properties} 会造出第二个 Spring 上下文，
 * 而 h2db 的库是命名共享的（见 {@code OtpRateLimitFlowTest} 顶部那段）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notify-log;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
@ActiveProfiles("test")
@DisplayName("短信邮件发送记录")
class NotifyLogFlowTest {

    @Autowired
    private SmsPort smsPort;

    @Autowired
    private MailPort mailPort;

    @Autowired
    private NotifyLogService notifyLogService;

    private PageData<SysNotifyLog> logs() {
        // 这条 helper 要的是「全部记录」，各维都传 null
        return notifyLogService.list(null, null, null, null, null, null, null, 1, 50);
    }

    @Test
    @DisplayName("★★★ 输完整手机号查得到 —— 库里存的是掩码，不转换的话运营查什么都是空")
    void searchByPlainPhoneFindsTheMaskedRow() {
        String phone = "13700002222";
        smsPort.sendOtp(phone, "123456", NotifyBizType.OTP, null);

        /*
         * 运营手上唯一有的就是完整手机号。这一条挂掉的表现是「查不到」——
         * 而「查不到」会被读成「这条根本没发出去」，排查当场走错方向。
         */
        var byPlain = notifyLogService.list(null, null, null, null, null, null, phone, 1, 50);
        assertThat(byPlain.records())
                .as("输完整手机号必须命中那条掩码记录")
                .extracting(SysNotifyLog::getTarget)
                .contains("137****2222");

        // 尾四位这种片段输入也要能用 —— 运营常常只记得后四位
        var byTail = notifyLogService.list(null, null, null, null, null, null, "2222", 1, 50);
        assertThat(byTail.records()).isNotEmpty();

        // 不相干的号码不能被模糊匹配捞进来
        var miss = notifyLogService.list(null, null, null, null, null, null, "18600009999", 1, 50);
        assertThat(miss.records())
                .extracting(SysNotifyLog::getTarget)
                .doesNotContain("137****2222");
    }

    @Test
    @DisplayName("★★ 「今天到今天」查得到今天的 —— 截止日按闭区间写的话这里会是空")
    void todayToTodayIncludesToday() {
        smsPort.sendOtp("13500003333", "123456", NotifyBizType.OTP, null);
        String today = java.time.LocalDate.now().toString();

        var rows = notifyLogService.list(null, null, null, null, today, today, "13500003333", 1, 50);
        assertThat(rows.records())
                .as("到货日期含当天，实现里要按次日零点的开区间")
                .isNotEmpty();

        // 昨天以前的窗口里不该有它
        String yesterday = java.time.LocalDate.now().minusDays(1).toString();
        var none = notifyLogService.list(null, null, null, null, null, yesterday, "13500003333", 1, 50);
        assertThat(none.records()).isEmpty();
    }

    @Test
    @DisplayName("★★★ 注入到域里的必须是带记录的那个 —— 否则发出去了却什么都不留")
    void theInjectedPortIsTheLoggingDecorator() {
        // @Primary 的装饰器包住桩/真实现。拿到裸桩就说明装配错了，
        // 而症状是「功能都正常，只是记录表永远空着」—— 没有任何报错
        assertThat(smsPort.getClass().getSimpleName()).isEqualTo("NotifyLoggingSmsPort");
        assertThat(mailPort.getClass().getSimpleName()).isEqualTo("NotifyLoggingMailPort");
    }

    @Test
    @DisplayName("★★★ 收件人存掩码 —— 这张表全运营可见，而它是用户的手机号")
    void recipientIsMasked() {
        String phone = "13900001111";
        smsPort.sendOtp(phone, "123456", NotifyBizType.OTP, null);

        SysNotifyLog row = logs().records().stream()
                .filter(r -> NotifyBizType.OTP.equals(r.getBizType()))
                .findFirst().orElseThrow(() -> new AssertionError("没记下这条发送"));

        assertThat(row.getTarget()).isEqualTo("139****1111");
        assertThat(row.getTarget()).as("明文手机号绝不能落库").isNotEqualTo(phone);
        assertThat(row.getStatus()).isEqualTo(SysNotifyLog.SENT);
    }

    @Test
    @DisplayName("★★ 验证码没过时不产生发送记录 —— 那一条压根没走到通道")
    void captchaRejectionLeavesNoRecord() {
        /*
         * **「失败也记一条」不在这里验** —— 集成层的通道是桩，桩不会失败，
         * 那条断言在这里写不出来（第一版硬写了一个，名字叫「失败也记一条」，
         * 实际只验了「异常没被吞」，是个名不副实的测试）。
         * 真正那条在 NotifyLoggingPortTest 里，用会抛的假 delegate 验。
         *
         * 这里验的是另一件事：验证码这道闸拦下时，不该留下发送记录 ——
         * 否则记录里会混进一堆压根没发生过的发送，而这张表的价值全在「它记的是事实」。
         */
        assertThatThrownBy(() -> notifyLogService.testSend("MAIL", "boom@neargo.ai", null,
                null, null, "no-such-captcha", "0000", "ST-TEST"))
                .as("图形验证码不对时必须抛 —— 那道闸是这个接口的主要保护")
                .isInstanceOf(RuntimeException.class);

        assertThat(logs().records())
                .noneMatch(r -> NotifyBizType.TEST.equals(r.getBizType()));
    }

    @Test
    @DisplayName("★★ 用途分得开 —— 混在一起时看到发送量激增分不清是有人在刷还是有人在测")
    void bizTypesAreDistinguishable() {
        mailPort.send("someone@neargo.ai", "【数智邻购】运营端账号已开通", "初始密码：Ab3xY9zQ",
                NotifyBizType.OPS_INIT_PASSWORD, "ST-ADMIN");

        SysNotifyLog row = logs().records().stream()
                .filter(r -> NotifyBizType.OPS_INIT_PASSWORD.equals(r.getBizType()))
                .findFirst().orElseThrow();

        assertThat(row.getChannel()).isEqualTo(SysNotifyLog.MAIL);
        assertThat(row.getOperatorNo()).as("手动触发的要记下是谁").isEqualTo("ST-ADMIN");
        // 邮件没有模板号，主题记进 templateCode —— 列表页要能一眼看出这是哪类邮件
        assertThat(row.getTemplateCode()).contains("运营端账号已开通");
        assertThat(row.getTarget()).as("邮箱同样掩码").isEqualTo("s***e@neargo.ai");
    }
}
