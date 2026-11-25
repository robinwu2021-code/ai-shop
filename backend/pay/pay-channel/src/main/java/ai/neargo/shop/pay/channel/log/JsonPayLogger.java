package ai.neargo.shop.pay.channel.log;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * 单行 JSON、固定字段（渠道接入统一封装设计 §10）：日志平台按字段检索，不靠正则抠。
 * 级别：成功 / 受理 INFO，失败 WARN，<b>不明 ERROR</b>（要告警：钱可能已经动了）。
 * 只写金额、来源、键与结果，<b>不写报文</b> —— 报文的脱敏副本在调用记录里，日志里不出现任何账号、卡号、签名。
 */
public final class JsonPayLogger implements PayLogger {

    private static final Logger log = LoggerFactory.getLogger("pay.channel");
    private static final Set<String> REASONS = Set.of(
            "AMOUNT_UNPARSEABLE", "BAD_SIGNATURE", "INTERRUPTED", "PAYMENT_RECEIPT_INVALID_AMOUNT",
            "PAYMENT_RECEIPT_MISMATCH", "PAY_SEND_UNKNOWN", "PREPAY_ID_MISSING", "QUERY_FAILED",
            "REFUND_IDENTITY_MISMATCH", "REFUND_SEND_UNKNOWN", "REFUND_SEND_UNPROVEN",
            "REFUND_STATUS_UNKNOWN", "SHUTDOWN_DRAIN_TIMEOUT", "SPLIT_RECEIPT_WITHOUT_ID",
            "SUB_MERCHANT_UNKNOWN", "TIMEOUT", "UNEXPECTED", "UNMAPPED_CODE", "UNPARSEABLE");
    private static final Set<String> PENDING_STATUSES = Set.of(
            "NOTPAY", "USERPAYING", "PROCESSING", "REFUND", "WAIT_BUYER_PAY",
            "PAYSIGN_SELF_CHECK_OK", "PAYSIGN_SELF_CHECK_FAILED");

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public void invocationStarted(Invocation inv) {
        Map<String, Object> f = base(inv, "CALL_START");
        log.info(write(f));
    }

    @Override
    public void invocationFinished(Invocation inv, ChannelResult result) {
        Map<String, Object> f = base(inv, "CALL_END");
        f.put("outcome", result.outcomeClass());
        f.put("elapsedMs", inv.elapsed().toMillis());
        if (result instanceof ChannelResult.Failed fail) {
            f.put("errorCode", fail.code().name());
        } else if (result instanceof ChannelResult.Unknown u) {
            f.put("reasonCode", reasonCode(u.reason()));
        } else if (result instanceof ChannelResult.Pending pending && pending.channelStatus() != null
                && PENDING_STATUSES.contains(pending.channelStatus())) {
            f.put("channelStatus", pending.channelStatus());
        }
        String line = write(f);
        switch (result) {
            case ChannelResult.Unknown u -> log.error(line);
            case ChannelResult.Failed fail -> log.warn(line);
            default -> log.info(line);
        }
    }

    // Internal adapters may append raw diagnostics after a colon. Only the known code is loggable.
    private static String reasonCode(String reason) {
        if (reason == null) {
            return "UNCLASSIFIED";
        }
        int separator = reason.indexOf(':');
        String code = separator < 0 ? reason : reason.substring(0, separator);
        return REASONS.contains(code) || code.matches("HTTP_5[0-9]{2}") ? code : "UNCLASSIFIED";
    }

    private static Map<String, Object> base(Invocation inv, String event) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("ts", Instant.now().toString());
        f.put("direction", "CALL");
        f.put("event", event);
        f.put("traceId", inv.command().meta().traceId());
        f.put("invocationNo", inv.invocationNo());
        f.put("market", inv.key().market());
        f.put("channel", inv.key().payChannel());
        f.put("scene", inv.key().scene().name());
        f.put("operation", inv.key().operation().name());
        f.put("attempt", inv.attempt());
        f.put("outNo", inv.command().outNo());
        Money amount = inv.command().requestAmount();
        if (amount != null) {
            f.put("amountMinor", amount.minor());
            f.put("currency", amount.currency());
        }
        f.put("sourceType", inv.command().meta().source().sourceType().name());
        f.put("sourceId", inv.command().meta().source().sourceId());
        f.put("clientType", inv.command().meta().source().clientType().name());
        return f;
    }

    private String write(Map<String, Object> fields) {
        try {
            return json.writeValueAsString(fields);
        } catch (RuntimeException e) {
            return "{\"event\":\"LOG_ENCODING_FAILED\"}";
        }
    }
}
