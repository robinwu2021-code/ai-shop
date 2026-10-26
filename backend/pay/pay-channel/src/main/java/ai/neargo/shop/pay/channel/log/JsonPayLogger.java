package ai.neargo.shop.pay.channel.log;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
            f.put("channelCode", fail.channelCode());
        } else if (result instanceof ChannelResult.Unknown u) {
            f.put("reason", u.reason());
        } else if (result instanceof ChannelResult.Pending p && p.channelStatus() != null) {
            // 渠道原始状态 / 处理器的诊断（如微信 paySign 自验）：处理器不打日志，由这一行带出
            f.put("channelStatus", p.channelStatus());
        }
        String line = write(f);
        switch (result) {
            case ChannelResult.Unknown u -> log.error(line);
            case ChannelResult.Failed fail -> log.warn(line);
            default -> log.info(line);
        }
    }

    @Override
    public void anomaly(String type, Map<String, Object> fields) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("ts", Instant.now().toString());
        f.put("direction", "ANOMALY");
        f.put("type", type);
        f.putAll(fields);
        log.error(write(f));
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
            return fields.toString();
        }
    }
}
