package ai.neargo.shop.message.notify;

import ai.neargo.shop.message.entity.SysNotifyLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 企微群机器人的桩：**只记下来，不发 HTTP**（{@code shop.notify.wecom.stub=true}）。
 *
 * <p>与 {@code StubPushGateway} / {@code StubWxSubscribeGateway} 同一个形状，
 * 理由也同：场景测试要验「发了什么、发给哪个群」，而真发会往企业微信打一次请求 ——
 * 要么真的在群里刷消息，要么等 5 秒超时。
 *
 * <p><b>为什么用桩 bean 而不是 {@code @MockitoBean}</b>：
 * {@code @MockitoBean} 会改变 Spring 的上下文缓存键，于是那个测试类**另起一个上下文**；
 * 而 shop-app 的测试共用一个命名 H2 库，第二个上下文会把 {@code schema-test.sql}
 * 重跑一遍并撞上唯一键（{@code mch_admission_policy}）——
 * 症状是「单独跑绿、和别人一起跑红」，这次就是这么撞上的。
 */
@Component
@ConditionalOnProperty(name = "shop.notify.wecom.stub", havingValue = "true")
public class StubWeComBotSender extends WeComBotSender {

    private static final Logger log = LoggerFactory.getLogger(StubWeComBotSender.class);

    /** 一次发送：发到哪个群、发了什么 */
    public record Sent(String bizType, String content, String webhook) {
    }

    /** 与真实实现同一个收件人标识（群机器人不是发给某个人的） */
    private static final String TARGET_STUB = "wecombot";

    private final List<Sent> sent = new ArrayList<>();
    private final NotifyLogWriter logWriter;

    /** 下一次发送是否模拟抛异常（验「群发炸了不拖累其余出口」） */
    private volatile boolean failNext;

    public StubWeComBotSender(ObjectMapper json, NotifyLogWriter logWriter,
                              @Value("${shop.notify.wecom.webhook:}") String webhook) {
        super(json, logWriter, webhook);
        this.logWriter = logWriter;
    }

    /*
     * **不 override available()**：它回答的是「平台那条 env 配了没有」，
     * 而桩改的只是「发不发 HTTP」。第一版让它恒 true，当场把
     * MerchantApplyOpsAlertFlowTest#inAppStillWorksWhenWebhookUnconfigured 弄红了 ——
     * 那条测的正是「平台那条没配时站内信照旧」，桩谎报「配了」就把它测的那一半抹掉了。
     */

    @Override
    public boolean sendMarkdown(String bizType, String content, String webhook) {
        if (webhook == null || webhook.isBlank() || content == null || content.isBlank()) {
            return false;
        }
        if (failNext) {
            failNext = false;
            /*
             * 失败也留痕 —— 与真实实现同一个口径（见下面那段注释）。
             * 不写的话，「发失败了会不会记一行」这件事在测试里没有量具。
             */
            logWriter.write(SysNotifyLog.WEBHOOK, bizType, TARGET_STUB, null, null,
                    SysNotifyLog.FAILED, "[stub] 模拟失败", null, null, "WECOM");
            throw new IllegalStateException("[stub] 企微群机器人模拟失败");
        }
        synchronized (sent) {
            sent.add(new Sent(bizType, content, webhook));
        }
        /*
         * **桩也要写 sys_notify_log。**
         *
         * 桩替换的只是「发 HTTP」这一步，留痕是发送器的另一半职责 ——
         * 少做它的后果是「企微有没有留痕」这件事在测试里永远验不到：
         * 2026-10-10 加端到端留痕覆盖时，四条通道里偏偏就缺它这一行，
         * 而生产上其实是有的。桩与真实实现的行为分叉，比桩本身更难发现。
         */
        logWriter.write(SysNotifyLog.WEBHOOK, bizType, TARGET_STUB, null, null,
                SysNotifyLog.SENT, null, null, null, "WECOM");
        log.info("[stub] 企微群机器人 bizType={} 群={} 内容={}", bizType, mask(webhook), content);
        return true;
    }

    public List<Sent> sent() {
        synchronized (sent) {
            return List.copyOf(sent);
        }
    }

    public void clear() {
        synchronized (sent) {
            sent.clear();
        }
        failNext = false;
    }

    public void failNext() {
        this.failNext = true;
    }

    /** 日志里不留整条 URL —— 它是凭据，桩世界里也一样 */
    private static String mask(String webhook) {
        int i = webhook.indexOf("key=");
        return i < 0 ? "…" : "…key=" + webhook.substring(i + 4).replaceAll("(?<=.{4}).", "*");
    }
}
