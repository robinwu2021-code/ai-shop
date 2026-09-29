package ai.neargo.shop.elec.svc;

import ai.neargo.shop.elec.gateway.ElecAlerts;
import ai.neargo.shop.elec.support.AlertText;
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
 * 企业微信群机器人，本进程自己发（不经主系统）。
 *
 * <p><b>URL 本身就是凭据</b>：只在服务器 env 里，不进仓库。空 = 不发。
 * <b>默认复用主系统那条</b>（{@code SHOP_NOTIFY_WECOM_WEBHOOK}，收入驻意向的那个群）；
 * 要单独建元器件群就配 {@code ELEC_WECOM_WEBHOOK} 盖过它。
 *
 * <p><b>成败看 body 的 errcode</b>，不看 HTTP 状态：企微永远回 200，
 * 只判状态码的话 key 失效、被限流、内容超长全都会被当成成功。
 */
@Component
public class WeComElecAlerts implements ElecAlerts {

    private static final Logger log = LoggerFactory.getLogger(WeComElecAlerts.class);

    /** 企微机器人每分钟 20 条；留两条余量 */
    private static final int MAX_PER_MINUTE = 18;

    private final String webhook;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final Deque<Instant> sent = new ArrayDeque<>();

    public WeComElecAlerts(@Value("${elec.wecom.webhook:}") String webhook, ObjectMapper json) {
        // 值来自 elec.wecom.webhook，它默认回落到主系统的 SHOP_NOTIFY_WECOM_WEBHOOK（见 application.yml）
        this.webhook = webhook == null ? "" : webhook.trim();
        this.json = json;
    }

    @Override
    public boolean newSupplier(SupplierAlert s) {
        return send("ELEC_SUPPLIER", AlertText.supplier(s));
    }

    @Override
    public boolean newRfq(RfqAlert rfq) {
        return send("ELEC_RFQ", AlertText.rfq(rfq));
    }

    @Override
    public boolean rfqAccepted(String rfqNo, String contactName, String contactPhone, String summary) {
        return send("ELEC_RFQ_ACCEPTED", AlertText.accepted(rfqNo, contactName, contactPhone, summary));
    }

    @Override
    public boolean supplierQuoted(QuoteAlert q) {
        return send("ELEC_QUOTE", AlertText.quoted(q));
    }

    @Override
    public boolean supplierDeclined(DeclineAlert d) {
        return send(d.lineAllDeclined() ? "ELEC_LINE_ALL_DECLINED" : "ELEC_DECLINE", AlertText.declined(d));
    }

    boolean send(String kind, String markdown) {
        if (webhook.isEmpty()) {
            return false;
        }
        if (!allowNow()) {
            log.warn("企微群机器人撞到每分钟 {} 条上限，这一条丢弃 kind={}", MAX_PER_MINUTE, kind);
            return false;
        }
        try {
            String body = json.writeValueAsString(Map.of("msgtype", "markdown",
                    "markdown", Map.of("content", markdown)));
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(webhook))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            int code = json.readTree(resp.body()).path("errcode").asInt(-1);
            if (code == 0) {
                return true;
            }
            log.warn("企微群机器人发送失败 kind={} body={}", kind, resp.body());
            return false;
        } catch (Exception e) {
            log.warn("企微群机器人发送异常 kind={} {}", kind, e.toString());
            return false;
        }
    }

    private synchronized boolean allowNow() {
        Instant now = Instant.now();
        while (!sent.isEmpty() && sent.peekFirst().isBefore(now.minusSeconds(60))) {
            sent.pollFirst();
        }
        if (sent.size() >= MAX_PER_MINUTE) {
            return false;
        }
        sent.addLast(now);
        return true;
    }
}
