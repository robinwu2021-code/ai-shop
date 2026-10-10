package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.support.TestLogin;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 绑聚落不需要自提点（TDD-C端位置选择-地址取代自提点 §M1）。
 *
 * <p><b>买家选的是地址，不是自提点。</b>聚落由地址坐标推出来，自提点是履约期的事。
 * 此前这条接口强制成对，代价不是「多传一个参数」——
 * 是**一个没有自提点的聚落，买家根本绑不上，于是看不到任何货**，且没有任何提示。
 * 而端上为了凑够这一对，只能替他挑 `pickups[0]`，
 * 等于让数组顺序决定了履约服务费归谁。
 */
@SpringBootTest
@ActiveProfiles("test")
class BindCommunityWithoutPickupTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper communityMapper;
    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.PickupPointMapper pickupMapper;

    private static int seq = 9700;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    /** 一个**没有任何自提点**的开通聚落 —— 今天的模型下它等于「买家进不去的地方」 */
    private String communityWithoutPickup() {
        var c = new CmtCommunity();
        c.setCommunityNo("NP" + seq++);
        c.setName("没有自提点的小区" + seq);
        c.setStatus("OPEN");
        c.setRegionCode("330106041");
        c.setKind(CmtCommunity.KIND_ESTATE);
        c.setFenceRadius(1000);
        c.setLatE6(30_600_000);
        c.setLngE6(120_600_000);
        DataScopeContext.executeWithoutScope(() -> communityMapper.insert(c));
        // 对照：这个聚落确实一个点都没有，否则下面测的不是同一件事
        long points = DataScopeContext.executeWithoutScope(() -> pickupMapper.selectCount(
                Wrappers.<ai.neargo.shop.community.entity.CmtPickupPoint>lambdaQuery()
                        .eq(ai.neargo.shop.community.entity.CmtPickupPoint::getCommunityNo,
                                c.getCommunityNo())));
        assertThat(points).as("夹具本身要成立：这个聚落不该有自提点").isZero();
        return c.getCommunityNo();
    }

    private String bind(String token, String communityNo, String pickupNo) throws Exception {
        String body = "{\"communityNo\":\"" + communityNo + "\""
                + (pickupNo == null ? "" : ",\"pickupNo\":\"" + pickupNo + "\"") + "}";
        return mvc().perform(post("/mp/user/community")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("★★★ 没有自提点的聚落也绑得上 —— 今天这条是红的，症状是「那儿的人看不到任何货」")
    void communityWithoutPickupCanStillBeBound() throws Exception {
        String token = TestLogin.consumerByWechat(mvc(), json, "wx-nopickup-1");
        String community = communityWithoutPickup();

        String resp = bind(token, community, null);
        assertThat(json.readTree(resp).path("code").asInt())
                .as("不传自提点就绑不上 = 没有点的聚落里的人，一件货都看不到，而且没有任何提示")
                .isZero();
        assertThat(json.readTree(resp).path("data").path("communityNo").asString())
                .isEqualTo(community);
        assertThat(json.readTree(resp).path("data").path("pickupNo").isNull()
                || json.readTree(resp).path("data").path("pickupNo").asString().isEmpty())
                .as("不传就不该凭空写一个点上去 —— 那个点决定履约服务费归谁")
                .isTrue();
    }

    @Test
    @DisplayName("★★★ 换到没有点的聚落，要**清掉**上一个聚落的自提点")
    void switchingToAPickuplessCommunityClearsTheOldPickup() throws Exception {
        /*
         * `updateById` 跳过 null：只把对象上的字段置空、照旧 updateById 的话，
         * 那句 set 根本不会生成 —— 用户换了个聚落，却留着上一个聚落的自提点，
         * 而两者根本不在一起。零报错。
         */
        String token = TestLogin.consumerByWechat(mvc(), json, "wx-nopickup-2");

        // 先绑一个**有点**的聚落（种子里的 C0001 带点）
        String withPoint = mvc().perform(get("/mp/community/nearby"))
                .andReturn().getResponse().getContentAsString();
        var first = json.readTree(withPoint).path("data");
        String cNo = null;
        String pNo = null;
        for (var c : first) {
            var ps = c.path("pickups");
            if (ps.size() > 0) {
                cNo = c.path("communityNo").asString();
                pNo = ps.get(0).path("pickupNo").asString();
                break;
            }
        }
        assertThat(cNo).as("种子里要有一个带自提点的聚落，否则这条用例测不到「清掉」").isNotNull();
        assertThat(json.readTree(bind(token, cNo, pNo)).path("data").path("pickupNo").asString())
                .as("前置：先绑上一个点").isEqualTo(pNo);

        String pickupless = communityWithoutPickup();
        bind(token, pickupless, null);

        /*
         * ★ **回读，不看这次响应。**
         *
         * 响应体是拿内存里那个对象拼的 —— 把字段置成 null，它当然显示为空，
         * 而库里可能一点没动（`updateById` 跳过 null，那句 set 根本不生成）。
         * 第一版我就是这么写的，消融时改回 updateById **照样绿**。
         * 重新查一次 profile，问的才是库。
         */
        var fresh = json.readTree(mvc().perform(get("/mp/user/profile")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(fresh.path("communityNo").asString()).isEqualTo(pickupless);
        assertThat(fresh.path("pickupNo").isNull() || fresh.path("pickupNo").asString().isEmpty())
                .as("旧点没清掉 = 他的自提点指着另一个聚落，而 updateById 不会为 null 生成 set")
                .isTrue();
    }

    @Test
    @DisplayName("★★ 传了点仍然校验「点属于该社区」—— 放开可空不等于放开乱传")
    void pickupStillValidatedWhenProvided() throws Exception {
        String token = TestLogin.consumerByWechat(mvc(), json, "wx-nopickup-3");
        String community = communityWithoutPickup();
        String resp = bind(token, community, "PK-NOT-IN-THIS-COMMUNITY");
        assertThat(json.readTree(resp).path("code").asInt())
                .as("随便传两个不相干的号也能存进去 = 商品池按社区取、履约按点走，"
                        + "用户会看到一个永远到不了货的组合")
                .isNotZero();
    }
}
