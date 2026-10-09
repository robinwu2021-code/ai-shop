package ai.neargo.shop.logistics.channel.wx;

import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.WaybillTokenBinder;
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

/**
 * 微信 {@code trace_waybill}：运单 → waybill_token（小程序物流插件凭它打开全屏轨迹页）。
 *
 * <p>码表（微信文档）：9300559 运单不存在（快递公司还没同步给微信）→ NOT_READY，等下一条推送；
 * 9300561 收件人手机号错 / 9300534 openid 与小程序不匹配 → FATAL；9300513 超限、取 token 失败、网络 → 可重试；
 * 认不出的码按 FATAL（盲目重试会烧次数）。
 */
@Component
public class WxWaybillBinder implements WaybillTokenBinder {

    private static final Logger log = LoggerFactory.getLogger(WxWaybillBinder.class);

    private final WxLogisticsClient client;
    private final ObjectMapper json = new ObjectMapper();

    public WxWaybillBinder(WxLogisticsClient client) {
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
    public ChannelOutcome bind(BindCmd cmd) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("openid", cmd.openid());
            body.put("waybill_id", cmd.waybillNo());
            body.put("trans_id", cmd.transId());
            if (notBlank(cmd.deliveryId())) {
                body.put("delivery_id", cmd.deliveryId());
            }
            if (notBlank(cmd.receiverPhone())) {
                body.put("receiver_phone", cmd.receiverPhone());
            }
            body.put("goods_info", Map.of("detail_list", goods(cmd.goodsJson())));
            if (notBlank(cmd.orderPath())) {
                body.put("order_detail_path", cmd.orderPath());
            }
            JsonNode n = client.post("trace_waybill", body, Duration.ofSeconds(10));
            int code = n.path("errcode").asInt(0);
            String tk = n.path("waybill_token").asText("");
            if (code == 0 && !tk.isBlank()) {
                return ChannelOutcome.ok("0", tk);
            }
            return classify(code, n.path("errmsg").asText(""));
        } catch (Exception e) {
            log.warn("[lgs:wx] trace_waybill {} 异常：{}", cmd.waybillNo(), e.toString());
            return ChannelOutcome.retryable("IO", e.getClass().getSimpleName());
        }
    }

    static ChannelOutcome classify(int code, String msg) {
        return switch (code) {
            case 9300559 -> ChannelOutcome.notReady("9300559", msg);
            case 9300513, -1, 40001, 42001 -> ChannelOutcome.retryable(String.valueOf(code), msg);
            default -> ChannelOutcome.fatal(String.valueOf(code), msg);
        };
    }

    private List<Map<String, String>> goods(String goodsJson) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            if (notBlank(goodsJson)) {
                for (JsonNode g : json.readTree(goodsJson)) {
                    out.add(Map.of("goods_name", g.path("name").asText("商品"),
                            "goods_img_url", g.path("imageUrl").asText("")));
                }
            }
        } catch (Exception e) {
            log.warn("[lgs:wx] 商品快照解析失败：{}", e.toString());
        }
        if (out.isEmpty()) {
            out.add(Map.of("goods_name", "商品", "goods_img_url", ""));   // 微信要求至少一件
        }
        return out;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
