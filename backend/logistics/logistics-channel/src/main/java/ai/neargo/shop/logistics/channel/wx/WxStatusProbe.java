package ai.neargo.shop.logistics.channel.wx;

import ai.neargo.shop.logistics.capability.StatusProbe;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 微信 {@code query_trace(waybill_token)}：<b>只给状态、不给节点</b>。
 * waybill_info.status：0 未揽收 · 1 揽件 · 2 运输 · 3 派件 · 4 签收 · 5 异常 · 6 代签。
 *
 * <p>超时 2 秒：它在物流页的读路径上，宁可这次不校正，也不能让买家等。
 */
@Component
public class WxStatusProbe implements StatusProbe {

    private static final Logger log = LoggerFactory.getLogger(WxStatusProbe.class);

    private final WxLogisticsClient client;

    public WxStatusProbe(WxLogisticsClient client) {
        this.client = client;
    }

    @Override
    public String channel() {
        return "wx";
    }

    @Override
    public boolean available() {
        return client.configured();
    }

    @Override
    public Optional<TraceResult> probe(ProbeCmd cmd) {
        if (cmd.waybillToken() == null || cmd.waybillToken().isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode n = client.post("query_trace", Map.of("waybill_token", cmd.waybillToken()), Duration.ofSeconds(2));
            if (n.path("errcode").asInt(0) != 0) {
                log.info("[lgs:wx] query_trace {} 失败：{} {}", cmd.waybillNo(),
                        n.path("errcode").asInt(), n.path("errmsg").asText(""));
                return Optional.empty();
            }
            TraceStatus s = statusOf(n.path("waybill_info").path("status").asInt(-1));
            return s == TraceStatus.UNKNOWN ? Optional.empty()
                    : Optional.of(new TraceResult(cmd.waybillNo(), cmd.carrier(), s, "wx", List.of()));
        } catch (Exception e) {
            log.info("[lgs:wx] query_trace {} 异常：{}", cmd.waybillNo(), e.toString());
            return Optional.empty();
        }
    }

    static TraceStatus statusOf(int s) {
        return switch (s) {
            case 1 -> TraceStatus.PICKED;
            case 2 -> TraceStatus.IN_TRANSIT;
            case 3 -> TraceStatus.DELIVERING;
            case 4, 6 -> TraceStatus.SIGNED;
            case 5 -> TraceStatus.EXCEPTION;
            default -> TraceStatus.UNKNOWN;   // 0 未揽收 / 认不出
        };
    }
}
