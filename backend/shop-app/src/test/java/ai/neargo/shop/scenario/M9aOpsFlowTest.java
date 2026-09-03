package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M9a 平台端骨架 —— **用例先行**。
 *
 * <p>这一轮第一次真正使用 `/ops/**` 这条过滤器链，因此**双池隔离**是重点：
 * C 端 token 打 `/ops` 必须 401，运营 token 打 `/mp` 的属主接口也不该当成某个消费者。
 * 越权防线①（前缀 + 过滤器链）到这里才第一次被真实验证 —— 前八个模块只用了 C 池。
 */
@SpringBootTest
@ActiveProfiles("test")
class M9aOpsFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;


    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 双池隔离（防线①）

    @Test
    @DisplayName("★ C 端 token 打 /ops/** 一律 401（池前缀不符，不用查库就能判）")
    void consumerTokenCannotAccessOps() throws Exception {
        String consumerToken = login("12600126001");
        mvc().perform(get("/ops/staffs").header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isUnauthorized());
        mvc().perform(get("/ops/order").header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("★ 运营 token 打 C 端属主接口不会被当成某个消费者")
    void operatorTokenIsNotAConsumer() throws Exception {
        String ops = opsLogin("admin", "admin123");
        mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + ops))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未登录访问 /ops/** 401")
    void opsRequiresLogin() throws Exception {
        mvc().perform(get("/ops/staffs")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("运营登录 → 拿到自己的角色与权限码")
    void opsLoginReturnsPerms() throws Exception {
        String token = opsLogin("admin", "admin123");
        String body = mvc().perform(get("/ops/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode staff = json.readTree(body).get("data");
        assertThat(staff.get("username").asString()).isEqualTo("admin");
        assertThat(staff.get("perms")).isNotEmpty();
    }

    @Test
    @DisplayName("密码错误不区分「用户不存在」与「密码不对」（否则等于用户名探测器）")
    void wrongPasswordIsIndistinguishable() throws Exception {
        String a = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andReturn().getResponse().getContentAsString();
        String b = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"wrong\"}"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(a).get("code").asInt())
                .isEqualTo(json.readTree(b).get("code").asInt());
    }

    // ---------------------------------------------------------------- RBAC（防线②）

    @Test
    @DisplayName("★ 无权限的角色被 @PreAuthorize 拦下（客服看不了员工管理）")
    void rbacBlocksUnauthorizedAction() throws Exception {
        String support = opsLogin("support", "support123");

        // 客服能看订单（工单处理要用）
        mvc().perform(get("/ops/order").header("Authorization", "Bearer " + support))
                .andExpect(status().isOk());
        // 但不能碰员工与角色。@PreAuthorize 抛 AccessDeniedException，
        // 由 GlobalExceptionHandler 转成契约包（HTTP 200 + code 10403）
        mvc().perform(get("/ops/staffs").header("Authorization", "Bearer " + support))
                .andExpect(jsonPath("$.code").value(10403));
    }

    @Test
    @DisplayName("超管可以管理员工")
    void adminCanManageStaff() throws Exception {
        String admin = opsLogin("admin", "admin123");
        mvc().perform(get("/ops/staffs").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                // 分页包：前端读 records，断言也跟着读它 ——
                // 在 PageData 上断言 $.data.length() 等于数字段数，恒真
                .andExpect(jsonPath("$.data.records.length()")
                        .value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    @DisplayName("★ 结算与分账列表是分页包 —— 返回裸数组的话页面会显示「暂无数据」，而接口是 200")
    void settleListsReturnPageData() throws Exception {
        String admin = opsLogin("admin", "admin123");
        /*
         * 断言 records 这个**键存在**，不是断言它有几条：库里有没有结算单取决于
         * 有没有成交，而契约与数据量无关。
         *
         * 这一条防的是那类最难查的故障：ops-web 按 {records,total} 渲染，
         * 后端返回裸数组时它取不到 records —— 页面显示「暂无数据」，
         * 接口 200，控制台一条错误都没有，看起来就像「本周期确实没有单」。
         */
        for (String url : java.util.List.of("/ops/settlements", "/ops/split-records")) {
            mvc().perform(get(url).header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.records").exists())
                    .andExpect(jsonPath("$.data.total").exists());
        }
    }

    // ---------------------------------------------------------------- 入驻：R-3 的回归

    /**
     * <b>本次最值得测的一条。</b>
     *
     * <p>此前 activate 只建商家、不配可达范围，结果是：商家审核通过 → 登录 B 端 →
     * 上架商品 → <b>一个订单都不来</b>，而这个故障没有任何报错，商家和运营都查不出原因
     * （ADR-009：service_scope 默认 COMMUNITY，一个社区都没覆盖 = 对谁都不可见）。
     *
     * <p>所以这里不验「接口返回 200」，验的是<b>业务上真的能被看见</b>。
     */
    @Test
    @DisplayName("★ 审核通过后商家在 C 端真的可见（不只是接口 200）")
    void approvedMerchantIsVisibleToBuyers() throws Exception {
        String user = login("12600126031");
        // 专属社区号：CM001 被十几个测试类共用，全量跑时它里面的商家早就超过一页
        String applyNo = applyMerchant(user, "李婶菜摊", "CM-M9A-VISIBLE");
        String bd = opsLogin("bd", "bd123");

        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isOk());

        // ★ 关键断言：C 端按社区查商家，必须查得到 —— 配了覆盖范围才会出现在这里
        /*
         * 显式给 size：默认页大小下，别的用例往 CM001 里多开几家店就会把它挤出第一页 ——
         * 而那时这条断言报的是「商家不可见」，与真正的可见性缺陷长得一模一样。
         */
        mvc().perform(get("/mp/merchant").param("communityNo", "CM-M9A-VISIBLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[?(@.name=='李婶菜摊')]").exists());
    }

    @Test
    @DisplayName("同一个人不能同时有两份进行中的申请")
    void duplicateApplyRejected() throws Exception {
        String user = login("12600126032");
        applyMerchant(user, "重复提交测试店");

        // 表单页重复点击是常态。真正兜底的是库上的唯一键，这里验的是它确实生效
        mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"再来一份\",\"subject\":\"PERSONAL\","
                                + "\"contactName\":\"李四\",\"contactPhone\":\"13900000001\","
                                + "\"category\":\"日用\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("★ 商家没填服务范围时，运营在通过时补上 —— 此前没有任何地方能填")
    void opsFillsServiceScopeAtAudit() throws Exception {
        String user = login("12600126097");
        String bd = opsLogin("bd", "bd123");

        // 商家申请时不填服务范围（ADR-009 允许留空）
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"没填范围的店\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"生鲜\",\"desc\":\"社区生鲜店\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();

        /*
         * 运营在通过时补上范围。
         * 不补的话 activate 会拒（那正是我们要的），但此前**运营侧没有这个入口** ——
         * B 端不填、运营补不了，商家开完店等着一个永远不来的订单。
         */
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        // 专属社区号，理由同上：CM001 被十几个测试类共用
                        .content("{\"approved\":true,\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM-M9A-FILLED\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // ★ 补上了才会出现在 C 端按社区查商家的结果里
        mvc().perform(get("/mp/merchant").param("communityNo", "CM-M9A-FILLED"))
                .andExpect(jsonPath("$.data.records[?(@.name=='没填范围的店')]").exists());
    }

    @Test
    @DisplayName("申请检索能翻历史 —— 「当初是谁批的」不该只能去查审计日志")
    void applySearchCoversHistory() throws Exception {
        String user = login("12600126096");
        String bd = opsLogin("bd", "bd123");
        String applyNo = applyMerchant(user, "历史检索测试店");
        approve(bd, applyNo);

        // 默认只给待办两档：已通过的不该混在「要我做的事」里
        mvc().perform(get("/ops/merchant/apply/search").header("Authorization", "Bearer " + bd))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[?(@.name=='历史检索测试店')]").doesNotExist());

        // 显式要 APPROVED 才翻得到
        mvc().perform(get("/ops/merchant/apply/search").header("Authorization", "Bearer " + bd)
                        .param("status", "APPROVED").param("keyword", "历史检索"))
                .andExpect(jsonPath("$.data.records[?(@.name=='历史检索测试店')]").exists());
    }

    @Test
    @DisplayName("★ 第二张执照不会覆盖第一个主体 —— 幂等按申请单，不按人")
    void secondLicenseCreatesSecondEntity() throws Exception {
        String user = login("12600126099");
        String bd = opsLogin("bd", "bd123");

        String first = applyMerchant(user, "老王粮油（个体户）");
        approve(bd, first);
        String firstEntity = entityNoOf(user, first);

        // 第二张执照：上一份已终态，「一人一份进行中」的名额已释放
        String second = applyMerchant(user, "老王商贸（公司）");
        approve(bd, second);
        String secondEntity = entityNoOf(user, second);

        /*
         * 曾经的缺陷：幂等按 owner_user_no 判重，于是第二张执照通过时被当成重复点击，
         * 系统去改**第一个主体** —— 名称/行业/法律形态被覆盖，两家变一家，
         * 而商家只看到「审核通过了」，没有任何报错。
         */
        assertThat(secondEntity).isNotEqualTo(firstEntity);
        /*
         * 按主体号直查两家店，**不要断言社区列表里都在** ——
         * 那个列表是分页的，全量跑时前面积累的商家会把先建的那家挤出首页，
         * 于是这条用例只在单跑时绿。分页列表不适合用来断言"某条存在"。
         */
        mvc().perform(get("/mp/store/" + firstEntity))
                .andExpect(jsonPath("$.data.merchant.name").value("老王粮油（个体户）"));
        mvc().perform(get("/mp/store/" + secondEntity))
                .andExpect(jsonPath("$.data.merchant.name").value("老王商贸（公司）"));
    }

    @Test
    @DisplayName("重复点「通过」仍然幂等 —— 不会建出第二个主体")
    void repeatedApproveIsIdempotent() throws Exception {
        String user = login("12600126098");
        String bd = opsLogin("bd", "bd123");
        String applyNo = applyMerchant(user, "重复通过测试店");
        approve(bd, applyNo);
        String entityNo = entityNoOf(user, applyNo);

        // 运营手抖再点一次：状态机会挡住重复审批，主体号不会变
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + bd)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approved\":true}"));
        assertThat(entityNoOf(user, applyNo)).isEqualTo(entityNo);
    }

    private void approve(String opsToken, String applyNo) throws Exception {
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 从申请单读回它激活出的主体号 —— 这正是新的幂等判据。 */
    private String entityNoOf(String userToken, String applyNo) throws Exception {
        String body = mvc().perform(get("/mp/merchant/apply")
                        .header("Authorization", "Bearer " + userToken))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
    }

    @Test
    @DisplayName("我的申请进度可查 —— 此前提交完就断了")
    void myApplyIsQueryable() throws Exception {
        String user = login("12600126033");
        applyMerchant(user, "进度查询测试店");

        mvc().perform(get("/mp/merchant/apply").header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.category").value("生鲜"))
                .andExpect(jsonPath("$.data.contactName").value("张三"));
    }

    // ---------------------------------------------------------------- 审核链路

    @Test
    @DisplayName("★ 入驻审核：通过后商家才 ACTIVE，才能上架与收款")
    void merchantApprovalActivatesMerchant() throws Exception {
        String user = login("12600126010");
        String applyNo = applyMerchant(user, "王姐水果店");

        String bd = opsLogin("bd", "bd123");
        mvc().perform(get("/ops/merchant/apply").header("Authorization", "Bearer " + bd))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));

        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 审核通过 → 申请人获得商家身份（/biz 可用）
        // A7：这个令牌要打 /biz/**，必须是 btk_
        String refreshed = TestLogin.merchantOwner(mvc(), json, otpStore, "12600126010");
        mvc().perform(get("/biz/context").header("Authorization", "Bearer " + refreshed))
                .andExpect(jsonPath("$.data.merchantNo").isNotEmpty());
    }

    @Test
    @DisplayName("驳回必须写理由（不写理由的驳回等于让对方猜）")
    void rejectRequiresReason() throws Exception {
        String user = login("12600126011");
        String applyNo = applyMerchant(user, "李哥杂货");
        String bd = opsLogin("bd", "bd123");

        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":false}"))
                .andExpect(jsonPath("$.code").value(10400));

        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":false,\"reason\":\"营业执照模糊\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("★ 高危操作留痕：审核动作写进审计日志，能追到人")
    void auditLogRecordsWho() throws Exception {
        String user = login("12600126012");
        String applyNo = applyMerchant(user, "赵姐粮油");
        String bd = opsLogin("bd", "bd123");

        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"));

        String admin = opsLogin("admin", "admin123");
        String body = mvc().perform(get("/ops/audit-log").header("Authorization", "Bearer " + admin)
                        .param("target", applyNo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode logs = json.readTree(body).get("data").get("records");
        assertThat(logs).isNotEmpty();
        // 审核是能改变别人生意的操作 —— 出问题时必须能回答「谁批的」
        assertThat(logs.get(0).get("staffName").asString()).isNotBlank();
        assertThat(logs.get(0).get("action").asString()).isEqualTo("MERCHANT_AUDIT");
    }

    @Test
    @DisplayName("★ 待审队列只给待审的 —— 审完一件，队列要短一件")
    void goodsAuditQueueOnlyPending() throws Exception {
        String ops = opsLogin("goods", "goods123");

        // 先造一件真的待审商品：种子商品都是已过审的，不造的话队列恒为 0，
        // 这条用例会变成「0 == 0」——又是一条恒真的断言
        String merchantToken = merchantWithGoods("12600126095", "待审队列测试店", "待审辣椒酱");
        assertThat(merchantToken).isNotBlank();

        /*
         * 这条用例原先断言的是 `onSale ∈ {true,false}` —— 恒真，等于没测。
         * 而队列当时走的是**不带任何条件的公共目录查询**，返回全部商品：
         * 运营审完一件，队列长度纹丝不动，只会以为没保存成功，反复再审一遍。
         */
        long before = queueTotal(ops);
        assertThat(before).isGreaterThan(0);

        String body = mvc().perform(get("/ops/goods/audit-queue").header("Authorization", "Bearer " + ops))
                .andReturn().getResponse().getContentAsString();
        JsonNode first = json.readTree(body).get("data").get("records").get(0);
        // 队列里的每一件都必须是待审态
        for (JsonNode g : json.readTree(body).get("data").get("records")) {
            // 对外口径是 PENDING（词典 §11）；库里那列仍叫 AUDITING，两者不必一致
            assertThat(g.get("status").asString()).isEqualTo("PENDING");
        }

        mvc().perform(post("/ops/goods/" + first.get("goodsNo").asString() + "/audit")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                /*
                 * **过审后是 ON_SALE**（V247 改的，此前断言 OFF_SALE）。
                 *
                 * 原来的理由是「审核只解锁可以卖，真正上架是商家自己按的按钮 ——
                 * 平台替他上架等于替他决定什么时候开卖」。那句话针对的是
                 * **无条件替他上架**，那样确实越权。
                 *
                 * 现在兑现的是他自己表达过的意愿：这件货是他点「提交审核」送上来的
                 * （见 merchantWithGoods），那一下就是「我要卖它」。
                 * 平台同意之后再要他点第三次，不增加任何信息 —— 而少点那一次的代价，
                 * 是他以为在卖、其实一件也卖不出去。
                 *
                 * 没表达过意愿的仍旧停在 OFF_SALE：直接被运营审的、
                 * 或商家自己下架过的，pending_on_sale 都是 0。
                 */
                .andExpect(jsonPath("$.data.status").value("ON_SALE"));

        assertThat(queueTotal(ops)).isEqualTo(before - 1);
    }

    /** 造一个商家并录一件商品 —— 新录的商品落在待审（对外 PENDING），正是队列要的。 */
    private String merchantWithGoods(String phone, String shopName, String goodsTitle) throws Exception {
        String user = login(phone);
        String applyNo = applyMerchant(user, shopName);
        approve(opsLogin("bd", "bd123"), applyNo);
        // A7：这个令牌要打 /biz/**，必须是 btk_
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, phone);
        String saved = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"" + goodsTitle + "\",\"subtitle\":\"测试\","
                                + "\"type\":\"NORMAL\",\"cover\":\"🥫\",\"images\":[],"
                                + "\"specGroups\":[],\"skus\":[{\"optionValues\":[],"
                                + "\"price\":1000,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        /*
         * **提交审核是显式的一步**（批 D）：新建落草稿，不进队列。
         * 少了这一句，这条用例会以「队列恒为 0」的形式失败 —— 而那正是 D2 想要的行为。
         */
        String goodsNo = json.readTree(saved).get("data").get("goodsNo").asString();
        mvc().perform(post("/biz/goods/" + goodsNo + "/submit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
        return token;
    }

    private long queueTotal(String opsToken) throws Exception {
        String body = mvc().perform(get("/ops/goods/audit-queue")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("total").asLong();
    }

    @Test
    @DisplayName("平台可检索全量订单（客服处理工单要用）")
    void opsCanSearchAllOrders() throws Exception {
        String buyer = login("12600126020");
        buyAndPay(buyer, "m9-order");

        String support = opsLogin("support", "support123");
        mvc().perform(get("/ops/order").header("Authorization", "Bearer " + support)
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    // ---------------------------------------------------------------- B 端：闭环的第三段

    /**
     * <b>闭环的第三段。</b>前两段（C 端提交、平台审核）此前已通，但商家自己在 B 端
     * 看到的是 404 —— 既不知道过没过，也拿不到驳回原因，于是「驳回 → 补料 → 重提」
     * 这条路根本走不通，闭环断在这里。
     *
     * <p>这条测试走完商家视角的**全过程**，而不是分成五条各验一个状态：
     * 入驻是一条有先后的路，分开测就测不到「上一步的结果是下一步的入参」。
     */
    @Test
    @DisplayName("★ B 端入驻闭环：未申请 → 提交 → 受理 → 驳回 → 补料重提 → 通过")
    void bizOnboardingFullLoop() throws Exception {
        // A7：这个令牌要打 /biz/**，必须是 btk_
        String user = TestLogin.merchantOwner(mvc(), json, otpStore, "12600126040");
        String bd = opsLogin("bd", "bd123");

        // ① 没申请过 —— 是 NONE，不是 404。让前端 catch 一个正常状态迟早出错
        mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NONE"))
                .andExpect(jsonPath("$.data.merchantNo").value(""));

        // ② 从 B 端提交（与 C 端同一个入口后端，被驳回后重提就发生在这里）
        mvc().perform(post("/biz/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("周叔五金")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLYING"));

        String applyNo = pendingApplyNo(bd, "周叔五金");

        // ③ 客服接手 → 商家那边立刻看到「有人在看」。这一步的全部价值就是这个反馈
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/accept")
                        .header("Authorization", "Bearer " + bd))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + user))
                .andExpect(jsonPath("$.data.status").value("REVIEWING"));

        // ④ 驳回 —— ★ 驳回原因必须到得了商家手里，否则他不知道要补什么
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":false,\"reason\":\"营业执照模糊\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + user))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReason").value("营业执照模糊"));

        // ⑤ ★ 回填：驳回往往只缺一张执照，让人从头重填是把「补交」变成「重来」
        mvc().perform(get("/biz/merchant/apply").header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("周叔五金"))
                .andExpect(jsonPath("$.data.contactName").value("张三"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        // ⑥ 补料重提。被驳回的那份已进终态，不再占「一人一份」的名额
        mvc().perform(post("/biz/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("周叔五金")))
                .andExpect(jsonPath("$.data.status").value("APPLYING"));

        // ⑦ 通过 → 商家拿到 merchantNo，B 端才谈得上上架与收款
        mvc().perform(post("/ops/merchant/apply/" + pendingApplyNo(bd, "周叔五金") + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));

        // A7：这个令牌要打 /biz/**，必须是 btk_
        String refreshed = TestLogin.merchantOwner(mvc(), json, otpStore, "12600126040");
        mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + refreshed))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.merchantNo").isNotEmpty())
                .andExpect(jsonPath("$.data.name").value("周叔五金"))
                .andExpect(jsonPath("$.data.rejectReason").doesNotExist());
    }

    @Test
    @DisplayName("入驻接口对「还不是商家的人」必须开放 —— 否则被驳回的人永远看不到原因")
    void applicantWithoutMerchantIsNotForbidden() throws Exception {
        // A7：这个令牌要打 /biz/**，必须是 btk_
        String user = TestLogin.merchantOwner(mvc(), json, otpStore, "12600126041");
        // 同一个 token 打真正的经营接口应当 403，打入驻接口应当 200 —— 这正是差别所在
        mvc().perform(get("/biz/context").header("Authorization", "Bearer " + user))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
        mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NONE"));
    }

    @Test
    @DisplayName("没申请过时草稿返回空，不是 404（「没申请过」是正常状态）")
    void draftIsEmptyNotFound() throws Exception {
        // A7：这个令牌要打 /biz/**，必须是 btk_
        String user = TestLogin.merchantOwner(mvc(), json, otpStore, "12600126042");
        mvc().perform(get("/biz/merchant/apply").header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ---------------------------------------------------------------- B 端：登录与开店

    @Test
    @DisplayName("★ B 端登录一次拿到 token + 档案（分两次请求会先闪一屏错的）")
    void bizLoginReturnsProfileInOneShot() throws Exception {
        login("12600126043");   // 先建号
        mvc().perform(post("/mp/user/otp/send")
                .header("Authorization", "Bearer " + ai.neargo.shop.support.TestLogin.otpSession(mvc())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"12600126043\"}"));
        String code = otpStore.peek("12600126043").orElseThrow();
        String body = mvc().perform(post("/biz/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"12600126043\","
                                + "\"credential\":\"" + code + "\",\"agreed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                // 还不是商家 → NONE，B 端据此直接进入驻流程，不用再取一次
                .andExpect(jsonPath("$.data.merchant.status").value("NONE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("data").get("merchant").get("phone").asString()).isNotBlank();
    }

    @Test
    @DisplayName("★ 店铺设置不能把「仅本社区」的覆盖清空（清空 = 对谁都不可见，且无报错）")
    void storeCannotClearCommunityCoverage() throws Exception {
        String token = approvedMerchantToken("12600126044", "孙记粮油");

        // 入驻时配了 CM001，读回来必须在 —— 否则下面那条断言等于没测
        mvc().perform(get("/biz/store").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serviceScope").value("COMMUNITY"))
                .andExpect(jsonPath("$.data.serviceCommunityNos[0]").value("CM001"));

        // ★ 清空社区必须被拒。ADR-009 的规则在入驻审核那边也有一份，两处必须一致 ——
        // 否则商家可以入驻时配好，转头在这里清空，然后货就人间蒸发了
        mvc().perform(post("/biz/store").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"announcement\":\"今天到了新米\",\"openHours\":\"06:30-21:00\","
                                + "\"address\":\"阳光里南门\",\"featured\":[],"
                                + "\"serviceScope\":\"COMMUNITY\",\"serviceCommunityNos\":[]}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));

        // 被拒之后覆盖范围必须原样还在（不能出现"拒了但已经删了"）
        mvc().perform(get("/biz/store").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.serviceCommunityNos[0]").value("CM001"));
    }

    @Test
    @DisplayName("店铺资料存得下也读得回，地址同步到 C 端商家详情")
    void storeProfileRoundTrips() throws Exception {
        String token = approvedMerchantToken("12600126045", "钱婶早点");

        mvc().perform(post("/biz/store").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"announcement\":\"今天有土鸡蛋\",\"openHours\":\"05:00-10:00\","
                                + "\"address\":\"阳光里北门早市\",\"featured\":[\"G001\",\"G002\"],"
                                + "\"serviceScope\":\"COMMUNITY\",\"serviceCommunityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.announcement").value("今天有土鸡蛋"))
                // 主推是有序的：顺序是门面的编排，乱序等于这个字段白存
                .andExpect(jsonPath("$.data.featured[0]").value("G001"))
                .andExpect(jsonPath("$.data.featured[1]").value("G002"));

        // 从没保存过的店读到的是空表单而不是 404
        String other = approvedMerchantToken("12600126046", "吴叔修车");
        mvc().perform(get("/biz/store").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.announcement").value(""))
                .andExpect(jsonPath("$.data.featured.length()").value(0));
    }

    @Test
    @DisplayName("社区列表不依赖定位（商家选的是经营半径，不是他此刻站在哪儿）")
    void bizCommunitiesNeedNoLocation() throws Exception {
        // A7：这个令牌要打 /biz/**，必须是 btk_
        String user = TestLogin.merchantOwner(mvc(), json, otpStore, "12600126047");
        mvc().perform(get("/biz/communities").header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    // ---------------------------------------------------------------- 行业（M2 / 通道准入）

    /**
     * <b>行业决定可选主体。</b>微信小微的准入白名单按行业给，线上业态不支持。
     *
     * <p>这条规则必须在<b>提交那一刻</b>生效。放到进件时才拦的后果是：
     * 入驻早已通过、商家已经在上架商品，此时告诉他「你这行不能用这个主体」——
     * 要么改主体重走一遍资质，要么这家店根本收不了款，而两条都不是他的错。
     */
    @Test
    @DisplayName("★ 线上业态不能选小微，提交即被拒（不是等到进件才撞墙）")
    void onlineIndustryCannotUseMicroSubject() throws Exception {
        String user = login("12600126050");
        mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBodyWith("直播小店", "PERSONAL", "ONLINE")))
                // 专用错误码，端上据此把「换个主体」这条出路说出来
                .andExpect(jsonPath("$.code").value(70001));

        // 同一个行业换成个体户就能过 —— 个体户走的是另一套准入，不受白名单限制
        mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBodyWith("直播小店", "INDIVIDUAL_BIZ", "ONLINE")))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("白名单内的行业可以选小微")
    void whitelistedIndustryAllowsMicro() throws Exception {
        String user = login("12600126051");
        mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBodyWith("楼下小卖部", "PERSONAL", "RETAIL")))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("★ 行业与店铺简介要落到商家主体 —— 只存在申请单上等于填了就丢")
    void industryAndDescriptionReachMerchant() throws Exception {
        String user = login("12600126052");
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBodyWith("陈姐家常菜", "INDIVIDUAL_BIZ", "CATERING")))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();

        String bd = opsLogin("bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));

        // A7：这个令牌要打 /biz/**，必须是 btk_
        String refreshed = TestLogin.merchantOwner(mvc(), json, otpStore, "12600126052");
        mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + refreshed))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                // ★ 这两项此前通过审核就消失了：行业是进件主体的判据，简介是 C 端门店页的内容
                .andExpect(jsonPath("$.data.industry").value("CATERING"))
                .andExpect(jsonPath("$.data.description").value("社区家常菜"));
    }

    @Test
    @DisplayName("行业主数据：BD 改不了准入（它是通道规则，不是招商能判断的）")
    void industryAdminIsNotForBd() throws Exception {
        String bd = opsLogin("bd", "bd123");
        // @PreAuthorize 抛 AccessDeniedException，由 GlobalExceptionHandler
        // 转成契约包（HTTP 200 + code 10403）—— 断 HTTP 403 会漏掉真正的拒绝
        mvc().perform(get("/ops/industries").header("Authorization", "Bearer " + bd))
                .andExpect(jsonPath("$.code").value(10403));

        String admin = opsLogin("admin", "admin123");
        mvc().perform(get("/ops/industries").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.industry=='CATERING')]").exists())
                // 支付宝的行业限制尚未确认，seed 全部保守置 0
                .andExpect(jsonPath("$.data[?(@.industry=='CATERING')].alipayMicroAllowed")
                        .value(org.hamcrest.Matchers.contains(false)));
    }

    @Test
    @DisplayName("★ 主数据一次给全：行业 + 主体 + 渠道，且游客可读（入驻表单在登录前就要显示）")
    void masterDataIsOneShotAndPublic() throws Exception {
        String body = mvc().perform(get("/common/master-data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                // 端上据此禁用「小微」选项，而不是让人填完再被后端拒
                .andExpect(jsonPath("$.data.industries[?(@.industry=='RETAIL')].microAllowed")
                        .value(org.hamcrest.Matchers.contains(true)))
                /*
                 * 停用的行业**不出现在这里**。此前这条断言查的是 ONLINE 的 microAllowed，
                 * 而一期收敛（V22）把 ONLINE 停用了 —— 它不再出现在端上的可选集里。
                 * 改成断言「停用的确实拿不到」，测的是同一层（snapshot 只给启用的），
                 * 而且这才是收敛能生效的前提。全量含停用的视图在 /ops/industries，
                 * 由 Phase1MasterDataTest 守。
                 */
                .andExpect(jsonPath("$.data.industries[?(@.industry=='ONLINE')]")
                        .value(org.hamcrest.Matchers.empty()))
                // 小微免执照、受行业限制；个体户要执照、不受限 —— 这些判断此前三端各写一遍
                .andExpect(jsonPath("$.data.subjects[?(@.subjectType=='NATURAL_PERSON')].needLicense")
                        .value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.data.subjects[?(@.subjectType=='NATURAL_PERSON')].industryGated")
                        .value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.data.subjects[?(@.subjectType=='INDIVIDUAL')].needLicense")
                        .value(org.hamcrest.Matchers.contains(true)))
                .andReturn().getResponse().getContentAsString();
        // 主数据不该带出任何密钥或平台资金账户
        assertThat(body).doesNotContain("poolAccountRef").doesNotContain("secret");
    }

    @Test
    @DisplayName("★ 旧主体取值能翻译成通道口径（映射只此一份，此前散在三处）")
    void legacySubjectMapsToCanonical() throws Exception {
        // 存量数据里存的是 PERSONAL，而行业白名单这条规则是按「小微」定义的。
        // 翻译错一次，商家就是进件被拒 —— 所以这条链路要有断言
        String user = login("12600126053");
        mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBodyWith("线上小店", "PERSONAL", "ONLINE")))
                .andExpect(jsonPath("$.code").value(70001));
    }

    /**
     * <b>地址与营业时间只有一个答案</b>（ADR-011 P0 / V42）。
     *
     * <p>此前这两个字段 {@code mch_entity} 与 {@code mch_store} 都有，
     * 靠一段双写代码保持一致 —— 而双写永远有漏的一天（少一个入口、少一条分支），
     * 漏了之后的症状是「店铺设置里改了地址、商家详情页还是老地址」，且不报错。
     *
     * <p>所以这条测的不是"能不能存"，而是<b>改完之后 C 端立刻看到新的</b>。
     */
    @Test
    @DisplayName("★ 店铺设置改地址后，C 端商家详情立刻是新地址（不再有第二份数据）")
    void storeAddressHasSingleSource() throws Exception {
        String token = approvedMerchantToken("12600126060", "地址一致性店");
        String merchantNo = json.readTree(mvc().perform(get("/biz/merchant/profile")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("merchantNo").asString();

        mvc().perform(post("/biz/store").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"announcement\":\"\",\"openHours\":\"06:00-22:00\","
                                + "\"address\":\"阳光里西门 3 号\",\"featured\":[],"
                                + "\"serviceScope\":\"COMMUNITY\",\"serviceCommunityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0));

        // ★ C 端读的必须是同一份 —— 以前这里读的是主体表那一列
        mvc().perform(get("/mp/merchant/" + merchantNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.address").value("阳光里西门 3 号"))
                .andExpect(jsonPath("$.data.openHours").value("06:00-22:00"));
    }

    // ---------------------------------------------------------------- 员工独立账号（M1.2）

    /**
     * <b>员工不必是 C 端用户。</b>
     *
     * <p>早先的设计强制绑 C 端账号，理由是「店员多半已是 C 端用户」——
     * 那只在小程序里成立。在 App 上，<b>要求店员先注册成消费者才能上班，
     * 是把雇佣关系硬塞进一个消费关系里</b>。
     */
    @Test
    @DisplayName("★ 员工用自己的手机号登录，不需要 C 端账号")
    void staffLogsInWithoutConsumerAccount() throws Exception {
        String merchantNo = approvedMerchantNo("12600126060", "员工测试店");

        // 一个**从没注册过 C 端账号**的手机号，直接建成员工
        String staffPhone = "13100999001";
        var staff = new ai.neargo.shop.merchant.entity.MchAccount();
        staff.setMchAccountNo("SF-TEST-1");
        staff.setEntityNo(merchantNo);
        staff.setLoginPhone(staffPhone);
        staff.setUserNo(null);          // ← 关键：没有 C 端账号
        staff.setIsOwner(false);
        staff.setIsPrimary(true);
        staff.setStatus(ai.neargo.shop.merchant.entity.MchAccount.ACTIVE);
        merchantStaffMapper.insert(staff);

        String token = TestLogin.merchantStaff(mvc(), json, otpStore, staffPhone);

        // ★ 拿到的作用域必须是这家店 —— 只认 user_no 的解析器会让这里作用域为空、全部 403
        mvc().perform(get("/biz/context").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.merchantNo").value(merchantNo));
    }

    @Test
    @DisplayName("验证码对但不是员工 → 403，而不是「账号不存在」")
    void nonStaffPhoneCannotEnumerate() throws Exception {
        String phone = "13100999002";
        mvc().perform(post("/mp/user/otp/send")
                .header("Authorization", "Bearer " + ai.neargo.shop.support.TestLogin.otpSession(mvc())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();

        // 报「账号不存在」会把「某个手机号是不是这家店的员工」变成可枚举信息，
        // 而验证码本来就是任何人都能给自己的手机号要的
        mvc().perform(post("/biz/auth/staff-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"))
                .andExpect(jsonPath("$.code").value(10403));
    }

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper merchantStaffMapper;

    /** 走完入驻并通过，返回 merchantNo。 */
    private String approvedMerchantNo(String phone, String name) throws Exception {
        String user = login(phone);
        String applyNo = applyMerchant(user, name);
        String bd = opsLogin("bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + bd)
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        // A7：这个令牌要打 /biz/**，必须是 btk_
        String refreshed = TestLogin.merchantOwner(mvc(), json, otpStore, phone);
        String body = mvc().perform(get("/biz/merchant/profile")
                        .header("Authorization", "Bearer " + refreshed))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
    }

    // ---------------------------------------------------------------- helpers

    private String applyBodyWith(String name, String subject, String industry) {
        return "{\"name\":\"" + name + "\",\"subject\":\"" + subject + "\","
                + "\"contactName\":\"陈姐\",\"contactPhone\":\"13900000009\","
                + "\"category\":\"餐饮\",\"desc\":\"社区家常菜\","
                + "\"serviceScope\":\"COMMUNITY\",\"communityNos\":[\"CM001\"],"
                + "\"industry\":\"" + industry + "\"}";
    }


    /** 走完「申请 → 通过」，返回可用于 /biz/** 的 token。 */
    private String approvedMerchantToken(String phone, String name) throws Exception {
        String user = login(phone);
        String applyNo = applyMerchant(user, name);
        String bd = opsLogin("bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        // 重新登录：商家身份是登录时解析进 BizContext 的，旧 token 上还没有
        // A7：这个令牌是拿去打 /biz/** 的，必须是 btk_
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }


    private String applyBody(String name) {
        return "{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                + "\"category\":\"五金\",\"desc\":\"社区五金店\","
                + "\"serviceScope\":\"COMMUNITY\",\"communityNos\":[\"CM001\"],"
                + "\"licenses\":[\"https://cdn/l.jpg\"]}";
    }

    /** 从审核队列里取这家店进行中的那份申请单号。 */
    private String pendingApplyNo(String bdToken, String name) throws Exception {
        String body = mvc().perform(get("/ops/merchant/apply").header("Authorization", "Bearer " + bdToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode a : json.readTree(body).get("data")) {
            if (name.equals(a.get("name").asString())) {
                return a.get("applyNo").asString();
            }
        }
        throw new AssertionError("审核队列里找不到「" + name + "」的进行中申请");
    }


    private String applyMerchant(String userToken, String name) throws Exception {
        return applyMerchant(userToken, name, "CM001");
    }

    /**
     * 带社区号的重载。
     *
     * <p><b>为什么需要它</b>：两条「审核通过后 C 端真的可见」的用例原来查 CM001，
     * 而**十几个测试类都往 CM001 开店**。它们各自跑绿，全量跑时这个社区里的商家
     * 早就超过一页 —— 断言报的是「商家不可见」，与真正的可见性缺陷长得一模一样。
     *
     * <p>原来的防御是把 size 提到 100（注释里写着理由），当时够用，现在不够了。
     * 提 size 是在跟别人的增长赛跑，换成**各用各的社区号**才是把这条依赖切断。
     * CM001 没有种子行、就是个自由字符串，所以换一个不花任何代价。
     */
    private String applyMerchant(String userToken, String name, String communityNo) throws Exception {
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        // 覆盖社区在申请时就带上：审核通过要用它配可达范围（ADR-009），
                        // 不带的话 activate 会拒 —— 那正是我们要的行为
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"生鲜\",\"desc\":\"社区生鲜店\","
                                + "\"serviceScope\":\"COMMUNITY\",\"communityNos\":[\"" + communityNo + "\"],"
                                + "\"licenses\":[\"https://cdn/l.jpg\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("applyNo").asString();
    }

    private void buyAndPay(String token, String idemKey) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString();
        String payOrderNo = json.readTree(body).get("data").get("payOrderNo").asString();
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-" + idemKey
                        + "\",\"sign\":\"" + STUB_SECRET + "\"}"));
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
