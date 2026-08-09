package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.user.IdentityType;
import ai.neargo.shop.user.entity.UsrIdentity;
import ai.neargo.shop.user.mapper.UserMappers.IdentityMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 身份统一（S2 / 安全整改方案 §六）。
 *
 * <p>旧结构把 phone/openid/apple_sub 平铺成三个列，登录只按本次用的那一列查，
 * 查不到就建新账号——同一个人换个入口就变成两个账号，订单、积分、卡包全部分裂，
 * <b>且不报任何错</b>。「不报错」是这件事最难发现的地方，所以这些用例断言的是
 * 数据层面的事实（{@code usr_identity} 有几行、指向谁），而不只是接口返回 200。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("身份统一：一个人一个账号")
class IdentityUnificationFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private OtpStore otpStore;

    @Autowired
    private IdentityMapper identityMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("登录即登记凭证：微信登录后 usr_identity 有一条 WX_OPENID_MP")
    void wechatLoginRegistersCredential() throws Exception {
        String openid = "wx-openid-idt-001";
        String userNo = profileUserNo(loginWechat(openid));

        List<UsrIdentity> rows = identityMapper.selectList(Wrappers.<UsrIdentity>lambdaQuery()
                .eq(UsrIdentity::getUserNo, userNo));
        assertThat(rows).extracting(UsrIdentity::getIdentityType)
                .as("登录建户时必须把本次凭证登记进 usr_identity，否则下次同一个人来了认不出")
                .contains(IdentityType.WX_OPENID_MP);
        assertThat(rows).filteredOn(r -> IdentityType.WX_OPENID_MP.equals(r.getIdentityType()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getIdentityValue()).isEqualTo(openid);
                    assertThat(r.getChannel()).as("来源留痕：排查「这个人从哪进来的」要靠它").isEqualTo("MP");
                });
    }

    @Test
    @DisplayName("绑手机号后，两条凭证挂在同一个人名下")
    void bindPhoneAddsSecondCredentialToSamePerson() throws Exception {
        String token = loginWechat("wx-openid-idt-002");
        String userNo = profileUserNo(token);
        bindPhone(token, "13800138002").andExpect(status().isOk());

        List<UsrIdentity> rows = identityMapper.selectList(Wrappers.<UsrIdentity>lambdaQuery()
                .eq(UsrIdentity::getUserNo, userNo));
        assertThat(rows).extracting(UsrIdentity::getIdentityType)
                .as("一个人多条凭证——这正是拆表要换来的能力")
                .containsExactlyInAnyOrder(IdentityType.WX_OPENID_MP, IdentityType.PHONE);
    }

    @Test
    @DisplayName("**核心**：微信进来的人绑了手机号后，改用手机号登录仍是同一个 userNo")
    void sameUserAcrossEntryPoints() throws Exception {
        String phone = "13800138003";
        String wechatToken = loginWechat("wx-openid-idt-003");
        String userNo = profileUserNo(wechatToken);
        bindPhone(wechatToken, phone).andExpect(status().isOk());

        // 换一条路进来 —— 旧实现在这里会建出第二个账号
        String userNoByPhone = profileUserNo(loginByPhone(phone));

        assertThat(userNoByPhone)
                .as("同一个人从两个入口登录必须是同一个 userNo，否则他的订单与积分会分裂在两个账号上")
                .isEqualTo(userNo);
    }

    @Test
    @DisplayName("手机号已属于他人时拒绝绑定（业务码 10409），不自动合并")
    void conflictingPhoneIsRejected() throws Exception {
        String phone = "13800138004";
        loginByPhone(phone);                       // 甲：手机号注册

        String otherToken = loginWechat("wx-openid-idt-004");   // 乙：微信注册，想绑同一个号
        // 本项目的错误约定是 HTTP 200 + 业务码（见 docs/api/响应格式规范.md），
        // 不是 HTTP 409 —— 断言业务码才是这里真正的契约
        bindPhone(otherToken, phone)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value(10409));

        /*
         * 自动合并要迁移订单、积分、卡包、优惠券、地址，横跨五个域，合错了难回滚。
         * 一期只做「检测 + 阻止 + 留痕」，但**检测必须有** ——
         * 没有的话冲突会以数据库唯一键异常的形式冒出来，用户看到的是 500。
         */
        assertThat(identityMapper.selectList(Wrappers.<UsrIdentity>lambdaQuery()
                .eq(UsrIdentity::getIdentityType, IdentityType.PHONE)
                .eq(UsrIdentity::getIdentityValue, phone)))
                .as("被拒之后不能留下第二条同值凭证")
                .hasSize(1);
    }

    @Test
    @DisplayName("重复登录不产生第二条凭证（幂等）")
    void repeatedLoginIsIdempotent() throws Exception {
        String openid = "wx-openid-idt-005";
        String userNo = profileUserNo(loginWechat(openid));
        loginWechat(openid);
        loginWechat(openid);

        assertThat(identityMapper.selectList(Wrappers.<UsrIdentity>lambdaQuery()
                .eq(UsrIdentity::getIdentityType, IdentityType.WX_OPENID_MP)
                .eq(UsrIdentity::getIdentityValue, openid)))
                .as("补登凭证前要先查在不在，否则每次登录都插一行")
                .hasSize(1)
                .allSatisfy(r -> assertThat(r.getUserNo()).isEqualTo(userNo));
    }

    // ---------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.ResultActions bindPhone(String token, String phone)
            throws Exception {
        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        return mvc().perform(post("/mp/user/phone/bind").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"));
    }

    private String profileUserNo(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userNo").asString();
    }

    private String loginByPhone(String phone) throws Exception {
        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        String body = mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone
                                + "\",\"credential\":\"" + code + "\",\"agreed\":true}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    private String loginWechat(String openid) throws Exception {
        String body = mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"WECHAT_MP\",\"principal\":\"" + openid
                                + "\",\"agreed\":true}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
