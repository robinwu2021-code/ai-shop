package ai.neargo.shop.scenario;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 门店管理与员工授权（M6 的商家侧）。
 *
 * <p>这两块此前**表和实体全齐、写侧零接口**：除了「激活时建一家默认店」，
 * 没有任何代码能再建第二家店，也没有任何代码能给员工授权到店。
 *
 * <p>额度在 {@code application-test.yml} 里开到 3（生产默认 1 = 与单店时代行为一致）：
 * 用默认值的话「超额被拒」之外的分支一条都走不到。
 * <b>不要改用 @TestPropertySource</b> —— 那会造出第二个 Spring 上下文，
 * 而 H2 是同一个内存库，建表脚本跑第二遍会让整套测试成片挂在主键冲突上。
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreAndStaffFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 门店

    @Test
    @DisplayName("★ 激活后就有一家默认店，新建的不抢默认标")
    void defaultStoreExistsAndStaysUnique() throws Exception {
        String token = merchant("12600129001", "门店测试店A");

        mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].isDefault").value(true));

        create(token, "二店", "文三路 100 号");

        // 一个主体**恰好一家**默认店：新建的不该抢标，否则中间态会出现两家默认
        String body = list(token);
        var arr = json.readTree(body).get("data");
        assertThat(arr.size()).isEqualTo(2);
        long defaults = 0;
        for (var n : arr) {
            if (n.get("isDefault").asBoolean()) defaults++;
        }
        assertThat(defaults).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 超出额度直接拒 —— 建出来却打不开的店比拒绝更难解释")
    void quotaIsEnforced() throws Exception {
        String token = merchant("12600129002", "门店测试店B");
        create(token, "二店", "");
        create(token, "三店", "");

        mvc().perform(post("/biz/store/create").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"四店\"}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("★ 默认店不能停用 —— 停掉之后「这个主体的店在哪」就没有答案了")
    void defaultStoreCannotBeDisabled() throws Exception {
        String token = merchant("12600129003", "门店测试店C");
        String def = storeNoOf(token, 0);

        mvc().perform(post("/biz/store/" + def + "/status").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));

        // 转移默认标之后就能停了
        String second = create(token, "二店", "");
        mvc().perform(post("/biz/store/" + second + "/default")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.isDefault").value(true));
        mvc().perform(post("/biz/store/" + def + "/status").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(jsonPath("$.data.status").value("READONLY"));
    }

    @Test
    @DisplayName("★ 换收款号只能挑本主体已开通的 —— 换到收不了款的号上，下一单就失败")
    void payMerchantMustBeOwnAndActive() throws Exception {
        String token = merchant("12600129004", "门店测试店D");
        String def = storeNoOf(token, 0);

        // 还没进件成功：任何号都挂不上
        mvc().perform(post("/biz/store/" + def + "/payment").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payMerchantNo\":\"PM-别人家的号\"}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));

        // 进件成功后拿到本主体的号，就能挂上
        String pm = activatePayment(token);
        mvc().perform(post("/biz/store/" + def + "/payment").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payMerchantNo\":\"" + pm + "\"}"))
                .andExpect(jsonPath("$.data.payMerchantNo").value(pm))
                .andExpect(jsonPath("$.data.payReady").value(true));

        // 传空 = 回到主体默认号，这是合法操作不是清空错误
        mvc().perform(post("/biz/store/" + def + "/payment").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.data.payMerchantNo").doesNotExist())
                .andExpect(jsonPath("$.data.payReady").value(true));
    }

    @Test
    @DisplayName("别家的门店号一律 404 —— 403 等于确认这个号存在")
    void othersStoreIsNotFound() throws Exception {
        String a = merchant("12600129005", "门店测试店E");
        String b = merchant("12600129006", "门店测试店F");
        String bStore = storeNoOf(b, 0);

        mvc().perform(post("/biz/store/" + bStore + "/rename").header("Authorization", "Bearer " + a)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"改别人的店\"}"))
                .andExpect(jsonPath("$.code").value(10404));
    }

    // ---------------------------------------------------------------- 员工

    @Test
    @DisplayName("★ 加员工不发密码、不建 C 端账号 —— 他用自己的手机号验证码登录")
    void addStaffThenLogin() throws Exception {
        String token = merchant("12600129007", "员工测试店A");

        mvc().perform(post("/biz/staff").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginPhone\":\"13600001234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isOwner").value(false))
                // 手机号脱敏：完整号回显等于给店长一份可导出的通讯录
                .andExpect(jsonPath("$.data.loginPhone").value("136****1234"));

        // 这个店员从来没注册过 C 端账号，照样能登
        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13600001234\"}"));
        String code = otpStore.peek("13600001234").orElseThrow();
        mvc().perform(post("/biz/auth/staff-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13600001234\",\"code\":\"" + code + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("★ 员工离职再回来是常事 —— 重复添加要重新启用，不是报「已存在」")
    void reAddReactivates() throws Exception {
        String token = merchant("12600129008", "员工测试店B");
        String no = addStaff(token, "13600002345");

        mvc().perform(post("/biz/staff/" + no + "/status").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        // 报错的话，店长只会去建一个带后缀的假号码 —— 而那个号码收不到验证码
        mvc().perform(post("/biz/staff").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginPhone\":\"13600002345\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.mchAccountNo").value(no));
    }

    @Test
    @DisplayName("★ 老板不能被停用 —— 那是个能把自己锁在门外的按钮")
    void ownerCannotBeDisabled() throws Exception {
        String token = merchant("12600129009", "员工测试店C");
        String body = mvc().perform(get("/biz/staff").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        var owner = json.readTree(body).get("data").get(0);
        assertThat(owner.get("isOwner").asBoolean()).isTrue();

        mvc().perform(post("/biz/staff/" + owner.get("mchAccountNo").asString() + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("★ 逐店授权：A 店店长可以同时是 B 店店员")
    void perStoreRoles() throws Exception {
        String token = merchant("12600129010", "员工测试店D");
        String storeA = storeNoOf(token, 0);
        String storeB = create(token, "二店", "");
        String staff = addStaff(token, "13600003456");

        grant(token, staff, storeA, "MANAGER");
        String body = grant(token, staff, storeB, "CLERK");
        var roles = json.readTree(body).get("data").get("roles");
        assertThat(roles.size()).isEqualTo(2);

        /*
         * 收回一家店的授权。**V18 起要说清楚收回哪个角色** ——
         * 一人一店可多角色之后，「传空 = 收回这家店的全部」是个危险的默认：
         * 老板想去掉「配送员」，手一滑把这家店的授权全清了。
         */
        revoke(token, staff, storeA, "MANAGER");
        assertThat(rolesOf(token, staff, storeA)).isEmpty();
        assertThat(rolesOf(token, staff, storeB)).containsExactly("CLERK");
    }

    @Test
    @DisplayName("只能授权本主体的门店 —— 否则就是把别人的店交给自己的员工")
    void cannotGrantOthersStore() throws Exception {
        String a = merchant("12600129011", "员工测试店E");
        String b = merchant("12600129012", "员工测试店F");
        String staff = addStaff(a, "13600004567");
        String bStore = storeNoOf(b, 0);

        mvc().perform(post("/biz/staff/" + staff + "/store").header("Authorization", "Bearer " + a)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeNo\":\"" + bStore + "\",\"role\":\"MANAGER\"}"))
                .andExpect(jsonPath("$.code").value(10404));
    }

    // ---------------------------------------------------------------- 辅助

    private String list(String token) throws Exception {
        return mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
    }

    private String storeNoOf(String token, int idx) throws Exception {
        return json.readTree(list(token)).get("data").get(idx).get("storeNo").asString();
    }

    private String create(String token, String name, String address) throws Exception {
        String body = mvc().perform(post("/biz/store/create").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"address\":\"" + address + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("storeNo").asString();
    }

    @Test
    @DisplayName("★★ 加一个角色不能冲掉已有的 —— 一人一店可多角色")
    void grantingOneRoleKeepsTheOthers() throws Exception {
        String biz = merchant("12600240001", "多角色店");
        String store = defaultStoreNo(biz);
        String staff = addStaff(biz, "12600240002");

        grant(biz, staff, store, "CLERK");
        grant(biz, staff, store, "COURIER");

        /*
         * 小店的常态是一人多岗：站收银台的顺手把货送了。
         * 覆盖式授权在这里是错的 —— 老板想「再加一个配送」，
         * 结果把「店员」冲掉了，而且不报错。
         */
        assertThat(rolesOf(biz, staff, store))
                .as("加配送员之后，店员这个角色不该消失")
                .containsExactlyInAnyOrder("CLERK", "COURIER");
    }

    @Test
    @DisplayName("★ 撤销一个角色只掉那一个，剩下的还在")
    void revokingOneRoleKeepsTheOthers() throws Exception {
        String biz = merchant("12600240010", "撤角色店");
        String store = defaultStoreNo(biz);
        String staff = addStaff(biz, "12600240011");

        grant(biz, staff, store, "CLERK");
        grant(biz, staff, store, "COURIER");
        revoke(biz, staff, store, "COURIER");

        assertThat(rolesOf(biz, staff, store)).containsExactly("CLERK");
    }

    @Test
    @DisplayName("★ 撤到一个不剩 = 从这家店移除他")
    void revokingLastRoleRemovesHimFromStore() throws Exception {
        String biz = merchant("12600240020", "移除店");
        String store = defaultStoreNo(biz);
        String staff = addStaff(biz, "12600240021");

        grant(biz, staff, store, "CLERK");
        revoke(biz, staff, store, "CLERK");

        assertThat(rolesOf(biz, staff, store)).isEmpty();
    }

    @Test
    @DisplayName("★★ 撤销再授予同一个角色不能 500 —— 逻辑删的行还占着唯一键")
    void revokeThenGrantAgainWorks() throws Exception {
        String biz = merchant("12600240040", "撤了再加店");
        String store = defaultStoreNo(biz);
        String staff = addStaff(biz, "12600240041");

        grant(biz, staff, store, "COURIER");
        revoke(biz, staff, store, "COURIER");
        // 撤销是逻辑删，而 uk_store_role 不含 deleted —— 直接 insert 会撞唯一键
        grant(biz, staff, store, "COURIER");

        assertThat(rolesOf(biz, staff, store)).containsExactly("COURIER");

        // 再来一轮，确认复活是幂等的而不是只能救一次
        revoke(biz, staff, store, "COURIER");
        grant(biz, staff, store, "COURIER");
        assertThat(rolesOf(biz, staff, store)).containsExactly("COURIER");
    }

    @Test
    @DisplayName("★ 重复授予同一个角色是幂等的 —— 不该长出两行")
    void grantingSameRoleTwiceIsIdempotent() throws Exception {
        String biz = merchant("12600240030", "幂等店");
        String store = defaultStoreNo(biz);
        String staff = addStaff(biz, "12600240031");

        grant(biz, staff, store, "PICKER");
        grant(biz, staff, store, "PICKER");

        assertThat(rolesOf(biz, staff, store)).containsExactly("PICKER");
    }

    /** 默认门店号 —— 新商家恰好一家 */
    private String defaultStoreNo(String token) throws Exception {
        return json.readTree(list(token)).get("data").get(0).get("storeNo").asString();
    }

    /** 这个员工在这家店持有的全部角色 */
    private java.util.List<String> rolesOf(String token, String staffNo, String storeNo) throws Exception {
        String body = mvc().perform(get("/biz/staff").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var s : json.readTree(body).get("data")) {
            if (!staffNo.equals(s.get("mchAccountNo").asString())) {
                continue;
            }
            for (var r : s.get("roles")) {
                if (storeNo.equals(r.get("storeNo").asString())) {
                    out.add(r.get("role").asString());
                }
            }
        }
        return out;
    }

    private void revoke(String token, String staffNo, String storeNo, String role) throws Exception {
        mvc().perform(post("/biz/staff/" + staffNo + "/store")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeNo\":\"" + storeNo + "\",\"role\":\"" + role
                                + "\",\"granted\":false}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private String addStaff(String token, String phone) throws Exception {
        String body = mvc().perform(post("/biz/staff").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginPhone\":\"" + phone + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("mchAccountNo").asString();
    }

    private String grant(String token, String staffNo, String storeNo, String role) throws Exception {
        String roleJson = role == null ? "null" : "\"" + role + "\"";
        return mvc().perform(post("/biz/staff/" + staffNo + "/store")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeNo\":\"" + storeNo + "\",\"role\":" + roleJson + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
    }

    /** 把进件推到 ACTIVE 并返回收款商户号 —— 换收款号的用例要用真实的号。 */
    private String activatePayment(String token) throws Exception {
        String body = mvc().perform(post("/biz/merchant/payment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payChannel\":\"WECHAT\",\"settleAccount\":\"6222020000999988887\","
                                + "\"licenses\":[\"https://cdn/l.jpg\"],"
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\"}"))
                .andExpect(jsonPath("$.data.applyStatus").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("payMerchantNo").asString();
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

        String bd = opsLogin();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        // 商家身份是登录时解析进 BizContext 的，旧 token 上还没有
        return login(phone);
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

    private String opsLogin() throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bd\",\"password\":\"bd123\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
