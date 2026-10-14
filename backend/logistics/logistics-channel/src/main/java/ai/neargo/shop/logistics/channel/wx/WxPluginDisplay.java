package ai.neargo.shop.logistics.channel.wx;

import ai.neargo.shop.spi.logistics.TraceDisplay;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 微信物流查询组件（TDD-物流轨迹多渠道 §2.3）。**只在 C 端小程序可用** —— 插件是微信的，
 * 只有小程序能 {@code requirePlugin}。
 *
 * <p>我们只做一件事：拿 {@code waybill_token}。点开之后是微信自己的全屏物流页，轨迹、地图、样式
 * 全由微信渲染，我们一行都改不了，也不必维护。
 *
 * <p><b>换 token 必须落库</b>：微信文档要求开发者自己存，而 {@code trace_waybill} 有调用次数上限
 * （{@code 9300513}）。不落库的话每次打开订单详情都换一次，很快撞上限。
 *
 * <p><b>前置条件挺硬</b>：{@code trans_id}（微信支付交易单号）必填 —— 线下收款、历史单没有它，
 * 这类单 {@link #supports} 为 false，链会落到自建渠道。这是微信的要求，不是我们的取舍。
 */
@Component
public class WxPluginDisplay implements TraceDisplay {

    private static final Logger log = LoggerFactory.getLogger(WxPluginDisplay.class);

    private final WxLogisticsClient client;

    /** token 与 HTTP 交给 {@link WxLogisticsClient}：与换 token、查状态共用同一份 stable_token 缓存 */
    public WxPluginDisplay(WxLogisticsClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "wx-plugin";
    }

    @Override
    public boolean supports(Surface surface, ShipmentCtx ctx) {
        return surface == Surface.MP
                && client.configured()
                && ctx != null
                && notBlank(ctx.buyerOpenid())
                && notBlank(ctx.transId())      // 微信 trace_waybill 必填，没有就根本换不到 token
                && notBlank(ctx.waybillNo());
    }

    @Override
    public Optional<DisplayPayload> prepare(ShipmentCtx ctx) {
        // 已经换过且就是本渠道的：直接用，别再调微信（它有次数上限）
        if (name().equals(ctx.savedChannel()) && notBlank(ctx.savedToken())) {
            return Optional.of(new DisplayPayload(name(), ctx.savedToken(), List.of(), null, false));
        }
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("openid", ctx.buyerOpenid());
            body.put("waybill_id", ctx.waybillNo());
            body.put("trans_id", ctx.transId());
            if (notBlank(ctx.carrier())) {
                // 选填，但微信文档说传了能提高运单识别准确度，非主流快递尤其建议传
                body.put("delivery_id", ctx.carrier());
            }
            if (notBlank(ctx.receiverPhone())) {
                // 申通/中通等运力必填，用它查单；缺了回 9300561「收件人手机号错误」。传完整号，微信比对后四位
                body.put("receiver_phone", ctx.receiverPhone());
            }
            body.put("goods_info", Map.of("detail_list", List.of(Map.of(
                    "goods_name", ctx.goodsName() == null ? "商品" : ctx.goodsName(),
                    "goods_img_url", ctx.goodsImgUrl() == null ? "" : ctx.goodsImgUrl()))));
            if (notBlank(ctx.orderPath())) {
                body.put("order_detail_path", ctx.orderPath());
            }
            JsonNode n = client.post("trace_waybill", body, Duration.ofSeconds(10));
            int code = n.path("errcode").asInt(0);
            String tk = n.path("waybill_token").asText("");
            if (code != 0 || tk.isBlank()) {
                /*
                 * 失败一律落到链上下一个渠道，不抛。常见：
                 *   9300559 运单不存在（刚发货，微信那边还没有）
                 *   9300513 调用次数到上限
                 *   40003  openid 不合法
                 *   9300534 access_token 与 openid 不匹配（小程序配错）
                 * 原因回给调用方记进 display_fail_reason，给运营排查用。
                 */
                log.info("[display:wx-plugin] 运单 {} 换 token 失败：{} {}",
                        ctx.waybillNo(), code, n.path("errmsg").asText(""));
                return Optional.empty();
            }
            return Optional.of(new DisplayPayload(name(), tk, List.of(), null, true));
        } catch (Exception e) {
            log.warn("[display:wx-plugin] 运单 {} 换 token 异常：{}", ctx.waybillNo(), e.toString());
            return Optional.empty();
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 与别的微信通道各管一份 stable_token：拿到的是同一个 token，而可用性不互相绑死 */
}
