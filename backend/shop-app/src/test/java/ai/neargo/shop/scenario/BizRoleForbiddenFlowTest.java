package ai.neargo.shop.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * B 端角色判权的**端到端**验证。
 *
 * <p>为什么必须有这个文件：{@code BizEndpointPermTest} 只证明「注解写上了」，
 * 而既有的 400 多条测试<b>全是老板账号跑的</b> —— 老板通配 {@code *}，
 * 所以注解就算完全不生效，那些测试也照样全绿。
 *
 * <p><b>「加了权限判断之后测试全过」本身就是可疑信号</b>：
 * 它可能意味着防住了，也可能意味着注解是装饰。只有用一个真的受限的账号打一次，
 * 才分得开这两种情况。
 */
@SpringBootTest
@ActiveProfiles("test")
class BizRoleForbiddenFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;


    /** 授权留痕还没有读端点（B-11.10.3 分两步走），先直接查表 */
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStaffLogMapper staffLogMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 店员打不开结算页 —— 这是本次要修的那个越权口子")
    void clerkCannotSeeMoney() throws Exception {
        Staff s = staffWithRoles("12600250001", "结算越权店", "12600250002", "CLERK");

        // 矩阵 §2.2 写着店员「无财务、无结算账户可见性」，此前这条约束根本不存在
        forbidden(s, get("/biz/settle/bills"));
        forbidden(s, get("/biz/settle/rate-card"));
        forbidden(s, get("/biz/merchant/payment"));
        forbidden(s, get("/biz/points/account"));
    }

    @Test
    @DisplayName("★★ 注解真的在生效 —— 店员能做的事仍然能做")
    void clerkCanStillDoHisJob() throws Exception {
        Staff s = staffWithRoles("12600250010", "店员本职店", "12600250011", "CLERK");

        // 如果 @PreAuthorize 在 /biz 链路上根本不生效，上一条会失败；
        // 如果判断写反了（把店员该做的也挡了），这一条会失败。两条一起才说明它是对的
        allowed(s, get("/biz/pickup/orders"));
        allowed(s, get("/biz/order"));
        allowed(s, get("/biz/goods"));
    }

    @Test
    @DisplayName("★★ 店员改不了价，但改得了库存 —— 这条缝是权限边界")
    void clerkChangesStockButNotPrice() throws Exception {
        Staff s = staffWithRoles("12600250020", "改价越权店", "12600250021", "CLERK");

        forbidden(s, post("/biz/goods/save")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"偷偷改价\",\"type\":\"NORMAL\",\"skus\":[]}"));

        // 改库存是店员的高频日常，不该被挡 —— 它不出钱
        allowed(s, post("/biz/goods/NOT-EXIST/stock")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuNo\":\"X\",\"stock\":1}"));
    }

    @Test
    @DisplayName("★★ 店员管不了门店结构与员工 —— 那是提权路径")
    void clerkCannotTouchStructure() throws Exception {
        Staff s = staffWithRoles("12600250030", "结构越权店", "12600250031", "CLERK");

        forbidden(s, post("/biz/store/create")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"我自己开的店\"}"));
        forbidden(s, get("/biz/staff"));
        forbidden(s, post("/biz/staff/X/store")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeNo\":\"X\",\"role\":\"MANAGER\"}"));
    }

    @Test
    @DisplayName("★★★ 多角色取并集 —— 店员+配送员两样都能干，但仍碰不到钱")
    void multiRoleUnionInRealRequest() throws Exception {
        Staff s = staffWithRoles("12600250040", "一人多岗店", "12600250041", "CLERK", "COURIER");

        allowed(s, get("/biz/pickup/orders"));   // 店员带来的
        allowed(s, get("/biz/order"));           // 两个角色都有
        // 并集不该凭空长出谁都没有的权限
        forbidden(s, get("/biz/settle/bills"));
        forbidden(s, post("/biz/goods/save")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\",\"type\":\"NORMAL\",\"skus\":[]}"));
    }

    @Test
    @DisplayName("★★ 理货员不能核销 —— 核销要面对顾客，他只对货")
    void pickerCannotVerify() throws Exception {
        Staff s = staffWithRoles("12600250050", "理货员店", "12600250051", "PICKER");

        allowed(s, get("/biz/pickup/picking"));
        forbidden(s, post("/biz/pickup/verify")
                .contentType(MediaType.APPLICATION_JSON).content("{\"verifyCode\":\"123456\"}"));
        forbidden(s, get("/biz/order"));   // 分拣单够他用，订单含金额
    }

    @Test
    @DisplayName("★★ 在这家店没有任何授权 = 零权限，不是「默认店员」")
    void noGrantInThisStoreMeansNothing() throws Exception {
        // 建了员工但**不给任何门店角色**
        Staff s = staffWithRoles("12600250060", "无授权店", "12600250061");

        forbidden(s, get("/biz/order"));
        forbidden(s, get("/biz/pickup/orders"));
        forbidden(s, get("/biz/settle/bills"));
    }

    @Test
    @DisplayName("★★★ 员工刷新页面后档案还在 —— 他没有 C 端账号，那不是「登录失效」")
    void staffKeepsProfileAfterRefresh() throws Exception {
        Staff s = staffWithRoles("12600250080", "刷新掉线店", "12600250081", "CLERK");

        /*
         * 浏览器实测出来的：登录接口自己组装档案，所以登录那一刻一切正常，
         * 但 GET /biz/merchant/profile 无条件查 usr_account，查不到就抛 10401。
         * 员工走手机号登录，**根本没有 C 端账号** —— 于是刷新一次，
         * b-app 拿不到 profile，整个工作台退化成「还没有开店 · 去入驻」。
         *
         * 这个缺陷四层测试一个都抓不到：后端测试没人用员工 token 打 profile，
         * b-app 的 mock 永远返回一个像样的档案。
         */
        String body = mvc().perform(get("/biz/merchant/profile")
                        .header("Authorization", "Bearer " + s.token()))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        // 拿到的必须是他所在主体的档案，不是一张空表单
        assertThat(json.readTree(body).get("data").get("merchantNo").asString()).isNotBlank();
        assertThat(json.readTree(body).get("data").get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("★★★ 店员看得到门店列表，且只看得到授权给他的那几家")
    void staffSeesOnlyHisStores() throws Exception {
        String owner = merchant("12600250090", "两家店");
        String a = firstStore(owner);
        String b = json.readTree(mvc().perform(post("/biz/store/create")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"分店\",\"address\":\"x\"}"))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("storeNo").asString();

        String staffNo = json.readTree(mvc().perform(post("/biz/staff")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginPhone\":\"12600250091\"}"))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("mchAccountNo").asString();
        mvc().perform(post("/biz/staff/" + staffNo + "/store")
                .header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeNo\":\"" + a + "\",\"role\":\"CLERK\"}"));

        // 门店切换器靠它。要 biz:store 的话店员一家都切不了 ——
        // 而「A 店店长 + B 店店员」正是多门店授权的主要用途
        String body = mvc().perform(get("/biz/store/list")
                        .header("Authorization", "Bearer " + staffLogin("12600250091")))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains(a);
        // 放开权限而不裁剪的话，他会看到一家自己进不去的店
        assertThat(body).doesNotContain(b);
    }

    @Test
    @DisplayName("★★★ 配送员的订单列表不含金额与核销码 —— 判权放行了，视图仍要裁")
    void courierGetsNarrowedOrderView() throws Exception {
        String owner = merchant("12600250100", "配送裁剪店");
        /*
         * **必须先真的下一单**。空列表下「不含 amount」这条断言是恒真的 ——
         * 撤掉裁剪它照样绿，那就不是守卫是装饰。
         */
        placePaidOrder(owner, "12600250102");
        Staff courier = staffFor(owner, "12600250101", "COURIER");

        /*
         * 他有 biz:order:view，所以这个请求**是通的** —— 这正是难点：
         * 判权层面一切正常，越权与否要看**返回体里有什么**。
         * 需求 §4.4 给配送员标的是 🟡「受限」而不是 ✅，此前那半个需求没落地，
         * 而它不会以任何形式报错。
         */
        String body = mvc().perform(get("/biz/order")
                        .header("Authorization", "Bearer " + courier.token()))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).get("data").get("records").size())
                .as("这一单没进列表，下面那几条断言就都是恒真的").isPositive();
        assertThat(body)
                .as("配送员的订单视图里出现了金额或核销码：%s", body)
                .doesNotContain("amount").doesNotContain("verifyCode").doesNotContain("payOrderNo");
        // 他要的东西必须还在，否则就是裁过头了 —— 那同样让他干不了活
        assertThat(body).contains("orderNo").contains("itemQty");
    }

    @Test
    @DisplayName("★★★ 店员兼配送**不裁** —— 判的是「谁给了他 order:view」，不是「有没有配送员这个角色」")
    void clerkWhoAlsoDeliversKeepsFullView() throws Exception {
        String owner = merchant("12600250110", "一人两岗店");
        placePaidOrder(owner, "12600250112");
        Staff both = staffFor(owner, "12600250111", "CLERK", "COURIER");

        String body = mvc().perform(get("/biz/order")
                        .header("Authorization", "Bearer " + both.token()))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        // 按「有没有 COURIER」判的话这里会被裁掉 —— 而他站收银台要看金额
        assertThat(body).as("店员兼配送被误裁了：%s", body).contains("amount");
    }

    @Test
    @DisplayName("★★ 授权变更要留痕（B-11.10.3）—— 三个月后答得出「谁把他提成了店长」")
    void grantsAreLogged() throws Exception {
        String owner = merchant("12600250120", "留痕店");
        String store = firstStore(owner);
        String staffNo = json.readTree(mvc().perform(post("/biz/staff")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginPhone\":\"12600250121\"}"))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("mchAccountNo").asString();

        // 给一个角色，再撤掉 —— 撤销尤其要留痕：它是「权限没了」的唯一解释
        grant(owner, staffNo, store, "MANAGER", true);
        grant(owner, staffNo, store, "MANAGER", false);
        mvc().perform(post("/biz/staff/" + staffNo + "/status")
                .header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"));

        var logs = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                staffLogMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.merchant.entity.MchStaffLog>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStaffLog::getTargetAccountNo, staffNo)));

        assertThat(logs.stream().map(ai.neargo.shop.merchant.entity.MchStaffLog::getAction))
                .as("四个动作都要留痕，缺哪个都会让事后追查断在那一步")
                .containsExactlyInAnyOrder("STAFF_ADD", "ROLE_GRANT", "ROLE_REVOKE", "STAFF_DISABLE");

        var granted = logs.stream()
                .filter(l -> "ROLE_GRANT".equals(l.getAction())).findFirst().orElseThrow();
        assertThat(granted.getRole()).isEqualTo("MANAGER");
        assertThat(granted.getStoreNo()).isEqualTo(store);
        // 「谁做的」是这张表存在的理由 —— 记不下操作人的审计等于没有审计
        assertThat(granted.getActorAccountNo()).isNotBlank();
    }

    @Test
    @DisplayName("★★ 变更记录端点：操作人没有登录手机号时也要能打开")
    void logsSurviveStaffWithoutLoginPhone() throws Exception {
        /*
         * **老板的 mch_account 没有 login_phone** —— 他走消费者账号登录，
         * 那一列一直是 NULL。而日志里的操作人就是他。
         *
         * 这条是**真实链路**打出来的：单测与 mock 造的数据里每个人都有手机号，
         * 于是「用 Collectors.toMap 装脱敏号」一路绿灯，
         * 连上真库第一次点开变更记录就是 500（toMap 对 null 值抛 NPE）。
         */
        String owner = merchant("12600250140", "无号老板店");
        String store = firstStore(owner);
        String staffNo = json.readTree(mvc().perform(post("/biz/staff")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginPhone\":\"12600250141\"}"))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("mchAccountNo").asString();
        grant(owner, staffNo, store, "CLERK", true);

        String body = mvc().perform(get("/biz/staff/logs")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("data").size())
                .as("加人 + 授权两条都该在：%s", body).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("★ 撤销一个他本来就没有的角色不留痕 —— 否则日志里全是没发生过的事")
    void revokingSomethingHeNeverHadIsNotLogged() throws Exception {
        String owner = merchant("12600250130", "空撤销店");
        String store = firstStore(owner);
        String staffNo = json.readTree(mvc().perform(post("/biz/staff")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginPhone\":\"12600250131\"}"))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("mchAccountNo").asString();

        grant(owner, staffNo, store, "PICKER", false);

        var logs = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                staffLogMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.merchant.entity.MchStaffLog>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStaffLog::getTargetAccountNo, staffNo)));
        assertThat(logs.stream().map(ai.neargo.shop.merchant.entity.MchStaffLog::getAction))
                .containsExactly("STAFF_ADD");
    }

    private void grant(String owner, String staffNo, String store, String role, boolean granted)
            throws Exception {
        mvc().perform(post("/biz/staff/" + staffNo + "/store")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeNo\":\"" + store + "\",\"role\":\"" + role
                                + "\",\"granted\":" + granted + "}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("★ 老板不受任何限制 —— 对照组，证明拒绝不是因为链路本身坏了")
    void ownerPassesEverything() throws Exception {
        String owner = merchant("12600250070", "老板对照店");
        mvc().perform(get("/biz/settle/bills").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(get("/biz/staff").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------------------------------------------------------------- 装配

    private record Staff(String token) {
    }

    private void forbidden(Staff s, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        String body = mvc().perform(req.header("Authorization", "Bearer " + s.token()))
                .andReturn().getResponse().getContentAsString();
        int code = body.isBlank() ? 403 : json.readTree(body).get("code").asInt();
        // 70006 是**角色不够**的专用码；10403 是作用域拒绝（这家店没自提点之类），
        // 两者分开正是为了让这条断言能说清楚它在验什么
        assertThat(code).as("这个请求本该被角色挡住，实际返回 %s", body).isEqualTo(70006);
    }

    private void allowed(Staff s, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        String body = mvc().perform(req.header("Authorization", "Bearer " + s.token()))
                .andReturn().getResponse().getContentAsString();
        int code = json.readTree(body).get("code").asInt();
        assertThat(code)
                .as("这个请求不该被**角色**挡住（业务错误、作用域拒绝都允许）：%s", body)
                .isNotEqualTo(70006);
    }

    /** 建一个员工，授予若干门店角色，返回他自己的登录态 */
    private Staff staffWithRoles(String ownerPhone, String shopName, String staffPhone,
                                 String... roles) throws Exception {
        return staffFor(merchant(ownerPhone, shopName), staffPhone, roles);
    }

    /**
     * 在**已有的**主体下建员工并授权。
     *
     * <p>与 {@link #staffWithRoles} 分开，是因为有些用例要先用老板身份把数据铺好
     * （比如先下一单），再让员工进来看 —— 那时主体已经建好了。
     */
    private Staff staffFor(String owner, String staffPhone, String... roles) throws Exception {
        String store = firstStore(owner);

        String staffNo = json.readTree(mvc().perform(post("/biz/staff")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginPhone\":\"" + staffPhone + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("mchAccountNo").asString();

        for (String role : roles) {
            mvc().perform(post("/biz/staff/" + staffNo + "/store")
                            .header("Authorization", "Bearer " + owner)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeNo\":\"" + store + "\",\"role\":\"" + role + "\"}"))
                    .andExpect(jsonPath("$.code").value(0));
        }
        return new Staff(staffLogin(staffPhone));
    }

    /**
     * 用这个商家的商品下一单并付掉，让 {@code /biz/order} 真的有东西可返回。
     *
     * <p>为什么值得写这二十行：视图裁剪那两条断言在**空列表上是恒真的** ——
     * 撤掉裁剪照样绿。测试的替身太干净时，它验的就不再是代码而是自己。
     */
    private void placePaidOrder(String ownerToken, String buyerPhone) throws Exception {
        String goodsNo = json.readTree(mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"裁剪测试商品\",\"type\":\"NORMAL\",\"cover\":\"📦\","
                                + "\"images\":[],\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("goodsNo").asString();

        String ops = opsLogin();
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit").header("Authorization", "Bearer " + ops)
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));

        String buyer = login(buyerPhone);
        mvc().perform(post("/mp/user/community").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"communityNo\":\"C0001\",\"pickupNo\":\"PP0001\"}"));
        String skuNo = json.readTree(mvc().perform(get("/mp/goods/" + goodsNo))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("skus").get(0).get("skuNo").asString();
        String payOrderNo = json.readTree(mvc().perform(post("/mp/order")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"EXPRESS\",\"addressId\":null,\"items\":[{\"goodsNo\":\""
                                + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("payOrderNo").asString();
        // 走支付回调而不是 /pay —— 只调 /pay 的话单还停在 WAIT_PAY
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-"
                        + payOrderNo + "\",\"sign\":\"stub-secret\"}"));
    }

    private String firstStore(String ownerToken) throws Exception {
        return json.readTree(mvc().perform(get("/biz/store/list")
                        .header("Authorization", "Bearer " + ownerToken))
                .andReturn().getResponse().getContentAsString())
                .get("data").get(0).get("storeNo").asString();
    }

    /** 员工独立登录（App 路径）—— 与老板的 C 端账号登录是两条路 */
    private String staffLogin(String phone) throws Exception {
        mvc().perform(post("/biz/auth/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        String body = mvc().perform(post("/biz/auth/staff-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    private String merchant(String phone, String name) throws Exception {
        String user = login(phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + opsLogin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        return login(phone);
    }

    private String opsLogin() throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    private String login(String phone) throws Exception {
        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        String body = mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone
                                + "\",\"credential\":\"" + code + "\",\"agreed\":true}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
