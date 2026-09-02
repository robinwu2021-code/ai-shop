package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每个 {@code /mp} 端点都必须**明确**它要不要登录。
 *
 * <h2>为什么 C 端需要这道闸，而它此前没有</h2>
 * <p>B 端有 {@link BizEndpointPermTest}：每加一个 {@code /biz} 端点都必须登记它要什么权限，
 * 没登记直接红。C 端一直没有对应物 —— {@code /mp/**} 整条安全链是 {@code permitAll}，
 * 登录靠散在各处的 161 处 {@code SecurityUtils.currentUserNo()} 各自把关。
 *
 * <p><b>那个设计本身是对的</b>：门店主页游客可看，未登录不该 401，
 * 所以才有刻意分开的 {@code currentUserNoOrNull()}。缺的只是这道闸 ——
 * 新加一个端点忘了取当前用户，它就是匿名可调的，<b>而没有任何地方会说</b>。
 *
 * <h2>判据是实弹，不是源码模式</h2>
 * <p>不扫 {@code currentUserNo} 的字面出现：90 个端点的强制是在 service 层做的，
 * 控制器里根本看不到。这里<b>真的不带令牌打一遍</b>，看回什么。
 *
 * <p>端点清单来自 {@code RequestMappingHandlerMapping} 而不是正则扫源码 ——
 * 少认一种 HTTP 方法、或注解写法一变，正则会静默漏掉端点而守卫照样绿。
 *
 * <h2>三个桶</h2>
 * <ul>
 *   <li>{@link #REQUIRES_LOGIN}：匿名调回 401。<b>这是主判据</b>，逐条实弹验证。</li>
 *   <li>{@link #ANONYMOUS}：匿名调回 0（成功）。游客可看的那些。</li>
 *   <li>{@link #UNDETERMINED}：<b>探测判不出来的</b>。路径变量是假的、
 *       或请求体是空的，于是业务校验（404/400）挡在鉴权之前 ——
 *       回的不是 401，但也不能据此说它不要登录。
 *       <b>这个桶里的每一条都需要人确认</b>，它是待办清单，不是许可。</li>
 * </ul>
 *
 * <p>加端点时三个桶都不在，这条用例会红并点名 —— 逼加的人回答一句「这个要不要登录」。
 * <b>这正是这类守卫存在的理由：不是防止今天写错，是防止明天忘记。</b>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("C 端 · 每个端点都要明确要不要登录")
class MpEndpointAuthTest {

    /** 匿名访问回 401，已逐条实弹验证。 */
    private static final Set<String> REQUIRES_LOGIN = Set.of(
            "GET /mp/after-sale",
            "GET /mp/after-sale/{afterSaleNo}",
            "GET /mp/cart",
            "GET /mp/coupon/mine",
            "GET /mp/group-buy/hosted",
            "GET /mp/invoice/mine",
            "GET /mp/invoice/order/{orderNo}",
            "GET /mp/merchant/apply",
            "GET /mp/merchant/visited",
            "GET /mp/message",
            "GET /mp/message/unread-count",
            "GET /mp/my-coupons",
            "GET /mp/my-memberships",
            "GET /mp/order",
            "GET /mp/order/{orderNo}",
            "GET /mp/order/{orderNo}/pay-result",
            // 收银台的支付方式列表（C-1）：要登录 —— 它按下单的商家算，是这个人的单
            "GET /mp/order/{orderNo}/pay-method",
            "GET /mp/points/account",
            "GET /mp/points/records",
            "GET /mp/store/{merchantNo}/frequent",
            "GET /mp/ticket",
            "GET /mp/ticket/{ticketNo}",
            "GET /mp/user/address",
            "GET /mp/user/profile",
            "POST /mp/after-sale/{afterSaleNo}/cancel",
            "POST /mp/after-sale/{afterSaleNo}/escalate",
            "POST /mp/after-sale/{afterSaleNo}/ship",
            "POST /mp/attribution/report",
            "POST /mp/cart/remove",
            "POST /mp/coupon/best",
            "POST /mp/coupon/{couponNo}/receive",
            "POST /mp/group-buy",
            "POST /mp/group-buy/{groupNo}/join",
            "POST /mp/group-buy/{groupNo}/verify",
            "POST /mp/group-request",
            "POST /mp/group-request/{requestNo}/confirm",
            "POST /mp/group-request/{requestNo}/interest",
            "POST /mp/invoice/apply",
            "POST /mp/merchant/apply",
            "POST /mp/message/read-all",
            "POST /mp/message/subscribe",
            "POST /mp/message/{messageNo}/read",
            "POST /mp/order",
            "POST /mp/order/capability",
            "POST /mp/order/preview",
            "POST /mp/order/{orderNo}/after-sale",
            "POST /mp/order/{orderNo}/cancel",
            "POST /mp/order/{orderNo}/confirm-receipt",
            "POST /mp/order/{orderNo}/pay",
            "POST /mp/order/{orderNo}/reorder",
            "POST /mp/push-token",
            "POST /mp/push-token/unregister",
            "POST /mp/review/{reviewNo}/like",
            "POST /mp/risk/appeal",
            "POST /mp/store/{merchantNo}/enter",
            "POST /mp/store/{merchantNo}/favorite",
            "POST /mp/store/{merchantNo}/rebuy",
            "POST /mp/ticket",
            "POST /mp/user/address/{addressId}/archive",
            "POST /mp/user/address/{addressId}/default",
            "POST /mp/user/deregister",
            "POST /mp/user/profile",
            "");

    /** 匿名访问回成功。游客可看。 */
    private static final Set<String> ANONYMOUS = Set.of(
            "GET /mp/goods/{goodsNo}",
            "GET /mp/merchant/{merchantNo}",
            "GET /mp/merchant/{merchantNo}/score",
            "GET /mp/pickup/{pickupNo}",
            "GET /mp/store/{merchantNo}",
            "GET /mp/after-sale/reasons",
            "GET /mp/category/tree",
            "GET /mp/community",
            "GET /mp/community/nearby",
            "GET /mp/community/regions",
            "GET /mp/config/bootstrap",
            "GET /mp/coupon",
            "GET /mp/goods",
            "GET /mp/goods/promoted",
            "GET /mp/group-buy",
            "GET /mp/group-request",
            "GET /mp/group-request/{requestNo}/price-history",
            "GET /mp/group-request/{requestNo}/quotes",
            "GET /mp/help/faq",
            "GET /mp/merchant",
            "GET /mp/merchant/promoted",
            "GET /mp/regions",
            "GET /mp/search/hot",
            "GET /mp/search/suggest",
            "GET /mp/store/mine",
            "GET /mp/topics",
            "GET /mp/topics/{topicNo}/goods",
            "GET /mp/user/phone/capable",
            "");

    /**
     * 探测判不出的。**待办清单，不是许可。**
     *
     * <p>它们回的是 404/400 —— 业务校验挡在了鉴权之前，于是判不出要不要登录。
     * <b>2026-08-28 用种子里的真号把 5 条判出来了</b>（商品、商家、商家评分、
     * 自提点、门店主页，全部匿名可看），剩下这些各自卡在：
     *
     * <ul>
     *   <li><b>缺种子数据</b>：团购、求团、小区详情 —— 测试库里没有对应的号，
     *       给了假号就 404。要判定得先补种子。</li>
     *   <li><b>缺合法请求体</b>：购物车、地址、评价、绑手机等写接口 ——
     *       空 body 在参数校验就被挡下。</li>
     *   <li><b>本来就是登录流程</b>：login / otp/send / phone/wx /
     *       token/refresh —— 它们的存在就是为了让还没登录的人用。</li>
     * </ul>
     *
     * <p><b>{@code GET /mp/group-buy/&#123;groupNo&#125;/orders} 单独说一句</b>：
     * 它返回团里其他人的订单（买家昵称、核销码）。源码上它走
     * {@code requireOwner} → {@code currentUserNo()}，用真实团号打过确实是 401 ——
     * 但这里给不出真团号，所以留在这一桶里，<b>不是因为它没被保护</b>。
     *
     * <p>其中三条另有问题：{@code logout}、{@code token/refresh} 的
     * {@code @RequestHeader("Authorization")} 是必填的，缺了会被渲染成
     * <b>10500 服务器内部错误</b>而不是 400 —— 客户端的错在监控里长成服务端故障。
     */
    private static final Set<String> UNDETERMINED = Set.of(
            "GET /mp/community/{communityNo}",   // 探测得到 200/code=10404
            "GET /mp/goods/{goodsNo}/sku-price",   // 探测得到 200/code=10400
            "GET /mp/group-buy/{groupNo}",   // 探测得到 200/code=10404
            "GET /mp/group-buy/{groupNo}/orders",   // 探测得到 200/code=10404
            "GET /mp/group-request/{requestNo}",   // 探测得到 200/code=10404
            "GET /mp/points/deductible",   // 探测得到 200/code=10400
            "GET /mp/review",   // 探测得到 200/code=10400
            "GET /mp/store/by-code",   // 探测得到 200/code=10400
            "GET /mp/stores/{storeNo}/appointment-slots",   // 探测得到 200/code=10400
            "POST /mp/cart/add",   // 探测得到 200/code=10400
            "POST /mp/cart/update",   // 探测得到 200/code=10400
            "POST /mp/group-buy/{groupNo}/receive",   // 探测得到 200/code=10404
            "POST /mp/group-request/{requestNo}/choose",   // 探测得到 200/code=10404
            "POST /mp/review",   // 探测得到 200/code=10400
            "POST /mp/user/address",   // 探测得到 200/code=10400
            "POST /mp/user/community",   // 探测得到 200/code=10400
            "POST /mp/user/login",   // 探测得到 200/code=10400
            "POST /mp/user/logout",   // 探测得到 200/code=10500
            "POST /mp/user/otp/send",   // 探测得到 200/code=10500
            "POST /mp/user/phone/bind",   // 探测得到 200/code=10500
            "POST /mp/user/phone/wx",   // 探测得到 200/code=70027
            "POST /mp/user/token/refresh",   // 探测得到 200/code=10500
            "PUT /mp/my-memberships/{entityNo}/reach",   // 探测得到 200/code=10400
            "");

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 每个 /mp 端点都必须在三个桶之一里 —— 没登记的直接报出来")
    void everyMpEndpointIsClassified() {
        Set<String> all = allMpEndpoints();
        assertThat(all).as("一个端点都没枚举到，装配变了？").isNotEmpty();

        Set<String> unlisted = new TreeSet<>(all);
        unlisted.removeAll(REQUIRES_LOGIN);
        unlisted.removeAll(ANONYMOUS);
        unlisted.removeAll(UNDETERMINED);
        assertThat(unlisted)
                .as("""
                        这些 /mp 端点还没决定要不要登录：%s

                        每一个都可能是匿名可调的口子。跑一遍不带令牌的请求看它回什么，
                        然后加进 REQUIRES_LOGIN / ANONYMOUS；判不出来的放 UNDETERMINED
                        并说明为什么判不出。""".formatted(unlisted))
                .isEmpty();
    }

    @Test
    @DisplayName("★★★ 登记为「要登录」的，匿名访问必须 401 —— 这是唯一的实弹判据")
    void loginRequiredEndpointsRejectAnonymous() {
        List<String> leaked = new ArrayList<>();
        for (String ep : REQUIRES_LOGIN) {
            if (ep.isEmpty()) {
                continue;
            }
            int status = callAnonymously(ep);
            if (status != 401) {
                // 带上 HTTP 状态就够定位了：200 基本就是「被 @Valid 挡在鉴权之前」，
                // 那时要补的是 PROBE_BODY，不是这条判据（理由见 PROBE_BODY 的注释）
                leaked.add(ep + " → " + status);
            }
        }
        assertThat(leaked)
                .as("""
                        这些端点登记为「要登录」，但匿名访问没有被拒：%s

                        要么是有人删掉了取当前用户那一步（于是它成了匿名可调的口子），
                        要么是这条登记本来就错。两种都要当场查清楚。""".formatted(leaked))
                .isEmpty();
    }

    @Test
    @DisplayName("★ 登记为「游客可看」的，匿名访问必须真的成功 —— 不是「没被拒」而已")
    void anonymousEndpointsStayOpen() {
        List<String> broken = new ArrayList<>();
        for (String ep : ANONYMOUS) {
            if (ep.isEmpty()) {
                continue;
            }
            /*
             * **判据是业务码 0，不是「不等于 401」。**
             *
             * 只查 401 的话，一个回 404 的端点也算通过 —— 于是把
             * {@link #SEED} 里的真号换回假占位符，这条用例照样绿，
             * 而后续所有判定都建立在「资源不存在」这种无效响应上。
             * 用成功码钉住，就等于同时钉住了「种子号还有效」。
             */
            String outcome = probeCode(ep);
            if (!"200/0".equals(outcome)) {
                broken.add(ep + " → " + outcome);
            }
        }
        assertThat(broken)
                .as("""
                        这些端点登记为游客可看，匿名访问却没有成功：%s

                        401 = 改成要登录了（可以，但要顺手改这张表）；
                        404/400 = 多半是 SEED 里的种子号失效了 —— 那会让整张表的判定失去依据。"""
                        .formatted(broken))
                .isEmpty();
    }

    /** 匿名打一次，回「HTTP 状态/业务码」。业务码取自响应体 —— 全局信封把状态统一成 200。 */
    private String probeCode(String endpoint) {
        String[] parts = endpoint.split(" ", 2);
        String url = parts[1];
        for (var e : SEED.entrySet()) {
            url = url.replace("{" + e.getKey() + "}", e.getValue());
        }
        url = url.replaceAll("\\{[^}]+\\}", "PROBE1");
        try {
            var res = mvc().perform(MockMvcRequestBuilders.get(url)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse();
            var m = java.util.regex.Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)")
                    .matcher(res.getContentAsString());
            return res.getStatus() + "/" + (m.find() ? m.group(1) : "?");
        } catch (Exception e) {
            return "EX:" + e.getClass().getSimpleName();
        }
    }

    @Test
    @DisplayName("待确认的那一桶只能变短，不能变长")
    void undeterminedMustNotGrow() {
        // 23 条。建表时是 28，用种子真号判出 5 条。**这个数字只许往下走** ——
        // 新端点往这个桶里一塞就等于绕过了整道闸
        assertThat(UNDETERMINED.size() - 1)
                .as("判不出的端点变多了：新端点不能往这个桶里塞，它是待办不是许可")
                .isLessThanOrEqualTo(23);
    }

    /**
     * 路径变量用<b>种子里真实存在的号</b>，不用占位符。
     *
     * <p>占位符会让业务校验（「没有这个商品」）挡在鉴权之前 ——
     * 回的是 404 而不是 401，于是判不出这个端点到底要不要登录。
     * 换成真号，请求能走到鉴权那一步，401 与否才是可信的判据。
     */
    private static final java.util.Map<String, String> SEED = java.util.Map.of(
            "goodsNo", "G0001", "merchantNo", "M0001", "communityNo", "CM001",
            "pickupNo", "PP0001", "storeNo", "ST-TEST", "skuNo", "SK0001");

    /** 不带令牌打一次，回状态码。 */
    private int callAnonymously(String endpoint) {
        String[] parts = endpoint.split(" ", 2);
        String url = parts[1];
        for (var e : SEED.entrySet()) {
            url = url.replace("{" + e.getKey() + "}", e.getValue());
        }
        url = url.replaceAll("\\{[^}]+\\}", "PROBE1");
        var req = switch (parts[0]) {
            case "POST" -> MockMvcRequestBuilders.post(url);
            case "PUT" -> MockMvcRequestBuilders.put(url);
            case "DELETE" -> MockMvcRequestBuilders.delete(url);
            case "PATCH" -> MockMvcRequestBuilders.patch(url);
            default -> MockMvcRequestBuilders.get(url);
        };
        try {
            return mvc().perform(req.contentType(MediaType.APPLICATION_JSON)
                            .content(PROBE_BODY.getOrDefault(endpoint, "{}")))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 探针 body：**要能过 `@Valid`**，否则这条实弹判据会被校验层挡在鉴权之前。
     *
     * <p>接上 `@Valid` 之后（2026-08-29），`@RequestBody` 的校验发生在**参数解析**阶段 ——
     * 早于控制器方法体里那句取当前用户，也早于 `@PreAuthorize`。于是空 body `{}`
     * 打到这几个端点上，回的是 200 + 10400「参数有误」，而不是 401。
     *
     * <p>那样这条用例就废了，而且是**最坏的废法**：它照旧绿着。一个真的丢了鉴权
     * 那一步的端点，只要 DTO 上有一个 `@NotBlank`，空 body 就永远走不到鉴权，
     * 于是「匿名被拒了」——拒它的是校验，不是登录。**判据必须踩到被测的那一层。**
     *
     * <p>所以这里给能过校验的最小 body。将来谁给这些 DTO 加了新的必填字段，
     * 这条用例会红并把 10400 打出来 —— 那时补一个字段进来，不要改判据。
     */
    private static final java.util.Map<String, String> PROBE_BODY = java.util.Map.of(
            "POST /mp/push-token", "{\"platform\":\"ANDROID\",\"clientId\":\"PROBE\"}",
            "POST /mp/ticket", "{\"subject\":\"探针\",\"content\":\"探针\"}",
            "POST /mp/order/{orderNo}/after-sale", "{\"type\":\"REFUND\",\"reason\":\"探针\"}",
            "POST /mp/after-sale/{afterSaleNo}/ship", "{\"expressNo\":\"PROBE1\"}",
            "POST /mp/group-request", "{\"title\":\"探针\"}");

    private String probeDetail(String endpoint) {
        String[] parts = endpoint.split(" ", 2);
        String url = parts[1];
        for (var e : SEED.entrySet()) {
            url = url.replace("{" + e.getKey() + "}", e.getValue());
        }
        url = url.replaceAll("\\{[^}]+\\}", "PROBE1");
        var req = switch (parts[0]) {
            case "POST" -> MockMvcRequestBuilders.post(url);
            case "PUT" -> MockMvcRequestBuilders.put(url);
            case "DELETE" -> MockMvcRequestBuilders.delete(url);
            case "PATCH" -> MockMvcRequestBuilders.patch(url);
            default -> MockMvcRequestBuilders.get(url);
        };
        try {
            var res = mvc().perform(req.contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse();
            var m = java.util.regex.Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)")
                    .matcher(res.getContentAsString());
            return res.getStatus() + "/" + (m.find() ? m.group(1) : "?");
        } catch (Exception e) {
            return "EX";
        }
    }

    private Set<String> allMpEndpoints() {
        Set<String> out = new TreeSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            var patterns = info.getPathPatternsCondition();
            if (patterns == null) {
                return;
            }
            String verb = info.getMethodsCondition().getMethods().stream()
                    .map(Enum::name).findFirst().orElse("GET");
            patterns.getPatternValues().stream()
                    .filter(p -> p.startsWith("/mp/"))
                    .forEach(p -> out.add(verb + " " + p));
        });
        return out;
    }

    @Test
    @DisplayName("★ 缺必填请求头是 400 不是 500 —— 客户端的错不该在监控里长成服务端故障")
    void missingRequiredHeaderIsBadRequestNotInternalError() throws Exception {
        /*
         * /mp/user/logout 与 /mp/user/token/refresh 的 @RequestHeader("Authorization")
         * 是必填的。此前缺了会掉进兜底的 Exception 处理器 → 10500「服务器内部错误」。
         *
         * 判据是响应体里的 code：HTTP 状态被全局信封统一成 200，
         * 只看状态码分不出「跑成了」和「炸了」。
         */
        for (String url : List.of("/mp/user/logout", "/mp/user/token/refresh")) {
            String body = mvc().perform(MockMvcRequestBuilders.post(url)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body)
                    .as("%s 缺 Authorization 头时回的应该是 10400，不是 10500", url)
                    .contains("10400")
                    .doesNotContain("10500");
        }
    }
}
