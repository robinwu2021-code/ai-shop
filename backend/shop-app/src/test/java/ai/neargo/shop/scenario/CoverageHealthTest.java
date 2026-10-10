package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.support.TestLogin;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 坐标健康度 —— **整个位置模块的分母**。
 *
 * <p>门店没标点时 `requireWithinDeliveryRadius` 那条闸**直接放行**（缺数据不该拦
 * 正常订单，这是对的）。代价是商家以为自己限了三公里、实际多远的单都进来，
 * 等他要送货才发现送不到，那时钱已经收了 —— <b>而这件事今天在任何界面上都看不见</b>。
 *
 * <p>这一组守的是「数字是真的，且能下钻」。**只给数字不给明细，运营下一步无从做起。**
 */
@SpringBootTest
@ActiveProfiles("test")
class CoverageHealthTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;
    @Autowired
    private ai.neargo.shop.user.mapper.UserMappers.AddressMapper addressMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private JsonNode health() throws Exception {
        String body = mvc().perform(get("/ops/coverage/health")
                        .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    @Test
    @DisplayName("★★★ 数字必须与直接查库一致 —— 分母写错的分析比没有分析更危险")
    void numbersMatchTheDatabase() throws Exception {
        long storeTotal = DataScopeContext.executeWithoutScope(() -> storeMapper.selectCount(
                Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()));
        long storeWithCoords = DataScopeContext.executeWithoutScope(() -> storeMapper.selectCount(
                Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .isNotNull(ai.neargo.shop.merchant.entity.MchStore::getLatE6)));
        long addrTotal = DataScopeContext.executeWithoutScope(() -> addressMapper.selectCount(
                Wrappers.<ai.neargo.shop.user.entity.UsrAddress>lambdaQuery()));

        JsonNode h = health();
        assertThat(h.get("stores").get("total").asInt()).isEqualTo((int) storeTotal);
        assertThat(h.get("stores").get("withCoords").asInt()).isEqualTo((int) storeWithCoords);
        assertThat(h.get("addresses").get("total").asInt()).isEqualTo((int) addrTotal);
    }

    @Test
    @DisplayName("★★★ 没标点的门店要能点名到户 —— 只给一个数字，运营下一步无从做起")
    void missingStoresAreListedNotJustCounted() throws Exception {
        JsonNode h = health();
        int total = h.get("stores").get("total").asInt();
        int withCoords = h.get("stores").get("withCoords").asInt();
        JsonNode missing = h.get("stores").get("missing");

        assertThat(missing).hasSize(total - withCoords);
        if (!missing.isEmpty()) {
            JsonNode one = missing.get(0);
            assertThat(one.get("storeNo").asString()).isNotBlank();
            assertThat(one.get("merchantNo").asString())
                    .as("要能从这里跳到商家去催他标点，否则这一页只是个数字")
                    .isNotBlank();
            /*
             * 半径也要带上：它正是「这家店以为自己限了多少米、而实际一米都没限」
             * 这句话的两个数之一。少了它，运营说不清后果有多大。
             */
            assertThat(one.has("deliveryRadiusM")).isTrue();
        }
    }

    @Test
    @DisplayName("★★ 地址只给聚合数**不给明细** —— 那是个人信息，看总数就够判断分母有多脏")
    void addressHealthIsAggregateOnly() throws Exception {
        JsonNode addresses = health().get("addresses");
        assertThat(addresses.has("total")).isTrue();
        assertThat(addresses.has("withCoords")).isTrue();
        assertThat(addresses.has("missing"))
                .as("地址明细不该出现在这一页 —— 运营没有理由逐条看买家住哪儿")
                .isFalse();
    }

    @Test
    @DisplayName("★★★ 没有运营权限调不动它")
    void requiresOpsPermission() throws Exception {
        mvc().perform(get("/ops/coverage/health"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isUnauthorized());
    }
}
