package ai.neargo.shop.elec.svc;

import ai.neargo.shop.elec.config.ElecProperties;
import ai.neargo.shop.elec.gateway.ElecColumnAi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型认列：cdw 上的 qwen（sglang，OpenAI 兼容的 {@code /chat/completions}）。
 *
 * <p><b>三件实测出来的事</b>（与主系统 {@code GoodsVisionGateway} 同一台、同一套坑）：
 * <ol>
 *   <li><b>钉死 HTTP/1.1</b>：明文 http 下 Java 默认会带 h2c 升级头去问，sglang 不支持 h2c，
 *       处理带升级头的请求时把请求体丢了，回 400「body 缺失」—— 看起来像提示词写错，其实错在协议层</li>
 *   <li><b>关 thinking</b>（{@code chat_template_kwargs.enable_thinking=false}）：不关的话推理过程进
 *       {@code reasoning_content}，{@code content} 是空串，表现是「认不出来」且不报错</li>
 *   <li><b>不用 response_format</b>：这台部署上无效。靠提示词要 JSON，并容忍它套一层 ``` 代码块</li>
 * </ol>
 *
 * <p>2026-09-30 从生产机实测：表头 {@code 序/Item/Maker/Stk/年份/Pkg/含税价/备注} 三次都回
 * {@code {"MPN":1,"MFR":2,"QTY":3,"DC":4,"PACKAGE":5,"PRICE":6}}，0.73–1.04 秒。
 *
 * <p>失败一律返回 null（端口约定），由 {@code ColumnResolver} 记熔断、走手工。
 */
@Component
public class QwenColumnAi implements ElecColumnAi {

    private static final Logger log = LoggerFactory.getLogger(QwenColumnAi.class);

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ElecProperties.Ai cfg;
    private final ObjectMapper json;

    public QwenColumnAi(ElecProperties props, ObjectMapper json) {
        this.cfg = props.getAi();
        this.json = json;
    }

    @Override
    public boolean isEnabled() {
        return cfg.isEnabled() && cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank();
    }

    @Override
    public Guess guess(List<List<String>> head, List<FieldSpec> fields) {
        if (!isEnabled() || head.isEmpty()) {
            return null;
        }
        long t0 = System.nanoTime();
        try {
            Map<String, Object> body = Map.of(
                    "model", cfg.getModel(),
                    "max_tokens", 200,
                    "temperature", 0,
                    "chat_template_kwargs", Map.of("enable_thinking", false),
                    "messages", List.of(
                            Map.of("role", "system", "content", system(fields)),
                            Map.of("role", "user", "content", user(head))));
            HttpRequest req = HttpRequest.newBuilder(URI.create(stripSlash(cfg.getBaseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("大模型认列失败：HTTP {} {}", resp.statusCode(), abbreviate(resp.body()));
                return null;
            }
            String content = json.readTree(resp.body()).path("choices").path(0).path("message").path("content")
                    .asString("");
            Guess g = parse(content);
            log.info("大模型认列 {} ms：{}", (System.nanoTime() - t0) / 1_000_000, g == null ? abbreviate(content) : g);
            return g;
        } catch (Exception e) {
            log.warn("大模型认列异常（{} ms）：{}", (System.nanoTime() - t0) / 1_000_000, e.toString());
            return null;
        }
    }

    static String system(List<FieldSpec> fields) {
        StringBuilder sb = new StringBuilder("""
                你是电子元器件库存表的列识别器。给你一张表的前几行（每行一个 JSON 数组，行号与列号都从 0 起），\
                第一行不一定是表头（上面可能有公司抬头或标题）。
                请判断：表头在哪一行；下列字段分别在哪一列。表里没有的字段不要写，拿不准的也不要写。
                只输出一个 JSON 对象，形如 {"headerRow":0,"columns":{"MPN":1,"QTY":3}}，不要解释，不要代码块。
                字段：
                """);
        for (FieldSpec f : fields) {
            sb.append("- ").append(f.code()).append("：").append(f.desc()).append('\n');
        }
        return sb.toString();
    }

    private String user(List<List<String>> head) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < head.size(); i++) {
            sb.append(i).append(": ").append(json.writeValueAsString(head.get(i))).append('\n');
        }
        return sb.toString();
    }

    /** 容忍 ``` 包裹与前后多余的字：取第一个 { 到最后一个 } */
    Guess parse(String content) {
        if (content == null) {
            return null;
        }
        int a = content.indexOf('{');
        int b = content.lastIndexOf('}');
        if (a < 0 || b <= a) {
            return null;
        }
        try {
            JsonNode n = json.readTree(content.substring(a, b + 1));
            JsonNode cols = n.path("columns");
            if (!cols.isObject()) {
                return null;
            }
            Map<String, Integer> m = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : cols.properties()) {
                if (e.getValue().isIntegralNumber()) {
                    m.put(e.getKey(), e.getValue().asInt());
                }
            }
            return new Guess(n.path("headerRow").asInt(-1), m);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
