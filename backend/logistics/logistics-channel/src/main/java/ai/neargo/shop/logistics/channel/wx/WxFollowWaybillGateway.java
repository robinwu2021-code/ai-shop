package ai.neargo.shop.logistics.channel.wx;

import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.WaybillFollower;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Official logistics-message follow/query APIs. Never writes to the query-plugin display token. */
@Component
public class WxFollowWaybillGateway implements WaybillFollower {
    private static final Logger log = LoggerFactory.getLogger(WxFollowWaybillGateway.class);
    private final WxLogisticsClient client;
    private final ObjectMapper json = new ObjectMapper();

    public WxFollowWaybillGateway(WxLogisticsClient client) {
        this.client = client;
    }

    @Override
    public boolean available() {
        return client.configured();
    }

    @Override
    public ChannelOutcome follow(FollowCmd cmd) {
        if (blank(cmd.openid()) || blank(cmd.transId()) || blank(cmd.receiverPhone())
                || blank(cmd.waybillNo()) || blank(cmd.orderPath())) {
            return ChannelOutcome.fatal("MISSING_PURCHASE", "缺少真实购买者或交易/收件信息");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("openid", cmd.openid());
        body.put("trans_id", cmd.transId());
        body.put("receiver_phone", cmd.receiverPhone());
        body.put("waybill_id", cmd.waybillNo());
        body.put("order_detail_path", cmd.orderPath());
        body.put("goods_info", Map.of("detail_list", goods(cmd.goodsJson())));
        if (!blank(cmd.deliveryId())) body.put("delivery_id", cmd.deliveryId());
        try {
            JsonNode response = client.post("follow_waybill", body, Duration.ofSeconds(4));
            int code = response.path("errcode").asInt(-1);
            String token = response.path("waybill_token").asText("");
            if (code == 0 && !token.isBlank()) return ChannelOutcome.ok("0", token);
            if (code == 0) return ChannelOutcome.retryable("EMPTY_TOKEN", "微信未返回运单 token");
            return classify(code, response.path("errmsg").asText(""));
        } catch (Exception error) {
            log.warn("[lgs:wx-follow] {} 关注失败：{}", cmd.waybillNo(), error.toString());
            return ChannelOutcome.retryable("IO", error.getClass().getSimpleName());
        }
    }

    @Override
    public Optional<TraceResult> queryFollow(String token, String carrier, String waybillNo) {
        if (blank(token)) return Optional.empty();
        try {
            JsonNode response = client.post("query_follow_trace", Map.of("waybill_token", token), Duration.ofSeconds(2));
            if (response.path("errcode").asInt(-1) != 0) return Optional.empty();
            TraceStatus status = WxStatusProbe.statusOf(response.path("waybill_info").path("status").asInt(-1));
            return status == TraceStatus.UNKNOWN ? Optional.empty()
                    : Optional.of(new TraceResult(waybillNo, carrier, status, "wx-follow", List.of()));
        } catch (Exception error) {
            log.info("[lgs:wx-follow] {} 查询失败：{}", waybillNo, error.toString());
            return Optional.empty();
        }
    }

    static ChannelOutcome classify(int code, String message) {
        return switch (code) {
            case 9300559 -> ChannelOutcome.notReady("9300559", message);
            case 9300513, -1, 40001, 42001 -> ChannelOutcome.retryable(String.valueOf(code), message);
            default -> ChannelOutcome.fatal(String.valueOf(code), message);
        };
    }

    private List<Map<String, String>> goods(String goodsJson) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            if (!blank(goodsJson)) {
                for (JsonNode good : json.readTree(goodsJson)) {
                    out.add(Map.of("goods_name", good.path("name").asText("商品"),
                            "goods_img_url", good.path("imageUrl").asText("")));
                    if (out.size() == 3) break;
                }
            }
        } catch (Exception error) {
            log.warn("[lgs:wx-follow] 商品快照无法解析：{}", error.toString());
        }
        if (out.isEmpty()) out.add(Map.of("goods_name", "商品", "goods_img_url", ""));
        return out;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
