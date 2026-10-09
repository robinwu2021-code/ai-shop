package ai.neargo.shop.message.notify;

import ai.neargo.shop.message.entity.SysNotifyLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import java.util.HashMap;
import java.util.Map;

/**
 * 企业微信群机器人（Webhook）。用来把「有人报名了」这类事推到运营的群里。
 *
 * <p><b>为什么是群机器人而不是自建应用</b>：群机器人只要一条 URL —— 不需要
 * access_token、不需要企业可信 IP 白名单、不需要用户授权，也不必申请模板。
 * 自建应用那条路要 corpid + agentid + secret 三样加白名单，为一条
 * 「有新意向」的通知不划算。代价是它只能发到群里，发不给指定的人。
 *
 * <p><b>URL 本身就是凭据</b>：拿到它的人都能往群里发消息。这一条不变，变的是存哪 ——
 * 现在有两种群，凭据各有各的去处：
 *
 * <ul>
 *   <li><b>平台那条</b>（入驻通知）：只在服务器 env 里（{@code SHOP_NOTIFY_WECOM_WEBHOOK}），
 *       不进库、不进仓库。<b>它没有在 notify_channel 里登记一行</b> —— 那张表是给
 *       「运营能在后台配、有 owner」的通道用的，为一个配在 env 里的东西加一行半真半假的
 *       记录，只会让那一屏说假话。</li>
 *   <li><b>商家自己那条</b>（来单提醒）：进 {@code notify_channel} 的
 *       {@code scope=MERCHANT} 行，URL 作为凭据存 {@code secret_cipher}（AES-256-GCM），
 *       明文永不落库、永不回前端。进库是因为它**按商家各一条**，env 装不下也不该装
 *       （TDD-商家企微群来单通知 §2.1）。解析走 {@code MerchantWecomWebhook}。</li>
 * </ul>
 *
 * <p><b>没配就静默跳过</b>：与门店链接、App 下载地址同一个口径 —— 缺配置时不发半截。
 */
@Component
@ConditionalOnProperty(name = "shop.notify.wecom.stub", havingValue = "false", matchIfMissing = true)
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
     * 最近一分钟的发送时刻，**按群分桶**。
     *
     * <p><b>为什么必须按群分，哪怕现在只有一个群</b>：企微的「每分钟 20 条」是**对每个机器人**的，
     * 而这里一度是一个全局队列 —— 自营阶段看不出任何差异，等商家多起来之后，
     * 一家刷满 20 条就会把别家的来单提醒**静默挤掉**（{@link #allowNow} 返回 false
     * 只留一行 WARN，群里什么都没有）。那种缺陷在「多商家了再说」的时候最难发现，
     * 所以在只有一个群的时候就把它改对（TDD-商家企微群来单通知 §2.3）。
     *
     * <p>桶里只留最近 60 秒，{@link #allowNow} 每次顺手清掉过期的键，不会无界增长。
     */
    private final Map<String, Deque<Instant>> recent = new HashMap<>();

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
        return available() && sendMarkdown(bizType, content, webhook);
    }

    /**
     * 发一条 markdown 消息到**指定的群**。
     *
     * <p>商家自己的群走这条（{@code MerchantWecomWebhook} 解析出来的 URL）；
     * 平台那条入驻通知走上面的无参方法。两条路共用同一套限流、留痕与 errcode 判定，
     * 唯一的差别是「发到哪个 URL」。
     *
     * <p><b>webhook 为空就不发</b>，与「没配就静默跳过」同一个口径 ——
     * 调用方拿不到 URL 时可以直接调进来，不必自己判一遍。
     *
     * @param webhook 目标群的 Webhook URL。**调用方不要把它写进日志或响应体**：它是凭据
     * @return 真的发出去了 true
     */
    public boolean sendMarkdown(String bizType, String content, String webhook) {
        if (webhook == null || webhook.isBlank() || content == null || content.isBlank()) {
            return false;
        }
        if (!allowNow(webhook)) {
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

    /**
     * 滑动窗口限流，**一个群一个窗口**。同步块很短，量级也就每分钟几条。
     *
     * <p>包可见而非私有：限流是这个类里唯一「不发 HTTP 也能验」的行为，
     * 单测直接把某个群的桶打满来断言「不影响另一个群」。
     */
    synchronized boolean allowNow(String webhook) {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(60);
        // 顺手清掉整分钟没动静的群，桶不会随商家数无界增长
        recent.entrySet().removeIf(e -> {
            Deque<Instant> q = e.getValue();
            while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
                q.pollFirst();
            }
            return q.isEmpty();
        });
        Deque<Instant> q = recent.computeIfAbsent(webhook, k -> new ArrayDeque<>());
        if (q.size() >= MAX_PER_MINUTE) {
            return false;
        }
        q.addLast(now);
        return true;
    }
}
