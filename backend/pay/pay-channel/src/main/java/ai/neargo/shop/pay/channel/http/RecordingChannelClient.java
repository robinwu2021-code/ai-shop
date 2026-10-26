package ai.neargo.shop.pay.channel.http;

import ai.neargo.shop.pay.channel.ChannelMessageRecorder;
import ai.neargo.shop.pay.channel.PayloadMasker;
import ai.neargo.shop.pay.channel.api.ChannelTransportException;
import ai.neargo.shop.pay.channel.entity.PayChannelCallLog;
import ai.neargo.shop.pay.channel.entity.PayRawPayload;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.pay.channel.invoke.record.CallLogStore;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 所有出站渠道 HTTP 的唯一出口（Decorator，渠道接入统一封装设计 §1、§4；TDD-支付服务-W2渠道层补完 S3）。
 *
 * <p>每发一次：
 * <ol>
 *   <li>发出前把<b>真实</b>方法与路径、请求脱敏副本、原文引用写进这次调用的 {@code pay_channel_call_log} 行
 *       （I2 时这两列恒为占位 {@code -}，报文一个字都没有）；</li>
 *   <li>原文进 {@code pay_raw_payload}（{@link RawPayloadSealer}：W5 前存脱敏全文）；</li>
 *   <li>标记「已发出」—— 只有连接没建成（拒绝、DNS、连接超时）才撤回，那种请求渠道一定没收到，可以安全重发；</li>
 *   <li>应答回来后写 HTTP 状态、应答脱敏副本与原文；旧报文表 {@code stl_channel_message} 按开关照写一行。</li>
 * </ol>
 * IO 失败在这里归成 {@link ChannelTransportException}（{@code CONNECT_FAILED} / {@code TIMEOUT}），处理器不碰 IO 异常。
 *
 * <p><b>记录失败不挡发送</b>：钱的事不能因为日志表写不进去就不做。失败计数并 WARN。
 * 管线外的调用（进件）没有调用记录行，只落原文与旧报文表。
 */
public final class RecordingChannelClient {

    private static final Logger log = LoggerFactory.getLogger(RecordingChannelClient.class);
    private static final int PATH_MAX = 255;
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final ChannelTransport transport;
    private final CallLogStore callLogs;
    private final RawPayloadStore raws;
    private final RawPayloadSealer sealer;
    private final ChannelMessageRecorder legacy;
    private final int retentionDays;
    private final AtomicLong failures = new AtomicLong();

    /**
     * @param legacy        旧报文表；为空则不写（独立 pay-svc、单测）
     * @param retentionDays 原文保留天数（{@code retain_until}）
     */
    public RecordingChannelClient(ChannelTransport transport, CallLogStore callLogs, RawPayloadStore raws,
                                  RawPayloadSealer sealer, ChannelMessageRecorder legacy, int retentionDays) {
        this.transport = transport;
        this.callLogs = callLogs;
        this.raws = raws;
        this.sealer = sealer;
        this.legacy = legacy;
        this.retentionDays = retentionDays;
    }

    /**
     * @param inv 管线内的这次调用；管线外（进件）为 null
     * @param api 渠道接口名，进旧报文表
     * @throws ChannelTransportException 连接没建成（{@code CONNECT_FAILED}）或发出后没拿到应答（{@code TIMEOUT}）
     */
    public ChannelHttpResponse send(Invocation inv, String payChannel, String api, ChannelHttpRequest request,
                                    Duration readTimeout) {
        String requestPlain = plainOf(request);
        String requestMasked = PayloadMasker.maskText(maskedEnvelope(request.headers(), request.body()));
        Long requestRawId = raw("CALL_REQUEST", requestPlain, requestMasked);
        PayChannelCallLog before = patch(inv);
        if (before != null) {
            before.setHttpMethod(request.method().toUpperCase(Locale.ROOT));
            before.setUrlPath(maskedPath(request.pathAndQuery()));
            before.setRequestMasked(requestMasked);
            before.setRequestRawId(requestRawId);
            update(before);
        }
        if (inv != null) {
            inv.markSent();
        }
        ChannelHttpResponse response;
        try {
            response = transport.exchange(request, readTimeout);
        } catch (ConnectException | HttpConnectTimeoutException e) {
            if (inv != null) {
                inv.connectFailed();
            }
            legacySent(payChannel, api, inv, false, "连接失败：" + e.getClass().getSimpleName(), request.body());
            throw new ChannelTransportException(ChannelTransportException.Kind.CONNECT_FAILED, 0, null,
                    payChannel + " " + api + " 连接失败：" + e.getClass().getSimpleName(), e);
        } catch (HttpTimeoutException e) {
            legacySent(payChannel, api, inv, false, "超时（可能已受理）", request.body());
            throw new ChannelTransportException(ChannelTransportException.Kind.TIMEOUT, 0, null,
                    payChannel + " " + api + " 读超时", e);
        } catch (IOException e) {
            legacySent(payChannel, api, inv, false, "断连（可能已受理）：" + e.getClass().getSimpleName(), request.body());
            throw new ChannelTransportException(ChannelTransportException.Kind.TIMEOUT, 0, null,
                    payChannel + " " + api + " 发出后断连：" + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelTransportException(ChannelTransportException.Kind.TIMEOUT, 0, null,
                    payChannel + " " + api + " 等应答时被中断", e);
        }
        String responseMasked = PayloadMasker.maskText(maskedEnvelope(response.headers(), response.body()));
        Long responseRawId = raw("CALL_RESPONSE", plainOf(response), responseMasked);
        PayChannelCallLog after = patch(inv);
        if (after != null) {
            after.setHttpStatus(response.status());
            after.setResponseMasked(responseMasked);
            after.setResponseRawId(responseRawId);
            update(after);
        }
        legacySent(payChannel, api, inv, response.ok(), response.ok() ? null : "HTTP " + response.status(),
                request.body());
        return response;
    }

    /** 记录写失败的次数（告警用） */
    public long recordingFailures() {
        return failures.get();
    }

    /** 落库与哈希用的「完整原文」：请求行、头、空行、体 */
    static String plainOf(ChannelHttpRequest r) {
        StringBuilder sb = new StringBuilder().append(r.method()).append(' ').append(r.pathAndQuery()).append('\n');
        r.headers().forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        return sb.append('\n').append(r.body()).toString();
    }

    static String plainOf(ChannelHttpResponse r) {
        StringBuilder sb = new StringBuilder().append(r.status()).append('\n');
        r.headers().forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        return sb.append('\n').append(r.body()).toString();
    }

    /** 头与体拼成一个 JSON 再整体递归脱敏：体是 JSON 的保持结构，不是的作为字符串 */
    private static String maskedEnvelope(Map<String, String> headers, String body) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("headers", headers);
        envelope.put("body", body);
        return JSON.writeValueAsString(envelope);
    }

    /** 路径照留；查询串里敏感参数的值遮掉（路径与 mchid / sub_mchid 是排障要看的） */
    static String maskedPath(String pathAndQuery) {
        int q = pathAndQuery.indexOf('?');
        String out = pathAndQuery;
        if (q >= 0) {
            StringBuilder sb = new StringBuilder(pathAndQuery.substring(0, q + 1));
            List<String> pairs = List.of(pathAndQuery.substring(q + 1).split("&"));
            for (int i = 0; i < pairs.size(); i++) {
                String pair = pairs.get(i);
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                sb.append(i == 0 ? "" : "&").append(PayloadMasker.sensitive(key) && eq >= 0 ? key + "=***" : pair);
            }
            out = sb.toString();
        }
        return out.length() <= PATH_MAX ? out : out.substring(0, PATH_MAX);
    }

    private Long raw(String kind, String plain, String masked) {
        try {
            RawPayloadSealer.Sealed sealed = sealer.seal(plain, masked);
            PayRawPayload row = new PayRawPayload();
            row.setKind(kind);
            row.setStorage("DB");
            row.setCipherText(sealed.cipherText());
            row.setDataKeyCipher(sealed.dataKeyCipher());
            row.setSecMasterKeyId(sealed.masterKeyId());
            byte[] bytes = plain.getBytes(StandardCharsets.UTF_8);
            row.setSha256(sha256(bytes));
            row.setSizeBytes(bytes.length);
            row.setRetainUntil(LocalDate.now(ZoneOffset.UTC).plusDays(retentionDays));
            return raws.insert(row);
        } catch (RuntimeException e) {
            fail("raw " + kind, e);
            return null;
        }
    }

    private static PayChannelCallLog patch(Invocation inv) {
        if (inv == null || inv.callLogId() == null) {
            return null;
        }
        PayChannelCallLog row = new PayChannelCallLog();
        row.setId(inv.callLogId());
        return row;
    }

    private void update(PayChannelCallLog row) {
        try {
            callLogs.update(row);
        } catch (RuntimeException e) {
            fail("call-log", e);
        }
    }

    private void legacySent(String payChannel, String api, Invocation inv, boolean ok, String reason, String body) {
        if (legacy == null || !legacy.writing()) {
            return;
        }
        try {
            Map<String, Object> masked = new LinkedHashMap<>();
            masked.put("body", body);
            legacy.sent(payChannel, api, inv == null ? null : inv.command().outNo(), ok, reason, masked);
        } catch (RuntimeException e) {
            fail("stl_channel_message", e);
        }
    }

    private void fail(String what, RuntimeException e) {
        failures.incrementAndGet();
        log.warn("[pay-channel] 报文记录写入失败 what={} error={}", what, e.getClass().getSimpleName());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 没有 SHA-256", e);
        }
    }
}
