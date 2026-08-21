package ai.neargo.shop.channel.ai.port;

import ai.neargo.shop.spi.product.GoodsVisionPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 拍照建品的**视觉识别**（B-11.3.7）。走 OpenAI 兼容的 `/v1/chat/completions`。
 *
 * <p>住在 shop-channel 而不是商品域：它是一个外部适配（和支付通道、短信一样），
 * 商品域只该知道「给我一张图，还我一个建议」，不该知道对面是什么模型。
 *
 * <p><b>三件实测出来的事，改这个类之前先读：</b>
 *
 * <ol>
 *   <li><b>必须关掉 thinking</b>。默认模式下模型把整个推理过程写进
 *       {@code reasoning_content}，而 {@code content} 是**空串** ——
 *       400 token 用完都还没吐出 JSON。只读 {@code content} 的话，
 *       表现是「识别不出来」，而且不报任何错。
 *       关法是 {@code chat_template_kwargs.enable_thinking=false}。
 *   <li><b>{@code response_format=json_object} 在这个部署上无效</b> ——
 *       传了它模型照样进 thinking，content 依旧是空。所以靠提示词要 JSON，
 *       并容忍它套一层 ``` 代码块。
 *   <li><b>类目要把候选列表喂进去</b>。不给列表让它自由发挥，返回的会是
 *       「日用品」这种不存在的编号 —— 而一个查无此项的 categoryNo 落进草稿，
 *       商家保存时才会撞上类目校验，那时他已经不记得是谁填的了。
 * </ol>
 *
 * <p><b>失败一律返回 null</b>，由调用方决定怎么办。识别是锦上添花：
 * 主图已经上传成功了，模型不可达不该让「拍照设主图」这件事跟着失败。
 */
@Slf4j
@Component
public class GoodsVisionGateway implements GoodsVisionPort {

    /** 五品类。**必须与 CATEGORY_TYPE 一致** —— 模型返回别的值一律丢弃 */
    private static final List<String> TYPES = List.of("NORMAL", "FRESH", "SERVICE", "VIRTUAL", "CARD");

    private final ObjectMapper json = new ObjectMapper();
    /**
     * **必须钉死 HTTP/1.1**。
     *
     * <p>`HttpClient` 默认版本是 HTTP/2，而这是个**明文 http://** 地址 ——
     * 明文下没有 TLS 的 ALPN 可用，Java 于是走 h2c upgrade：首个请求按 HTTP/1.1 发，
     * 同时带上 `Connection: Upgrade` 与 `HTTP2-Settings` 问对面能不能升。
     *
     * <p>对面是 sglang（uvicorn/ASGI），**不支持 h2c**：它既没升成，也没干净地拒绝，
     * 而是在处理这个带升级头的请求时**把请求体丢了**，FastAPI 那侧报
     * `{'loc': ('body',), 'msg': 'Field required'}`。
     *
     * <p>实测三种设置，同一 JVM、同一包体、同一端点：
     * <pre>
     *   默认        → 400（body 缺失）
     *   HTTP_2     → 400（body 缺失）
     *   HTTP_1_1   → 200 ✅
     * </pre>
     * 三次的应答版本都是 HTTP_1_1 —— 升级从来没成功过，**光是「问一句」就够丢包体了**。
     *
     * <p>这个坑的欺骗性在于：报错来自模型服务端，看起来像「请求格式不对」，
     * 于是人会回去改提示词、改 JSON 结构、怀疑模型不支持多模态 —— 全都改不好，
     * 因为错的不在那一层。curl 与 python 默认就发普通 HTTP/1.1，压根不问，所以它们没事。
     *
     * <p>同仓库的 `WxAcodeGateway` / `WxAuthGateway` 也用 java.net.http 却没踩到：
     * 它们连的是 HTTPS，有 ALPN 能正常协商。**这个组合（明文 HTTP + 不支持 h2c 的服务端）
     * 恰恰是内网自建推理服务的常态。**
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

    public GoodsVisionGateway(
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

    /**
     * 看图猜商品。
     *
     * @param imageUrl   公开可访问的图片 URL（商品图落在公开桶，模型侧要能直接拉到）
     * @param categories 候选类目：编号 → 中文路径。**空 map 也可以**，那样就不猜类目
     * @return null = 没识别出来 / 模型不可达。调用方据此决定是提示还是静默
     */
    @Override
    public Guess recognize(String imageUrl, Map<String, String> categories) {
        if (!isEnabled() || imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        try {
            var body = Map.of(
                    "model", model,
                    "max_tokens", 300,
                    "temperature", 0.1,
                    // ★ 见类注释第 1 条：不关掉的话 content 永远是空串
                    "chat_template_kwargs", Map.of("enable_thinking", false),
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of("type", "text", "text", prompt(categories)),
                                    Map.of("type", "image_url",
                                            "image_url", Map.of("url", imageUrl))))));

            var req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json");
            if (!apiKey.isBlank()) {
                req.header("Authorization", "Bearer " + apiKey);
            }
            var resp = http.send(
                    req.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("商品识别失败：HTTP {} {}", resp.statusCode(), abbreviate(resp.body()));
                return null;
            }
            String content = json.readTree(resp.body())
                    .path("choices").path(0).path("message").path("content").asText("");
            return parse(content, categories);
        } catch (Exception e) {
            // 识别不该让上传跟着失败 —— 主图这时候已经存好了
            log.warn("商品识别异常：{}", e.toString());
            return null;
        }
    }

    /**
     * 生成图文详情正文。
     *
     * <p>与 {@link #recognize} 共用同一个 client 与同一条「必须关 thinking」的教训，
     * 但**不要 JSON**：这里要的就是一段纯文本，让模型套 JSON 只会多一层解析，
     * 而且它经常把换行转义得没法直接用。
     *
     * <p>token 预算给到 800：详情是长文，300 会在句子中间被截断 ——
     * 而截断的那一段看起来像模型写坏了，其实是配额到头了。
     */
    @Override
    public String describe(String imageUrl, String title, String subtitle, String category) {
        if (!isEnabled() || title == null || title.isBlank()) {
            return null;
        }
        try {
            // 有图就带图：同一件货，看得见实物写出来的描述具体得多
            var content = new java.util.ArrayList<Map<String, Object>>();
            content.add(Map.of("type", "text", "text", describePrompt(title, subtitle, category)));
            if (imageUrl != null && !imageUrl.isBlank()) {
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", imageUrl)));
            }
            var body = Map.of(
                    "model", model,
                    "max_tokens", 800,
                    // 详情要的是可读，不是可复现 —— 比识别那边的 0.1 高一些
                    "temperature", 0.6,
                    // ★ 同 recognize：不关掉的话 content 永远是空串
                    "chat_template_kwargs", Map.of("enable_thinking", false),
                    "messages", List.of(Map.of("role", "user", "content", content)));

            var req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json");
            if (!apiKey.isBlank()) {
                req.header("Authorization", "Bearer " + apiKey);
            }
            var resp = http.send(
                    req.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("详情生成失败：HTTP {} {}", resp.statusCode(), abbreviate(resp.body()));
                return null;
            }
            String text = json.readTree(resp.body())
                    .path("choices").path(0).path("message").path("content").asText("").trim();
            // 模型偶尔仍会套一层代码块，剥掉再给端上 —— 详情框里出现 ``` 很难看
            if (text.startsWith("```")) {
                int nl = text.indexOf('\n');
                int close = text.lastIndexOf("```");
                if (nl > 0 && close > nl) {
                    text = text.substring(nl + 1, close).trim();
                }
            }
            return text.isBlank() ? null : text;
        } catch (Exception e) {
            log.warn("详情生成异常：{}", e.toString());
            return null;
        }
    }

    /**
     * 详情提示词。**改之前先照着真模型跑至少 5 个样本**，一个样本说明不了任何事。
     *
     * <p>这一版是第三版，前两版都是这么栽的：
     *
     * <ol>
     *   <li><b>v1</b> 只写「不要编产地品牌保质期」「不要营销腔」。实测输出
     *       「本地散养土鸡蛋…蛋黄饱满紧实、色泽金黄诱人」—— 标题里只有
     *       「本地土鸡蛋 30枚」，散养/饱满/金黄全是编的。这些词不在那张字面清单里，
     *       模型不认为自己违规了。
     *   <li><b>v2</b> 把品质描述与营销话术各自举例，单跑一个样本很干净，于是以为成了。
     *       <b>跑 5 个样本才发现 4 个违规</b>，而且违的是最要命的一类：
     *       「明早截单，后天一早送到」—— 那是替商家对顾客做的送达承诺。
     *       根因有一半是 v2 自己招来的：它写着「可以写…下单与到货提醒」。
     *   <li><b>v3</b>（本版）：把「你只知道这三项、别的一概不知道」提到最前面，
     *       时间承诺单列为一类并说明理由（截单与到货由商家在别处填，
     *       模型写的任何时间都是错的），并把「可以写什么」收窄到
     *       <b>不依赖这件货具体信息</b>的常识。同一件商品 5 个样本、
     *       换一件商品再 4 个样本，编造与时间承诺都为 0。
     * </ol>
     *
     * <p><b>仍未解决</b>：日用品这类「没什么存放常识可讲」的货，模型会退回营销腔
     * （实测抽纸："纸质厚实不易破""干湿两用不掉屑" —— 都是编的产品属性）。
     * 关键词探针查不出这种，靠的是读输出。所以端上那句
     * 「结果只填进输入框、不直接保存」不是客套，是这个功能成立的前提。
     */
    private String describePrompt(String title, String subtitle, String category) {
        var sb = new StringBuilder("""
                你是社区团购的商品文案助手。为下面这件商品写一段图文详情正文。

                格式：
                · 纯文本，不要 Markdown、不要标题符号、不要代码块
                · 3 到 5 行，每行以「· 」开头，行与行之间不要空行
                · 总长 200 字以内

                **你只知道下面列出的「商品名、卖点、类目」这三项。别的一概不知道。**
                凡是这三项里没有的事实，一个字都不许写。尤其是这几类，写了就算错：

                · 养殖或种植方式：散养、土养、有机、无农药
                · 外观与口感：饱满、鲜嫩、金黄、香甜、颜色深、个头大、无破损
                · 产地、品牌、等级、保质期、认证、执行标准
                · 数量与库存：限量多少、每天多少、仅剩多少
                · **时间承诺：什么时候截单、什么时候送到、次日达、当天送**
                  —— 这些由商家在别处单独填，你写的任何时间都是错的
                · 营销话术：性价比高、值得拥有、老少皆宜、满足全家

                可以写的只有两类：
                1. 常识性的存放与食用/使用方法（这类不依赖这件货的具体信息）
                2. 从商品名里能直接读出的分量与适用场景

                宁可只写两行，也不要写一句你无法确认的话。口吻像店主平实地交代事情。
                """);
        sb.append("\n商品名：").append(title).append('\n');
        if (subtitle != null && !subtitle.isBlank()) {
            sb.append("卖点：").append(subtitle).append('\n');
        }
        if (category != null && !category.isBlank()) {
            sb.append("类目：").append(category).append('\n');
        }
        return sb.toString();
    }

    private String prompt(Map<String, String> categories) {
        var sb = new StringBuilder("""
                你是电商商品录入助手。看图，只输出一个 JSON 对象，不要解释、不要代码块。
                title：商品名，含品牌与规格，20字内
                subtitle：一句话卖点，20字内
                type：只能是 NORMAL(标品)/FRESH(生鲜)/SERVICE(服务)/VIRTUAL(虚拟)/CARD(卡券) 之一
                confidence：0到1的小数，表示你对以上判断的把握
                """);
        if (!categories.isEmpty()) {
            sb.append("categoryNo：只能从下列编号中选一个，拿不准给空串\n类目：\n");
            categories.forEach((no, name) -> sb.append(no).append('=').append(name).append('\n'));
        }
        return sb.toString();
    }

    /**
     * 解析模型输出。**容忍 ``` 代码块**：提示词里明说了不要，但模型仍会时不时套一层，
     * 而为这件事整条链路失败是不值当的。
     */
    private Guess parse(String content, Map<String, String> categories) {
        String s = content.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("商品识别：返回体里没有 JSON —— {}", abbreviate(content));
            return null;
        }
        try {
            JsonNode n = json.readTree(s.substring(start, end + 1));
            String type = n.path("type").asText("NORMAL");
            String categoryNo = n.path("categoryNo").asText("");
            return new Guess(
                    n.path("title").asText("").trim(),
                    n.path("subtitle").asText("").trim(),
                    // 模型给了不认识的品类就退回标品，而不是把脏值传下去
                    TYPES.contains(type) ? type : "NORMAL",
                    // **类目必须在候选表里**：查无此项的编号落进草稿，
                    // 商家要到保存那一刻才撞上校验，那时他已不记得是谁填的
                    categories.containsKey(categoryNo) ? categoryNo : "",
                    clamp(n.path("confidence").asDouble(0)));
        } catch (Exception e) {
            log.warn("商品识别：JSON 解析失败 —— {}", abbreviate(content));
            return null;
        }
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
