package ai.neargo.shop.channel.ai.port;

import ai.neargo.shop.spi.product.CertVisionPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 证照识别，走 OpenAI 兼容的 {@code /v1/chat/completions}。与 {@link GoodsVisionGateway}
 * 共用同一个模型配置（{@code shop.ai.vision.*}）—— 是同一个部署，没必要配两份。
 *
 * <p><b>三条实测出来的事，改这个类之前先读：</b>
 *
 * <ol>
 *   <li><b>必须关掉 thinking</b>（与商品识别同一条）。默认模式下模型把推理写进
 *       {@code reasoning_content}，而 {@code content} 是<b>空串</b> ——
 *       表现是「识别不出来」且不报任何错。关法是
 *       {@code chat_template_kwargs.enable_thinking=false}。
 *       2026-08-25 用真执照与真身份证各验过一次：关掉之后 {@code reasoning_content}
 *       长度为 0、{@code content} 直接就是 JSON。
 *   <li><b>图要先压。</b>实拍执照常见 5712×4284 / 7.3MB，base64 之后接近 10MB，
 *       大多数网关直接拒收 —— 而那个失败看起来像「模型不可用」。压到 1600px 长边
 *       约 400KB，识别准确率实测不受影响（信用代码、日期、地址逐字对过）。
 *   <li><b>身份证的两面字段不同</b>，所以先让模型判面再填字段：人像面没有签发机关与有效期，
 *       国徽面没有姓名与身份证号。不判面的话，模型会把另一面的字段编出来 ——
 *       而编出来的有效期会被商家当成「系统读到的」直接提交。
 * </ol>
 *
 * <p><b>图片只内联，不落盘、不落公开桶</b> —— 见 {@link CertVisionPort} 的说明。
 * 也<b>不进日志</b>：这个类任何一条日志都不打图片内容与识别出的号码。
 */
@Slf4j
@Component
public class CertVisionGateway implements CertVisionPort {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 提示词。**要求「认不出填 null，不要猜」是这段里最重要的一句** ——
     * 少了它，模型会把模糊的位补齐成一个看着合法的信用代码。
     */
    private static final String PROMPT = """
            你是证照识别助手。只输出 JSON，不要任何解释、不要代码块。
            先判断这是哪种证件，再按对应字段填。**认不出的字段一律填 null，不要猜、不要补全。**

            营业执照 → docType="BUSINESS_LICENSE"，填 name(名称)、code(统一社会信用代码,18位)、
              legalForm(类型,如 个体工商户/有限责任公司)、person(法定代表人或经营者)、
              address(住所或经营场所)、issuedAt(成立日期 YYYY-MM-DD)。side 填 null。
            身份证 → docType="ID_CARD"，side 填 FRONT(人像面) 或 BACK(国徽面)。
              人像面填 name(姓名)、code(公民身份号码,18位)、address(住址)；
              国徽面填 validTo(有效期止 YYYY-MM-DD 或 长期)，其余为 null。
            都不是 → docType="UNKNOWN"，其余全 null。

            输出格式：
            {"docType":"","side":null,"name":null,"code":null,"legalForm":null,
             "person":null,"address":null,"issuedAt":null,"validTo":null,"confidence":0.0}
            """;

    /**
     * <b>必须锁 HTTP/1.1。</b>
     *
     * <p>Java 的 HttpClient 默认 HTTP/2，对这个部署（sglang）**发大 body 时它收不到** ——
     * 返回 400 且消息是 {@code 'loc': ('body',), 'msg': 'Field required', 'input': None}，
     * 也就是「服务端认为你没发 body」。而同样的载荷用 curl（默认 1.1）是通的。
     *
     * <p>这个坑只在 body 大的时候露出来：{@link GoodsVisionGateway} 传的是图片 URL，
     * body 只有几百字节，一直没撞上。证照走 base64 内联，一张 400KB 的图 base64 后
     * 约 530KB —— 第一次真图联调就是 400，而且**异常被吞成「没认出来」**，
     * 从表现上完全看不出是传输层的事。
     */
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final int timeoutSeconds;
    private final boolean enabled;

    public CertVisionGateway(
            @Value("${shop.ai.vision.base-url:}") String baseUrl,
            @Value("${shop.ai.vision.model:qwen3.6}") String model,
            @Value("${shop.ai.vision.api-key:}") String apiKey,
            @Value("${shop.ai.vision.timeout-seconds:25}") int timeoutSeconds,
            @Value("${shop.ai.vision.enabled:false}") boolean enabled) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.timeoutSeconds = timeoutSeconds;
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return enabled && !baseUrl.isBlank();
    }

    @Override
    public Cert recognize(byte[] imageBytes, String contentType) {
        if (!isEnabled() || imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        try {
            String mime = contentType == null || contentType.isBlank() ? "image/jpeg" : contentType;
            String dataUri = "data:" + mime + ";base64,"
                    + Base64.getEncoder().encodeToString(imageBytes);

            var body = Map.of(
                    "model", model,
                    "max_tokens", 900,
                    // 证照是照抄不是创作：温度给 0，同一张图两次结果要一样
                    "temperature", 0,
                    // ★ 见类注释第 1 条
                    "chat_template_kwargs", Map.of("enable_thinking", false),
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of("type", "text", "text", PROMPT),
                                    Map.of("type", "image_url",
                                            "image_url", Map.of("url", dataUri))))));

            var req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json");
            if (!apiKey.isBlank()) {
                req.header("Authorization", "Bearer " + apiKey);
            }
            var resp = http.send(
                    req.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                // **只记状态码不记响应体** —— 有些网关会把请求原样回显，那里面有整张证照
                log.warn("证照识别失败：HTTP {}", resp.statusCode());
                return null;
            }
            String content = JSON.readTree(resp.body())
                    .path("choices").path(0).path("message").path("content").asText("");
            return parse(content);
        } catch (Exception e) {
            // 识别不该让上传跟着失败。**异常只记类型不记消息** —— 消息里可能带 data URI
            log.warn("证照识别异常：{}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 解析模型输出。**容忍它套一层代码块** ——
     * {@code response_format=json_object} 在这个部署上无效（与商品识别是同一条教训），
     * 所以靠提示词要 JSON，而模型偶尔仍会包一层 ```json。
     */
    private Cert parse(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String s = content.trim();
        int l = s.indexOf('{');
        int r = s.lastIndexOf('}');
        if (l < 0 || r <= l) {
            return null;
        }
        try {
            var n = JSON.readTree(s.substring(l, r + 1));
            String docType = text(n, "docType");
            if (docType == null) {
                return null;
            }
            return new Cert(docType, text(n, "side"), text(n, "name"), text(n, "code"),
                    text(n, "legalForm"), text(n, "person"), text(n, "address"),
                    text(n, "issuedAt"), text(n, "validTo"),
                    n.path("confidence").asDouble(0));
        } catch (Exception e) {
            log.warn("证照识别结果解析失败：{}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** 空串与字面量 "null" 都归一成 null —— 模型两种都会吐，而空串会被端上渲染成一个空输入框 */
    private static String text(com.fasterxml.jackson.databind.JsonNode n, String field) {
        String v = n.path(field).asText("");
        return v.isBlank() || "null".equalsIgnoreCase(v) ? null : v;
    }
}
