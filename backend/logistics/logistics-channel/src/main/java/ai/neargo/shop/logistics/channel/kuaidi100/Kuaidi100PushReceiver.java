package ai.neargo.shop.logistics.channel.kuaidi100;

import ai.neargo.shop.logistics.capability.PushReceiver;
import ai.neargo.shop.spi.logistics.TraceResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 快递100 轨迹推送（{@code POST /callback/logistics/kuaidi100}，表单 {@code param} + {@code sign}）。
 *
 * <p>{@code param.status}：polling 跟踪中 · shutdown 结束（通常已签收）· abort 中止（3 天无记录 / 60 天无变化）·
 * updateall 全量重推。{@code autoCheck=1} 时带 {@code comOld}/{@code comNew}：快递100 纠正了承运商（商家选错是常态）。
 * {@code lastResult} 与查询接口的返回体同一结构，复用 {@link Kuaidi100TraceProvider#parse}。
 */
@Component
public class Kuaidi100PushReceiver implements PushReceiver {

    private static final Logger log = LoggerFactory.getLogger(Kuaidi100PushReceiver.class);
    static final String ACK = "{\"result\":true,\"returnCode\":\"200\",\"message\":\"成功\"}";

    private final ObjectMapper json = new ObjectMapper();
    private final String salt;

    public Kuaidi100PushReceiver(@Value("${shop.express.kuaidi100.salt:}") String salt) {
        this.salt = salt == null ? "" : salt.trim();
    }

    @Override
    public String channel() {
        return "kuaidi100";
    }

    @Override
    public boolean available() {
        return !salt.isEmpty();
    }

    @Override
    public String ack() {
        return ACK;
    }

    @Override
    public Parsed parse(Request request) {
        String param = request.form().get("param");
        String sign = request.form().get("sign");
        if (param == null || sign == null || !Kuaidi100Codes.sign(param, salt).equalsIgnoreCase(sign.trim())) {
            log.warn("[lgs:kuaidi100] 推送验签失败（param {}，sign {}）", param == null ? "缺" : "有", sign == null ? "缺" : "有");
            return Parsed.rejected();
        }
        try {
            JsonNode root = json.readTree(param);
            JsonNode last = root.path("lastResult");
            String nu = last.path("nu").asText("").trim();
            boolean corrected = "1".equals(root.path("autoCheck").asText(""));
            String com = corrected ? root.path("comNew").asText(last.path("com").asText("")) : last.path("com").asText("");
            boolean ended = "abort".equalsIgnoreCase(root.path("status").asText(""));
            TraceResult trace = null;
            if (last.isObject() && last.path("data").isArray() && !last.path("data").isEmpty()) {
                Optional<TraceResult> t = Kuaidi100TraceProvider.parse(json, com, nu, last.toString());
                trace = t.orElse(null);
            }
            return new Parsed(true, com, nu, trace, ended, corrected ? root.path("comOld").asText(null) : null);
        } catch (Exception e) {
            log.warn("[lgs:kuaidi100] 推送报文解析失败：{}", e.toString());
            return new Parsed(true, null, null, null, false, null);
        }
    }
}
