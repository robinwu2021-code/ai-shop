package ai.neargo.shop.pay.points.event.impl;

import ai.neargo.shop.pay.points.event.PointsAttentionService;
import ai.neargo.shop.pay.points.event.PointsEventQueue;
import ai.neargo.shop.pay.points.event.PointsEventQueue.Kind;
import ai.neargo.shop.pay.points.event.PointsEventQueue.Message;
import ai.neargo.shop.pay.points.policy.PointsDigests;
import ai.neargo.shop.spi.notify.OpsAlertPort.PointsAttention;

import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

@Service
public class PointsAttentionServiceImpl implements PointsAttentionService {
    private final PointsEventQueue queue;
    private final ObjectMapper json = new ObjectMapper();

    public PointsAttentionServiceImpl(PointsEventQueue queue) {
        this.queue = queue;
    }

    @Override
    public String raise(
            String type, String code, String ref, long expected, long actual, long now) {
        if (code == null || code.isBlank() || ref == null || now < 0) {
            throw new IllegalArgumentException("POINTS_ATTENTION_INVALID");
        }
        var day = Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        String event =
                "ATTENTION:"
                        + PointsDigests.sha256(
                                json.writeValueAsString(
                                        List.of(type, code, ref, expected, actual, day)));
        var alert = new PointsAttention(type, code, ref, expected, actual, event);
        queue.enqueue(type, event, new Message(Kind.OPS_ATTENTION, json.writeValueAsString(alert)));
        return event;
    }
}
