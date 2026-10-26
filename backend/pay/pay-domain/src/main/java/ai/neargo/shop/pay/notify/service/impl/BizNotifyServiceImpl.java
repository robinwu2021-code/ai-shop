package ai.neargo.shop.pay.notify.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.channel.api.CallerType;
import ai.neargo.shop.pay.mapper.notify.NotifyMappers.PayBizNotifyAttemptMapper;
import ai.neargo.shop.pay.mapper.notify.NotifyMappers.PayBizNotifyMapper;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayAppMapper;
import ai.neargo.shop.pay.notify.WebhookSender;
import ai.neargo.shop.pay.notify.WebhookTargets;
import ai.neargo.shop.pay.notify.entity.PayBizNotify;
import ai.neargo.shop.pay.notify.entity.PayBizNotifyAttempt;
import ai.neargo.shop.pay.notify.service.BizNotifyService;
import ai.neargo.shop.pay.openapp.AppKeyResolver;
import ai.neargo.shop.pay.openapp.SignatureCanonicalizer;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** {@link BizNotifyService} 的实现（TDD-支付服务-I5接入层 S6） */
@Service
public class BizNotifyServiceImpl implements BizNotifyService {

    private static final Logger log = LoggerFactory.getLogger(BizNotifyServiceImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 退避：30 秒起翻倍，封顶 2 小时；24 小时内约 15 次 */
    private static final long FIRST_BACKOFF_SECONDS = 30;
    private static final long MAX_BACKOFF_SECONDS = 7200;
    /** 投递租约：抢到后这么久内别的实例不再投同一行 */
    private static final long LEASE_SECONDS = 60;
    /** 对象状态版本：支付单与退款单的答复都只发终态，终态记 2（1 留给将来的「处理中」通知） */
    private static final int TERMINAL_STATE_VERSION = 2;

    private final PayBizNotifyMapper notifies;
    private final PayBizNotifyAttemptMapper attempts;
    private final PayAppMapper apps;
    private final AppKeyResolver keys;
    private final WebhookSender sender;
    private final Duration timeout;
    private final Duration giveUpAfter;
    private final boolean allowInsecure;

    public BizNotifyServiceImpl(PayBizNotifyMapper notifies, PayBizNotifyAttemptMapper attempts, PayAppMapper apps,
                                AppKeyResolver keys, WebhookSender sender,
                                @Value("${shop.pay.notify.webhook.timeout:PT5S}") Duration timeout,
                                @Value("${shop.pay.notify.webhook.give-up-after:PT24H}") Duration giveUpAfter,
                                @Value("${shop.pay.notify.webhook.allow-insecure-targets:false}") boolean allowInsecure) {
        this.notifies = notifies;
        this.attempts = attempts;
        this.apps = apps;
        this.keys = keys;
        this.sender = sender;
        this.timeout = timeout;
        this.giveUpAfter = giveUpAfter;
        this.allowInsecure = allowInsecure;
    }

    @Override
    public void paymentSucceeded(PayOrder order) {
        enqueue(order, null, "PAYMENT_SUCCEEDED", order.getPaidMinor());
    }

    @Override
    public void paymentClosed(PayOrder order) {
        enqueue(order, null, "PAYMENT_CLOSED", order.getAmountMinor());
    }

    @Override
    public void refundSucceeded(PayOrder order, PayRefund refund) {
        enqueue(order, refund, "REFUND_SUCCEEDED", refund.getAmountMinor());
    }

    @Override
    public void refundFailed(PayOrder order, PayRefund refund) {
        enqueue(order, refund, "REFUND_FAILED", refund.getAmountMinor());
    }

    /** 只答复第三方建的单（内部系统随 S6 事件、终端随 I7）；没登记地址或地址不合规不落行 */
    private void enqueue(PayOrder o, PayRefund r, String eventType, Long amount) {
        if (o == null || !CallerType.PARTNER.name().equals(o.getSourceType()) || o.getPayAppId() == null) {
            return;
        }
        PayApp app = DataScopeContext.executeWithoutScope(() -> apps.selectById(o.getPayAppId()));
        String url = o.getNotifyUrl() != null && !o.getNotifyUrl().isBlank() ? o.getNotifyUrl()
                : app == null ? null : app.getWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        if (!WebhookTargets.acceptable(url, allowInsecure)) {
            log.warn("[notify] 支付单 {} 的 webhook 地址不合规，不答复：{}", o.getPayOrderNo(), url);
            return;
        }
        String eventKey = eventType + "|" + (r == null ? "O" + o.getId() : "R" + r.getId()) + "|" + TERMINAL_STATE_VERSION;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        PayBizNotify n = new PayBizNotify();
        n.setTraceId(UUID.randomUUID().toString().replace("-", ""));
        n.setEventType(eventType);
        n.setEventKey(eventKey);
        n.setStateVersion(TERMINAL_STATE_VERSION);
        n.setTargetType("WEBHOOK");
        n.setPayAppId(o.getPayAppId());
        n.setTargetUrl(url);
        n.setPayOrderId(o.getId());
        n.setPayRefundId(r == null ? null : r.getId());
        n.setNotifyAmountMinor(amount);
        n.setNotifyCurrency(o.getCurrency());
        n.setPayloadMasked(payload(eventKey, eventType, now, o, r));
        n.setStatus("PENDING");
        n.setAttempts(0);
        n.setNextAt(now);
        // 放弃时限按建行时间算：显式写 UTC（列注释即 UTC），不交给按 JVM 时区填的审计字段 —— 否则 +04:00 的机器晚放弃 4 小时
        n.setCreatedAt(now);
        try {
            DataScopeContext.executeWithoutScope(() -> notifies.insert(n));
        } catch (DuplicateKeyException already) {
            // 同一事件重复到达（渠道重推、补查与回调撞车）：答复只落一次
        }
    }

    /** 推送体（对外契约，见接口清单 webhook 一节）：不含内部 id、收款商户、密钥版本 */
    private static String payload(String eventKey, String eventType, LocalDateTime at, PayOrder o, PayRefund r) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payOrderNo", o.getPayOrderNo());
        data.put("outOrderNo", o.getOutOrderNo());
        data.put("status", o.getStatus());
        data.put("amountMinor", o.getAmountMinor());
        data.put("paidMinor", o.getPaidMinor());
        data.put("refundedMinor", o.getRefundedMinor());
        data.put("currency", o.getCurrency());
        data.put("payChannel", o.getPayChannel());
        data.put("payMethod", o.getPayMethod());
        data.put("succeededAt", epoch(o.getSucceededAt()));
        data.put("closedAt", epoch(o.getClosedAt()));
        if (r != null) {
            data.put("payRefundNo", r.getPayRefundNo());
            data.put("outRefundNo", r.getOutRefundNo());
            data.put("refundAmountMinor", r.getAmountMinor());
            data.put("refundStatus", r.getStatus());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventKey);
        body.put("eventType", eventType);
        body.put("createdAt", epoch(at));
        body.put("data", data);
        return JSON.writeValueAsString(body);
    }

    @Override
    public int deliverDue(Instant now, int limit) {
        LocalDateTime t = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        List<PayBizNotify> due = DataScopeContext.executeWithoutScope(() -> notifies.selectList(
                Wrappers.<PayBizNotify>lambdaQuery().in(PayBizNotify::getStatus, "PENDING", "FAILED")
                        .eq(PayBizNotify::getTargetType, "WEBHOOK").le(PayBizNotify::getNextAt, t)
                        .orderByAsc(PayBizNotify::getNextAt).last("LIMIT " + Math.max(1, Math.min(limit, 500)))));
        int sent = 0;
        for (PayBizNotify n : due) {
            if (notifies.claim(n.getId(), n.getVersion(), t.plusSeconds(LEASE_SECONDS), t) != 1) {
                continue;
            }
            deliver(n, t, "AUTO", null);
            sent++;
        }
        return sent;
    }

    @Override
    public void redeliver(Long notifyId, String operatorNo) {
        PayBizNotify n = DataScopeContext.executeWithoutScope(() -> notifies.selectById(notifyId));
        if (n == null || !"WEBHOOK".equals(n.getTargetType())) {
            throw new IllegalArgumentException("没有这条 webhook 答复：" + notifyId);
        }
        deliver(n, LocalDateTime.now(ZoneOffset.UTC), "MANUAL", operatorNo);
    }

    private void deliver(PayBizNotify n, LocalDateTime now, String trigger, String operatorNo) {
        int attempt = (n.getAttempts() == null ? 0 : n.getAttempts()) + 1;
        long started = System.currentTimeMillis();
        Integer http = null;
        String response;
        String outcome;
        try {
            if (!WebhookTargets.acceptable(n.getTargetUrl(), allowInsecure)) {
                throw new IllegalStateException("目标地址此刻解析到不允许的地址");
            }
            WebhookSender.Response r = sender.post(n.getTargetUrl(), headers(n), n.getPayloadMasked(), timeout);
            http = r.status();
            response = r.body();
            outcome = r.status() >= 200 && r.status() < 300 ? "DELIVERED" : "REJECTED";
        } catch (java.net.http.HttpTimeoutException e) {
            response = "timeout";
            outcome = "TIMEOUT";
        } catch (Exception e) {
            response = e.getClass().getSimpleName() + ": " + e.getMessage();
            outcome = "ERROR";
        }
        PayBizNotifyAttempt a = new PayBizNotifyAttempt();
        a.setPayBizNotifyId(n.getId());
        a.setAttempt(attempt);
        a.setTriggerType(trigger);
        a.setTriggeredBy(operatorNo);
        a.setSentAt(now);
        a.setCreatedAt(now);
        a.setHttpStatus(http);
        a.setResponseMasked(response == null ? null : response.length() > 1024 ? response.substring(0, 1024) : response);
        a.setElapsedMs((int) (System.currentTimeMillis() - started));
        a.setOutcome(outcome);
        DataScopeContext.executeWithoutScope(() -> attempts.insert(a));

        PayBizNotify patch = new PayBizNotify();
        patch.setId(n.getId());
        patch.setAttempts(attempt);
        if ("DELIVERED".equals(outcome)) {
            patch.setStatus("DELIVERED");
            patch.setDeliveredAt(now);
        } else if (!"MANUAL".equals(trigger) && !now.isBefore(n.getCreatedAt().plus(giveUpAfter))) {
            patch.setStatus("GIVEN_UP");
            patch.setGivenUpAt(now);
            log.warn("[notify] 答复 {}（{}）超过 {} 仍未送达，放弃；运营可手动重推", n.getId(), n.getEventKey(), giveUpAfter);
        } else {
            patch.setStatus("FAILED");
            patch.setNextAt(now.plusSeconds(backoffSeconds(attempt)));
        }
        DataScopeContext.executeWithoutScope(() -> notifies.updateById(patch));
    }

    static long backoffSeconds(int attempt) {
        return Math.min(MAX_BACKOFF_SECONDS, FIRST_BACKOFF_SECONDS << Math.min(attempt - 1, 20));
    }

    /** 与入站请求同一套头与待签串（安全规范 §2.3）：METHOD=POST，PATH 为目标地址的 path，用接入方当前密钥签 */
    private Map<String, String> headers(PayBizNotify n) throws Exception {
        PayApp app = DataScopeContext.executeWithoutScope(() -> apps.selectById(n.getPayAppId()));
        List<String> secrets = app == null ? List.of() : keys.hmacSecretsOf(app.getAppNo());
        if (secrets.isEmpty()) {
            throw new IllegalStateException("接入方没有可用的签名密钥");
        }
        URI u = URI.create(n.getTargetUrl());
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        byte[] body = n.getPayloadMasked().getBytes(StandardCharsets.UTF_8);
        String toSign = SignatureCanonicalizer.stringToSign("POST", u.getRawPath() == null || u.getRawPath().isEmpty()
                        ? "/" : u.getRawPath(), SignatureCanonicalizer.parseQuery(u.getRawQuery()), app.getAppNo(), ts, nonce,
                SignatureCanonicalizer.sha256Hex(body));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secrets.get(0).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json; charset=UTF-8");
        h.put("X-App-Id", app.getAppNo());
        h.put("X-Timestamp", ts);
        h.put("X-Nonce", nonce);
        h.put("X-Signature", Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8))));
        h.put("X-Event-Id", n.getEventKey());
        return h;
    }

    private static Long epoch(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
