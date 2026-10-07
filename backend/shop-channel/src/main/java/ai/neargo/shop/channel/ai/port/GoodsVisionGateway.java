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
     * 从一段商品文字里抽结构化信息（「文字也走 LLM」）。纯文字,无图;只要 JSON。
     *
     * <p>与 {@link #recognize} 同一个 client、同一条「必须关 thinking」的教训。
     * token 给到 500:参数+省份连写时 JSON 会比图片识别长。失败一律 null,调用方退回纯规则。
     */
    @Override
    public ai.neargo.shop.spi.product.GoodsVisionPort.TextExtract extractText(String text) {
        return extractText(text, java.util.List.of());
    }

    @Override
    public ai.neargo.shop.spi.product.GoodsVisionPort.TextExtract extractText(
            String text, java.util.List<ai.neargo.shop.spi.product.GoodsVisionPort.ParamHint> hints) {
        if (!isEnabled() || text == null || text.isBlank()) {
            return null;
        }
        try {
            var body = Map.of(
                    "model", model,
                    "max_tokens", 500,
                    "temperature", 0.1,
                    "chat_template_kwargs", Map.of("enable_thinking", false),
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", textPrompt(hints) + "\n\n商品文字：\n" + text)));
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
                log.warn("文字识别失败：HTTP {} {}", resp.statusCode(), abbreviate(resp.body()));
                return null;
            }
            String content = json.readTree(resp.body())
                    .path("choices").path(0).path("message").path("content").asText("");
            return parseExtract(content);
        } catch (Exception e) {
            log.warn("文字识别异常：{}", e.toString());
            return null;
        }
    }

    /**
     * 文字抽取提示词（见 docs/technical/design 的识别 prompt 方案）。
     *
     * <p>给了清单就多一段「标准参数」：**模型的任务是映射，不是发明**。
     * 没有这一段时模型会自由起名 —— 「净重」—— 而这个品类的标准参数叫「净含量」，
     * 识别出来的值于是成了一条游离的自由参数（2026-10-07 在生产草稿里实测到）。
     */
    private String textPrompt(java.util.List<ai.neargo.shop.spi.product.GoodsVisionPort.ParamHint> hints) {
        return baseTextPrompt() + hintSection(hints);
    }

    /**
     * 「只能往这里落」那一段。每行一个标准参数：维度号 · 名称 · 值类型。
     * 清单为空（不知道品类）时不加这一段，模型照旧自由抽取。
     */
    private static String hintSection(java.util.List<ai.neargo.shop.spi.product.GoodsVisionPort.ParamHint> hints) {
        if (hints == null || hints.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("""


                【这个商品的标准参数】params 里的每一项**必须**从下表选，"dimNo" 原样照抄表里的维度号，
                "name" 写表里的名称（不要用原文的叫法）。原文说「净重」而表里叫「净含量」，就落「净含量」。
                表里没有对应项的，"dimNo" 留空串，name 写原文叫法 —— 不要硬塞进一个意思不同的参数。
                """);
        for (var h : hints) {
            sb.append("- ").append(h.dimNo()).append(" · ").append(h.name());
            if (h.valueType() != null && !h.valueType().isBlank()) {
                sb.append(" · ").append(h.valueType());
            }
            sb.append('\n');
        }
        sb.append("""
                此时 params 每项写成 {"dimNo":维度号,"name":名称,"value":值}。""");
        return sb.toString();
    }

    private String baseTextPrompt() {
        return """
                你是社区团购的商品信息抽取助手。给你一段商家随手写的商品文字,把结构化信息抽出来,
                只输出一个 JSON 对象,不要解释、不要代码块、不要编造。

                字段与规则:
                - name: 商品名(没有明确品名就留空串)。
                - params: 数组,每项 {"name":属性名,"value":属性值}。常见属性名:单果重量、净重、规格、产地、等级、口感、品牌、保质期、配料。
                  **单果重量 与 净重 是两回事**:单果/单个/每颗 说的是一颗(如"单果140g+"→单果重量=140g+);
                  净重/净含量/装 说的是整件(如"净重4.5斤装"→净重=4.5斤)。两者都有就各记一条,绝不合并。
                  只记明确写了的,没写的属性不要出现,不要编产地/品牌/保质期。
                - priceYuan: 数字,售价(元)。价格常和分量连写(如"4.5斤装10元"=4.5斤卖10元),把价格单独拆出来,分量留在净重。没有就 null。
                - fulfillment: 数组,取值只能 "EXPRESS"(快递)/"MERCHANT_DELIVERY"(自送)/"STORE_PICKUP"(自提)。出现 快递/发货/包邮/圆通/中通/顺丰/韵达/申通/邮政 等→加 "EXPRESS"。没提给空数组。
                - courier: 承运快递公司(圆通/顺丰…),没提留空串。
                - provinces: 数组,**不发货/不包邮/不卖**到的省,元素是省全名(如"新疆维吾尔自治区")。
                  "新疆西藏海南不发货"这种多省连写要逐个拆开补全:新疆→新疆维吾尔自治区、西藏→西藏自治区、海南→海南省、内蒙→内蒙古自治区、广西→广西壮族自治区、宁夏→宁夏回族自治区。
                  只收"不发货/不包邮/不卖/除…外"这类排除语义的省,正常销售地区不要进来。没有给空数组。
                - confidence: 0到1的小数。

                拿不准的字段一律留空/空数组/null,留空是合法答案,不要为了填满而猜。

                示例输入:
                规格：单果140g+ 净重4.5斤装10元 圆通快递，新疆西藏海南不发货
                示例输出:
                {"name":"","params":[{"name":"单果重量","value":"140g+"},{"name":"净重","value":"4.5斤"}],"priceYuan":10,"fulfillment":["EXPRESS"],"courier":"圆通","provinces":["新疆维吾尔自治区","西藏自治区","海南省"],"confidence":0.9}""";
    }

    /**
     * 压缩包文件结构 → 标准结构（TDD-商品压缩包导入 AC11）。只发路径与宽高，不发图片。
     *
     * <p>token 按文件数给：一条输出约 40 token，200 个文件也装得下。失败一律 null，调用方全按规则。
     */
    @Override
    public java.util.List<ai.neargo.shop.spi.product.GoodsVisionPort.ZipPick> mapZip(
            String title, String category,
            java.util.List<ai.neargo.shop.spi.product.GoodsVisionPort.ZipFile> files, String txtPreview) {
        if (!isEnabled() || files == null || files.isEmpty()) {
            return null;
        }
        try {
            var body = Map.of(
                    "model", model,
                    "max_tokens", Math.min(8000, 200 + 60 * files.size()),
                    "temperature", 0,
                    "chat_template_kwargs", Map.of("enable_thinking", false),
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", zipPrompt(title, category, files, txtPreview))));
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
                log.warn("压缩包分类失败：HTTP {} {}", resp.statusCode(), abbreviate(resp.body()));
                return null;
            }
            String content = json.readTree(resp.body())
                    .path("choices").path(0).path("message").path("content").asText("");
            return parseZip(content);
        } catch (Exception e) {
            log.warn("压缩包分类异常：{}", e.toString());
            return null;
        }
    }

    private String zipPrompt(String title, String category,
                             java.util.List<ai.neargo.shop.spi.product.GoodsVisionPort.ZipFile> files,
                             String txtPreview) {
        var sb = new StringBuilder();
        sb.append("你在帮商家整理一件商品的图片压缩包。按文件的路径（目录名、文件名）与宽高，")
                .append("把每个文件归到下面四类之一，只输出 JSON。\n\n")
                .append("MAIN   主图：商品方图（宽高接近 1:1），白底、正面、细节、场景都算；第一张是封面\n")
                .append("DETAIL 详情：详情页长图（高明显大于宽），按阅读顺序\n")
                .append("TEXT   文案：商品介绍的 .txt 文件\n")
                .append("IGNORE 不导入：资质证照、检测报告、缩略图、重复的、和这件商品无关的\n\n")
                .append("规则：目录名与文件名的意思优先（如「首图」「白底」「主图」→MAIN，「长图」「详情」→DETAIL，")
                .append("「资质」「证书」「报告」→IGNORE）；名字看不出时看宽高；")
                .append("文件名里的数字决定顺序（2 在 10 前面）；「封面」「首图」或序号最小的那张标 cover。\n\n");
        if (title != null && !title.isBlank()) {
            sb.append("商品：").append(title.trim()).append("\n");
        }
        if (category != null && !category.isBlank()) {
            sb.append("类目：").append(category).append("\n");
        }
        sb.append("文件（路径 · 宽×高）：\n");
        for (var f : files) {
            sb.append(f.path());
            if (f.width() != null && f.height() != null) {
                sb.append(" · ").append(f.width()).append("×").append(f.height());
            }
            sb.append("\n");
        }
        if (txtPreview != null && !txtPreview.isBlank()) {
            String t = txtPreview.length() > 500 ? txtPreview.substring(0, 500) : txtPreview;
            sb.append("\ntxt 开头：").append(t.replace("\n", " ")).append("\n");
        }
        sb.append("\n输出：{\"files\":[{\"path\":\"原样照抄\",\"target\":\"MAIN|DETAIL|TEXT|IGNORE\",")
                .append("\"order\":同类内从1起的序号,\"cover\":true|false}]}，每个文件恰好一条，路径一个字都不改。");
        return sb.toString();
    }

    /** 解析压缩包分类的 JSON（容忍 ``` 代码块）。逐条校验交给调用方，这里只管形状 */
    private java.util.List<ai.neargo.shop.spi.product.GoodsVisionPort.ZipPick> parseZip(String content) {
        String s = content == null ? "" : content.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("压缩包分类：返回体里没有 JSON —— {}", abbreviate(content));
            return null;
        }
        try {
            var out = new java.util.ArrayList<ai.neargo.shop.spi.product.GoodsVisionPort.ZipPick>();
            for (var f : json.readTree(s.substring(start, end + 1)).path("files")) {
                String path = f.path("path").asText("").trim();
                if (!path.isEmpty()) {
                    out.add(new ai.neargo.shop.spi.product.GoodsVisionPort.ZipPick(
                            path, f.path("target").asText("").trim().toUpperCase(),
                            f.path("order").asInt(0), f.path("cover").asBoolean(false)));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("压缩包分类：JSON 解析失败 —— {}", abbreviate(content));
            return null;
        }
    }

    /** 解析文字抽取的 JSON（容忍 ``` 代码块，同 {@link #parse}）。 */
    private ai.neargo.shop.spi.product.GoodsVisionPort.TextExtract parseExtract(String content) {
        String s = content == null ? "" : content.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("文字识别：返回体里没有 JSON —— {}", abbreviate(content));
            return null;
        }
        try {
            var node = json.readTree(s.substring(start, end + 1));
            var params = new java.util.ArrayList<ai.neargo.shop.spi.product.GoodsVisionPort.ParamKV>();
            for (var p : node.path("params")) {
                String name = p.path("name").asText("").trim();
                String value = p.path("value").asText("").trim();
                // 模型选的维度号。空串当没选 —— 调用方还要再核一次它确实在清单里（模型会编）
                String dimNo = p.path("dimNo").asText("").trim();
                if (!name.isEmpty() && !value.isEmpty()) {
                    params.add(new ai.neargo.shop.spi.product.GoodsVisionPort.ParamKV(
                            name, value, dimNo.isEmpty() ? null : dimNo));
                }
            }
            var fulfillment = new java.util.ArrayList<String>();
            for (var fnode : node.path("fulfillment")) {
                String v = fnode.asText("").trim();
                if (!v.isEmpty()) {
                    fulfillment.add(v);
                }
            }
            var provinces = new java.util.ArrayList<String>();
            for (var pr : node.path("provinces")) {
                String v = pr.asText("").trim();
                if (!v.isEmpty()) {
                    provinces.add(v);
                }
            }
            Double priceYuan = node.hasNonNull("priceYuan") && node.path("priceYuan").isNumber()
                    ? node.path("priceYuan").asDouble() : null;
            return new ai.neargo.shop.spi.product.GoodsVisionPort.TextExtract(
                    node.path("name").asText("").trim(), params, priceYuan,
                    fulfillment, node.path("courier").asText("").trim(),
                    provinces, node.path("confidence").asDouble(0d));
        } catch (Exception e) {
            log.warn("文字识别：JSON 解析失败 —— {}", abbreviate(content));
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
    public String describe(String imageUrl, String title, String subtitle, String category,
                           java.util.List<ai.neargo.shop.spi.product.GoodsVisionPort.ParamKV> facts) {
        if (!isEnabled() || title == null || title.isBlank()) {
            return null;
        }
        try {
            // 有图就带图：同一件货，看得见实物写出来的描述具体得多
            var content = new java.util.ArrayList<Map<String, Object>>();
            content.add(Map.of("type", "text", "text", describePrompt(title, subtitle, category, facts)));
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
     * 按候选值挑参数。与 {@link #describe} 共用同一个模型与同一条取舍：
     * <b>结果是草稿，端上摆给商家点确认，不直接落库</b>。
     *
     * <p><b>返回前逐个核验</b>：只保留「维度名在 candidates 里」且「值也在那一维的候选里」
     * 的条目。不核验的话，模型编出来的「储存条件：冷鲜」会带着一个不存在的 valueNo
     * 走到建品页上 —— 而商家看到的是一个看起来很正常的选项。
     * describePrompt 那段注释记着同一类教训：字面清单拦不住模型，只有事后核验拦得住。
     */
    @Override
    public Map<String, String> suggestParams(String imageUrl, String title, String subtitle,
                                             String category, Map<String, List<String>> candidates) {
        if (!isEnabled() || title == null || title.isBlank() || candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        try {
            var content = new java.util.ArrayList<Map<String, Object>>();
            content.add(Map.of("type", "text", "text", paramsPrompt(title, subtitle, category, candidates)));
            if (imageUrl != null && !imageUrl.isBlank()) {
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", imageUrl)));
            }
            var body = Map.of(
                    "model", model,
                    "max_tokens", 400,
                    // 挑选要的是可复现，不是文采 —— 与 recognize 同一档，比 describe 低
                    "temperature", 0.1,
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
                log.warn("参数建议失败：HTTP {} {}", resp.statusCode(), abbreviate(resp.body()));
                return Map.of();
            }
            String text = json.readTree(resp.body())
                    .path("choices").path(0).path("message").path("content").asText("").trim();
            if (text.startsWith("```")) {
                int nl = text.indexOf('\n');
                int close = text.lastIndexOf("```");
                if (nl > 0 && close > nl) {
                    text = text.substring(nl + 1, close).trim();
                }
            }
            var picked = new java.util.LinkedHashMap<String, String>();
            var node = json.readTree(text);
            for (var e : candidates.entrySet()) {
                String v = node.path(e.getKey()).asText("").trim();
                // 空 = 模型没把握，这是允许的结果；不在候选里 = 它编的，丢掉
                if (!v.isEmpty() && e.getValue().contains(v)) {
                    picked.put(e.getKey(), v);
                }
            }
            return picked;
        } catch (Exception ex) {
            log.warn("参数建议异常：{}", ex.toString());
            return Map.of();
        }
    }

    /**
     * 参数提示词。要的是**挑**，所以把话说死：只许从给定清单里选、没把握就留空。
     *
     * <p>「没把握就留空」必须写进去并且给它一条出路（留空是合法答案），
     * 否则模型会为了完成任务而硬选一个 —— 那正是 describePrompt 踩过的坑：
     * 不给合法的「不知道」，它就编一个看起来合理的。
     */
    private String paramsPrompt(String title, String subtitle, String category,
                                Map<String, List<String>> candidates) {
        var sb = new StringBuilder("""
                你是社区团购的商品资料助手。下面是一件商品，请为它挑选商品参数。

                规则：
                · 每一项**只能从给定的候选里选一个**，原样照抄那个词
                · 拿不准就**留空字符串**，留空是完全可以接受的答案，不要硬选
                · 只输出 JSON 对象，不要解释、不要代码块

                """);
        sb.append("商品名：").append(title).append('\n');
        if (subtitle != null && !subtitle.isBlank()) {
            sb.append("卖点：").append(subtitle).append('\n');
        }
        if (category != null && !category.isBlank()) {
            sb.append("类目：").append(category).append('\n');
        }
        sb.append("\n候选：\n");
        candidates.forEach((dim, values) ->
                sb.append("· ").append(dim).append("：").append(String.join("、", values)).append('\n'));
        sb.append("\n输出示例：{");
        sb.append(candidates.keySet().stream().map(k -> "\"" + k + "\": \"\"")
                .collect(java.util.stream.Collectors.joining(", ")));
        sb.append("}");
        return sb.toString();
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
    // 包内可见（不是 private）：GoodsDescribePromptTest 直接比对提示词文本。
    // 判据取文本而不是模型输出 —— 模型是外部依赖、输出不确定，拿它当断言
    // 就是把闸门建在别人家的服务上（known-failures.txt 头部：恒红的闸门等于没有闸门）。
    String describePrompt(String title, String subtitle, String category,
                          java.util.List<ai.neargo.shop.spi.product.GoodsVisionPort.ParamKV> facts) {
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
        /*
         * **已知事实**（TDD-商品描述带参数生成 AC1）。
         *
         * 只在真有参数时追加 —— 空时这段一个字都不拼，提示词与加这个参数之前**逐字相同**
         * （AC3 钉的就是这条：老调用方不发 params，行为不能变）。
         *
         * 为什么这不算放宽 v3：v3 禁的是「写你不知道的事」，而这些值是商家自己在建品页
         * 填的、经过候选值核验的结构化数据 —— 模型照抄它们不是编造。禁写清单原样保留。
         *
         * **滤掉售后类参数**（AC2 的一半）：那是商家自由起名的一格，内容多为
         * 「坏果包赔」「七天无理由」。喂给模型，它会把这些织进正文 —— 而那正是
         * v2 栽过的那一类（「明早截单，后天一早送到」），对顾客是一条我们兑不了的承诺。
         * C 端详情页出于同一个理由也把这一格挡掉了（goods/index.vue 的 facts 计算）。
         */
        var known = (facts == null ? java.util.List.<ai.neargo.shop.spi.product.GoodsVisionPort.ParamKV>of() : facts)
                .stream()
                .filter(f -> f != null && f.name() != null && !f.name().isBlank()
                        && f.value() != null && !f.value().isBlank())
                .filter(f -> !f.name().contains("售后"))
                .toList();
        if (!known.isEmpty()) {
            sb.append("""

                    已知事实（商家自己在建品页填的，和上面三项一样可以写进正文）：
                    """);
            for (var f : known) {
                sb.append("· ").append(f.name()).append('：').append(f.value()).append('\n');
            }
            sb.append("""
                    **这几条只能照抄，不许在它们之上引申。** 填了「常温」不等于可以写
                    「常温保存更香甜」，填了「脆爽」不等于可以写「咬一口脆到掉渣」——
                    那些仍然是你不知道的事。清单之外的一切，上面的禁写规则一个字都不放宽。
                    """);
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
