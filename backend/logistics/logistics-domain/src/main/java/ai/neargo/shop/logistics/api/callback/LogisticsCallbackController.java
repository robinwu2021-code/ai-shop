package ai.neargo.shop.logistics.api.callback;

import ai.neargo.shop.logistics.capability.PushReceiver;
import ai.neargo.shop.logistics.push.PushIngestion;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * 物流渠道推送的<b>唯一入口</b>：{@code POST /callback/logistics/{channel}}（TDD-物流模块 M4，物流-API X1）。
 *
 * <p>只做分派，不含任何渠道逻辑。三条规矩：
 * <ul>
 *   <li><b>返回类型必须就是 {@code String}</b>：统一信封只放过返回类型是 String 的方法 ——
 *       {@code ResponseEntity<String>} 也会被它包成 ApiResult，写响应时 ClassCastException、渠道收到 10500
 *       （2026-10-09 场景测试当场抓到）；</li>
 *   <li><b>只要渠道名存在就一律回成功</b>：验签失败、找不到运单、入库异常都回成功、各自记 WARN / ERROR ——
 *       回失败渠道会重推，而重推的报文不会让结果变对；快递100 只重试 3 次，之后靠补偿作业；</li>
 *   <li>渠道名不存在 → 回 {@code {"result":false}} 并 WARN。写不出 404：全局异常处理把任何异常都转成
 *       HTTP 200 + 10500，而域里又不许碰 Servlet 响应对象。</li>
 * </ul>
 * 放在物流模块里而不是主服务的 portal：拆出去那天 nginx 按前缀 {@code /callback/logistics/} 转发一行即可。
 */
@RestController
@Profile("api")
public class LogisticsCallbackController {

    private static final Logger log = LoggerFactory.getLogger(LogisticsCallbackController.class);

    private final ChannelRouter router;
    private final PushIngestion ingestion;

    public LogisticsCallbackController(ChannelRouter router, PushIngestion ingestion) {
        this.router = router;
        this.ingestion = ingestion;
    }

    static final String UNKNOWN_CHANNEL = "{\"result\":false,\"returnCode\":\"404\",\"message\":\"unknown channel\"}";

    @PostMapping(value = "/callback/logistics/{channel}", produces = "application/json")
    public String push(@PathVariable String channel,
                       @RequestParam Map<String, String> form,
                       @RequestBody(required = false) String body) {
        Optional<PushReceiver> receiver = router.receiver(channel);
        if (receiver.isEmpty()) {
            log.warn("[lgs-push] 没有可用的渠道 {}（没装、没启用或凭据没配）", channel);
            return UNKNOWN_CHANNEL;
        }
        PushReceiver r = receiver.get();
        try {
            ingestion.logFirst(channel, body != null && !body.isBlank() ? body : String.valueOf(form));
            ingestion.ingest(channel, r.parse(new PushReceiver.Request(form, body)));
        } catch (RuntimeException e) {
            log.error("[lgs-push] {} 推送入库失败（照样回成功，补偿作业兜底）：{}", channel, e.toString(), e);
        }
        return r.ack();
    }
}
