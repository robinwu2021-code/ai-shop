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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1 用户与归属 —— **用例先行**（任务清单 §二 .4 步）。
 *
 * <p>本文件在实现之前写成，此时应全部失败。它按**契约（A1）与不变量（A2 §2）**写，
 * 不按代码现在的样子写 —— 后者永远是绿的，也永远抓不到问题。
 *
 * <p>覆盖：
 * ① 契约每条端点的正例（10 条新端点）
 * ② A2 §2.1 三条不变量的反例（标识唯一 / 归属同属 / 常去店与开店不混）
 * ③ 越权（读他人地址）、幂等（重复登录不建两个账号）、脱敏（手机号视角）
 */
@SpringBootTest
@ActiveProfiles("test")
class M1UserFlowTest {

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

    // ---------------------------------------------------------------- 契约正例

    @Test
    @DisplayName("C1：登录响应同时给 userNo 与 cUserNo（过渡期双写，前端改完删后者）")
    void loginReturnsBothUserNoFields() throws Exception {
        String body = loginRaw("13600136000");
        JsonNode user = json.readTree(body).get("data").get("user");
        assertThat(user.get("userNo").asString()).isNotBlank();
        assertThat(user.get("cUserNo").asString()).isEqualTo(user.get("userNo").asString());
    }

    @Test
    @DisplayName("会话续期：换新 token，旧 token 立即失效")
    void refreshTokenRotates() throws Exception {
        String old = login("13600136001");

        String body = mvc().perform(post("/mp/user/token/refresh").header("Authorization", "Bearer " + old))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String fresh = json.readTree(body).get("data").get("token").asString();
        assertThat(fresh).isNotEqualTo(old);

        // 旧 token 必须失效：不失效等于每次续期都多留一把可用的钥匙
        mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + old))
                .andExpect(status().isUnauthorized());
        mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + fresh))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("登出后 token 失效")
    void logoutInvalidatesToken() throws Exception {
        String token = login("13600136002");
        mvc().perform(post("/mp/user/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("微信用户绑定手机号后，两种标识指向同一账号")
    void bindPhoneKeepsSameAccount() throws Exception {
        String token = loginWechat("wx-openid-001");
        String userNo = profileUserNo(token);

        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13600136003\"}"));
        String code = otpStore.peek("13600136003").orElseThrow();
        mvc().perform(post("/mp/user/phone/bind").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13600136003\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("136****6003"));

        // 之后用手机号登录，必须还是同一个人 —— 否则同一用户会有两套订单与两个购物车
        String byPhone = login("13600136003");
        assertThat(profileUserNo(byPhone)).isEqualTo(userNo);
    }

    @Test
    @DisplayName("修改昵称与头像")
    void updateProfile() throws Exception {
        String token = login("13600136004");
        mvc().perform(post("/mp/user/profile").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"老王\",\"avatar\":\"https://cdn/a.png\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("老王"));
    }

    @Test
    @DisplayName("社区详情与自提点详情（游客可访问）")
    void communityAndPickupDetail() throws Exception {
        mvc().perform(get("/mp/community/C0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.communityNo").value("C0001"))
                .andExpect(jsonPath("$.data.pickups").isArray());

        mvc().perform(get("/mp/pickup/PP0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pickupNo").value("PP0001"))
                .andExpect(jsonPath("$.data.arrivalDesc").isNotEmpty());
    }

    // ---------------------------------------------------------------- 地址簿（R1）

    @Test
    @DisplayName("地址簿：新增 → 列表 → 编辑 → 归档")
    void addressCrud() throws Exception {
        String token = login("13600136005");

        String body = saveAddress(token, null, "张三", "13900001111", "文一西路 100 号", false);
        JsonNode list = json.readTree(body).get("data");
        assertThat(list).hasSize(1);
        String addressId = list.get(0).get("addressId").asString();

        // 编辑：传 addressId 即为更新，不新增
        body = saveAddress(token, addressId, "张三", "13900001111", "文一西路 200 号", false);
        list = json.readTree(body).get("data");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("detail").asString()).isEqualTo("文一西路 200 号");

        mvc().perform(post("/mp/user/address/" + addressId + "/archive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("默认地址至多一条：设新默认自动清旧（A2 §1 不变量）")
    void onlyOneDefaultAddress() throws Exception {
        String token = login("13600136006");
        saveAddress(token, null, "甲", "13900002222", "A 栋", true);
        String body = saveAddress(token, null, "乙", "13900003333", "B 栋", true);

        JsonNode list = json.readTree(body).get("data");
        long defaults = 0;
        for (JsonNode a : list) {
            if (a.get("isDefault").asBoolean()) {
                defaults++;
            }
        }
        // 两条默认地址会让下单时「取默认地址」这一步变成随机的
        assertThat(defaults).isEqualTo(1);
    }

    @Test
    @DisplayName("首个地址自动成为默认")
    void firstAddressBecomesDefault() throws Exception {
        String token = login("13600136007");
        String body = saveAddress(token, null, "丙", "13900004444", "C 栋", false);
        assertThat(json.readTree(body).get("data").get(0).get("isDefault").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("越权：改不了别人的地址（防 IDOR）")
    void cannotModifyOthersAddress() throws Exception {
        String owner = login("13600136008");
        String body = saveAddress(owner, null, "丁", "13900005555", "D 栋", true);
        String addressId = json.readTree(body).get("data").get(0).get("addressId").asString();

        String stranger = login("13600136009");
        mvc().perform(post("/mp/user/address/" + addressId + "/default")
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(jsonPath("$.code").value(10404));

        // 归档同样不可越权
        mvc().perform(post("/mp/user/address/" + addressId + "/archive")
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(jsonPath("$.code").value(10404));
    }

    // ---------------------------------------------------------------- 不变量反例

    @Test
    @DisplayName("同一手机号重复登录不建第二个账号（标识唯一，A2 §2.1 不变量①）")
    void repeatedLoginReusesAccount() throws Exception {
        String first = profileUserNo(login("13600136010"));
        String second = profileUserNo(login("13600136010"));
        assertThat(second).isEqualTo(first);
    }

    /*
     * 端上小程序发的是 WX_MINI，后端此前只认 WECHAT_MP —— 请求落进 default 分支抛 400，
     * 小程序上登录**必然失败**。两个名字同一件事，这里钉住它们不会再分家。
     */
    @Test
    @DisplayName("WX_MINI 与 WECHAT_MP 是同一个授权分支，同一凭证登进同一个账号")
    void wxMiniIsTheSameGrantAsWechatMp() throws Exception {
        String viaWechatMp = profileUserNo(loginWechat("wx-openid-mini-001"));
        String viaWxMini = profileUserNo(loginWxMini("wx-openid-mini-001"));
        assertThat(viaWxMini).isEqualTo(viaWechatMp);
    }

    /*
     * 上一条是「把 default 分支改窄」，这条守住「别改宽」——
     * 合并 case 时手滑写成 default 也能让上一条变绿，而那等于任何字符串都能登录。
     */
    @Test
    @DisplayName("未知 grantType 仍然被拒（default 分支不许被改宽）")
    void unknownGrantTypeIsRejected() throws Exception {
        mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"WX_PHONE\",\"principal\":\"whatever\",\"agreed\":true}"))
                .andExpect(jsonPath("$.code").value(10400));
    }

    @Test
    @DisplayName("归属整体替换：自提点必须属于该社区（不变量②）")
    void belongingMustBeConsistent() throws Exception {
        String token = login("13600136011");
        mvc().perform(post("/mp/user/community").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"communityNo\":\"C0001\",\"pickupNo\":\"PP0002\"}"))
                .andExpect(jsonPath("$.code").value(10400));
    }

    @Test
    @DisplayName("常去店 ≠ 我开的店：登录带 merchantNo 只写常去店，不授予商家身份（不变量③）")
    void visitedStoreDoesNotGrantMerchantRole() throws Exception {
        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13600136012\"}"));
        String code = otpStore.peek("13600136012").orElseThrow();
        String body = mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"13600136012\",\"credential\":\""
                                + code + "\",\"merchantNo\":\"M0001\",\"agreed\":true}"))
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).get("data").get("token").asString();

        // 常去店写上了
        mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.merchantNo").value("M0001"));
        // 但没有任何 B 端权限 —— 两个概念共用字段名的话，扫个店铺码就成了店主。
        // （M4 实现 /biz/order 之前这里是 404；现在端点存在，判定落在作用域上）
        mvc().perform(get("/biz/order").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(10403));
    }

    @Test
    @DisplayName("OTP 一次性：用过的验证码不能重放")
    void otpCannotBeReplayed() throws Exception {
        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13600136013\"}"));
        String code = otpStore.peek("13600136013").orElseThrow();
        String payload = "{\"grantType\":\"PHONE_OTP\",\"principal\":\"13600136013\",\"credential\":\""
                + code + "\",\"agreed\":true}";

        mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(jsonPath("$.code").value(10400));
    }

    // ---------------------------------------------------------------- helpers

    private String saveAddress(String token, String addressId, String name, String phone,
                               String detail, boolean isDefault) throws Exception {
        String idPart = addressId == null ? "" : "\"addressId\":\"" + addressId + "\",";
        return mvc().perform(post("/mp/user/address").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + idPart + "\"name\":\"" + name + "\",\"phone\":\"" + phone
                                + "\",\"province\":\"浙江省\",\"city\":\"杭州市\",\"district\":\"西湖区\",\"detail\":\""
                                + detail + "\",\"isDefault\":" + isDefault + ",\"tag\":\"家\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
    }

    private String profileUserNo(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userNo").asString();
    }

    private String loginRaw(String phone) throws Exception {
        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        return mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone
                                + "\",\"credential\":\"" + code + "\",\"agreed\":true}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String login(String phone) throws Exception {
        return json.readTree(loginRaw(phone)).get("data").get("token").asString();
    }

    private String loginWechat(String openid) throws Exception {
        return loginByGrant("WECHAT_MP", openid);
    }

    /** 端上小程序真正发的那个值（`shared:GrantType` 的 WX_MINI） */
    private String loginWxMini(String code) throws Exception {
        return loginByGrant("WX_MINI", code);
    }

    private String loginByGrant(String grantType, String principal) throws Exception {
        String body = mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"" + grantType + "\",\"principal\":\"" + principal
                                + "\",\"agreed\":true}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
