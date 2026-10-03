package ai.neargo.shop.message.notify;

import ai.neargo.shop.message.entity.SysNotifyLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * 企业微信群机器人（Webhook）。用来把「有人报名了」这类事推到运营的群里。
 *
 * <p><b>为什么是群机器人而不是自建应用</b>：群机器人只要一条 URL —— 不需要
 * access_token、不需要企业可信 IP 白名单、不需要用户授权，也不必申请模板。
 * 自建应用那条路要 corpid + agentid + secret 三样加白名单，为一条
 * 「有新意向」的通知不划算。代价是它只能发到群里，发不给指定的人。
 *
 * <p><b>URL 本身就是凭据</b>：拿到它的人都能往群里发消息。所以它只在
 * 服务器的 env 里（{@code SHOP_NOTIFY_WECOM_WEBHOOK}），不进库、不进仓库。
 * <b>也因此没有在 notify_channel 里登记一行</b> —— 那张表是给「运营能在后台配」的
 * 通道用的，为一个配在 env 里的东西加一行半真半假的记录，只会让那一屏说假话。
 *
 * <p><b>没配就静默跳过</b>：与门店链接、App 下载地址同一个口径 —— 缺配置时不发半截。
 */
@Component
public class WeComBotSender {

    private static final Logger log = LoggerFactory.getLogger(WeComBotSender.class);

    /** 企微对每个机器人的限制：每分钟 20 条。超了整条被拒，不是排队 */
    private static final int MAX_PER_MINUTE = 20;

    /**
     * 记进 {@code sys_notify_log} 的收件人标识。群机器人不是发给某个人的，
     * 而那一列会按手机号规则掩码（少于 8 位原样、否则头 3 尾 4）——
     * 用这个 7 位短串，掩码后仍然读得出是谁。
     */
    private static final String TARGET = "wecombot";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    private final NotifyLogWriter logWriter;
    private final String webhook;

    /**
     * 最近一分钟的发送时刻。**撞上限时丢最老的一条而不是最新的** ——
     * 「有人报名了」这种通知，新的比旧的有用。
     */
    private final Deque<Instant> recent = new ArrayDeque<>();

    public WeComBotSender(ObjectMapper json, NotifyLogWriter logWriter,
                          @Value("${shop.notify.wecom.webhook:}") String webhook) {
        this.json = json;
        this.logWriter = logWriter;
        this.webhook = webhook == null ? "" : webhook.trim();
    }

    /** 配了 URL 才发。端上/调用方据此决定要不要准备内容 */
    public boolean available() {
        return !webhook.isBlank();
    }

    /**
     * 发一条 markdown 消息到群。
     *
     * <p><b>失败不抛</b>：通知发不出去不该让下单、报名这些主流程回滚。
     * 但一定要写进 {@code sys_notify_log} —— 否则「消息没发出去」这件事
     * 没有任何症状，运营只会以为最近没人报名。
     * （实况：接线当天第一次调用返回 {@code 93000 invalid webhook url}，
     * 机器人刚建好、企微侧还没同步；第二次就通了。这种错只有日志能留下痕迹。）
     *
     * @return 真的发出去了 true
     */
    public boolean sendMarkdown(String bizType, String content) {
        if (!available() || content == null || content.isBlank()) {
            return false;
        }
        if (!allowNow()) {
            log.warn("企微群机器人撞到每分钟 {} 条上限，这一条丢弃 bizType={}", MAX_PER_MINUTE, bizType);
            logWriter.write(SysNotifyLog.WEBHOOK, bizType, TARGET, null, null,
                    SysNotifyLog.FAILED, "rate_limited", null, null, "WECOM");
            return false;
        }
        try {
            String body = json.writeValueAsString(Map.of(
                    "msgtype", "markdown",
                    "markdown", Map.of("content", content)));
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(webhook))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            /*
             * 企微**永远回 HTTP 200**，成败看 body 里的 errcode ——
             * 只判 statusCode 的话，key 失效、被限流、内容超长全都会被当成成功。
             */
            int code = json.readTree(resp.body()).path("errcode").asInt(-1);
            if (code == 0) {
                logWriter.write(SysNotifyLog.WEBHOOK, bizType, TARGET, null, null,
                        SysNotifyLog.SENT, null, null, null, "WECOM");
                return true;
            }
            String err = "errcode=" + code + " " + json.readTree(resp.body()).path("errmsg").asString("");
            log.warn("企微群机器人发送失败 {}", err);
            logWriter.write(SysNotifyLog.WEBHOOK, bizType, TARGET, null, null,
                    SysNotifyLog.FAILED, err, null, null, "WECOM");
            return false;
        } catch (Exception e) {
            log.warn("企微群机器人发送异常 bizType={} {}", bizType, e.toString());
            logWriter.write(SysNotifyLog.WEBHOOK, bizType, TARGET, null, null,
                    SysNotifyLog.FAILED, e.toString(), null, null, "WECOM");
            return false;
        }
    }

    /** 滑动窗口限流。同步块很短，量级也就每分钟几条 */
    private synchronized boolean allowNow() {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(60);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
            recent.pollFirst();
        }
        if (recent.size() >= MAX_PER_MINUTE) {
            return false;
        }
        recent.addLast(now);
        return true;
    }
}
