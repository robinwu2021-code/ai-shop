package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 三端令牌的**六种交叉组合都必须被拒**。
 *
 * <h2>为什么这道闸要在 HTTP 层再来一遍</h2>
 * <p>{@code RealmIsolationTest} 已经覆盖了**存储层**：前缀互不相同、跨池查不到。
 * 但它回答不了这个问题 —— <b>一个完全有效的 {@code otk_} 打到 {@code /biz/**} 会怎样？</b>
 * 存储层能正常解析出这条会话（它确实存在、确实没过期），拦不拦得住取决于
 * 认证过滤器有没有比对 realm。<b>而那正是 2026-08-28 出事的那一层。</b>
 *
 * <p>那天 A7（B 端改发 {@code btk_}）落地时，{@code BizContextFilter} 的判据还写着
 * {@code isConsumer()} —— A7 之前店主与店员拿的都是 {@code ctk_}，判据成立；
 * 改完之后它对谁都不成立，商家登进去后**每个操作都 403**。不是 401，
 * 所以看不出是登录问题；而 {@code resolve()} 从头到尾没被调用过。
 *
 * <p>当时补的守卫只覆盖了一格（{@code ctk_ → /biz}）。这份把六格补齐 ——
 * <b>三端两两交叉，每一格都可能因为某个判据写错而悄悄敞开。</b>
 *
 * <h2>为什么断言的是「认证失败」而不是「非零」</h2>
 * <p>拒绝的方式要分得开：<b>401 是「这个令牌不属于这条链」，403 是「你是谁我认了，
 * 但你没这个权限」。</b> 只断言 code≠0 的话，一个把跨端令牌**认成了某个身份**、
 * 只是恰好没权限的实现同样能过 —— 而那种实现是真正的越权口子。
 */
@SpringBootTest
@ActiveProfiles("test")
class CrossRealmRejectionTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OtpStore otpStore;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** C 端：{@code ctk_}。 */
    private String consumerToken() throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, "13500135801");
    }

    /**
     * B 端：{@code btk_}（店主那一支，主体是 user_no）。
     *
     * <p>**刻意只登录、不走入驻。** 这里要的是「一个有效的 B 端令牌」，
     * 而不是「一个有店的商家」—— 后者需要申请+审核+激活，而我试过：
     * 单独在这个测试里跑完那条链路，商家仍然是 {@code status=NONE}
     * （同样的写法在 M9b 里是通的，差别没查出来）。
     * <b>与其把夹具改造到绿，不如把这条测试的主张收窄到它真能证明的那件事。</b>
     */
    private String merchantToken() throws Exception {
        return TestLogin.merchantOwner(mvc(), json, otpStore, "13500135802");
    }

    /** 运营端：{@code otk_}。 */
    private String operatorToken() throws Exception {
        return TestLogin.admin(mvc(), json);
    }

    /**
     * 三端各挑一个「必须登录才能用」的代表端点。
     *
     * <p>刻意挑不需要额外权限码的：要是挑了个还要 {@code @perm.can} 的端点，
     * 拒绝可能来自权限而不是 realm，这条断言就证明不了它想证明的事。
     */
    private static final String MP = "/mp/user/profile";
    private static final String BIZ = "/biz/merchant/profile";
    private static final String OPS = "/ops/auth/me";

    /**
     * 正向那条**不能用上面那些端点**。
     *
     * <p>{@code /biz/merchant/profile} 不需要经营作用域 —— {@code BizContext} 是空的
     * 它照样返回 0。于是「B 端链路是通的」这句话被证明得太弱：
     * 2026-08-28 把 {@code BizContextFilter} 的判据改回坏的那一版，这条断言依然全绿。
     *
     * <p>{@code /biz/context} 不一样：它的返回体里就是解析出来的主体号，
     * 作用域没建起来就拿不到。**正向断言要挑一个「坏了会疼」的端点。**
     */
    private static final String BIZ_SCOPED = "/biz/context";

    private int codeOf(String path, String token) throws Exception {
        String body = mvc().perform(get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("code").asInt(-1);
    }

    /** 认证层拒绝：10401 未登录 / 10402 会话已失效。**403 不算** —— 那是判权层。 */
    private void assertRejectedByAuth(String path, String token, String what) throws Exception {
        int code = codeOf(path, token);
        assertThat(code)
                .as("%s 打 %s 必须在**认证层**被拒（10401/10402）。"
                        + "拿到 403 说明它被认成了某个身份、只是恰好没权限 —— "
                        + "那是真正的越权口子；拿到 0 说明它直接通了。实际 code=%d",
                        what, path, code)
                .isIn(10401, 10402);
    }

    @Test
    @DisplayName("★★★ C 端令牌打不开 B 端与运营端")
    void consumerTokenCannotReachOtherRealms() throws Exception {
        String ctk = consumerToken();
        assertRejectedByAuth(BIZ, ctk, "ctk_");
        assertRejectedByAuth(OPS, ctk, "ctk_");
    }

    @Test
    @DisplayName("★★★ B 端令牌打不开 C 端与运营端")
    void merchantTokenCannotReachOtherRealms() throws Exception {
        /*
         * btk_ 打 /mp 这一格是 2026-08-28 真实踩过的：A7 把一批测试里的令牌换成
         * btk_ 之后，那些还在调 /mp/user/profile 取 user_no 的地方全部 401。
         * 当时是「测试挂了」，但它证明的正是这条隔离在起作用。
         */
        String btk = merchantToken();
        assertRejectedByAuth(MP, btk, "btk_");
        assertRejectedByAuth(OPS, btk, "btk_");
    }

    @Test
    @DisplayName("★★★ 运营端令牌打不开 C 端与 B 端 —— 权力最大的那个尤其不能串门")
    void operatorTokenCannotReachOtherRealms() throws Exception {
        /*
         * 这两格最要紧：运营令牌背后是平台权限。如果它能落到 /biz/**，
         * BizContextFilter 会拿 staffNo 去解析经营作用域 —— 解析不出来是运气，
         * 解析出来就是「平台账号以某个商家的身份在操作」，而审计里看不出异常。
         */
        String otk = operatorToken();
        assertRejectedByAuth(MP, otk, "otk_");
        assertRejectedByAuth(BIZ, otk, "otk_");
    }

    @Test
    @DisplayName("★ 对照：三个令牌都能过自己那一端的**认证** —— 否则上面三条只是「全都拒」")
    void eachTokenPassesAuthOnItsOwnRealm() throws Exception {
        /*
         * **没有这条，上面三条就不可证伪**：一个把所有请求一律 401 的实现能让它们全绿。
         * 对照量本身也要验非零 —— 2026-08-28 栽过一次，当时补的对照量恒为 0 而我没查。
         *
         * C 端与运营端断言 code=0；B 端断言「**不是认证失败**」而不是 code=0：
         * 这个 btk_ 背后的人没有店，所以 /biz/context 会因为空作用域回 10403 ——
         * 而 10403 恰恰证明了它**通过了认证**，正是对照要证明的事。
         */
        assertThat(codeOf(MP, consumerToken())).as("ctk_ 打 /mp 应当通").isZero();
        assertThat(codeOf(OPS, operatorToken())).as("otk_ 打 /ops 应当通").isZero();
        assertThat(codeOf(BIZ_SCOPED, merchantToken()))
                .as("btk_ 打 /biz 必须过认证（可以因为没有店而 403，但不能是 401/402）")
                .isNotIn(10401, 10402);
    }
}
