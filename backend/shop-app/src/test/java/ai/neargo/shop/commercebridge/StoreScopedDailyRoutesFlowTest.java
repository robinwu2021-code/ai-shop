package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 登录的是账户，进店凭登录令牌再校验门店（TDD-账户登录与选店校验）：新模型（STORE_V1）门店的店主，
 * 选了门店之后，日常经营接口（商品、订单、主体、收款）按「令牌 + 门店成员 + 原有权限码」放行，
 * 不要求业务端权益；没选门店回 SELECT_STORE；未登记的新命名空间照旧拒绝。
 */
@SpringBootTest(
        properties = {
            "shop.auth.token-store=db",
            "commerce.enabled=true",
            "commerce.access.active-key-id=test",
            "commerce.access.keys.test=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "spring.autoconfigure.exclude=ai.neargo.commerce.starter.CommercePersistenceAutoConfiguration,"
                + "ai.neargo.common.security.SecurityAutoConfiguration,ai.neargo.common.security.SessionStoreAutoConfiguration,"
                + "ai.neargo.common.data.DataAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:store_daily;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("test")
class StoreScopedDailyRoutesFlowTest {

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestPersistence {
        @org.springframework.context.annotation.Bean
        ai.neargo.commerce.core.spi.CommerceUnitOfWork uow(
                javax.sql.DataSource ds,
                org.springframework.transaction.PlatformTransactionManager manager) {
            return new ai.neargo.commerce.persistence.JdbcCommerceUnitOfWork(ds, manager);
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OtpStore otp;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String login(String phone) throws Exception {
        mvc.perform(post("/biz/auth/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        var body = mvc.perform(post("/biz/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone + "\",\"agreed\":true,"
                                + "\"credential\":\"" + otp.peek(phone).orElseThrow() + "\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode env = json.readTree(body);
        assertEquals(0, env.path("code").asInt(), body);
        return env.path("data").path("token").asText();
    }

    private JsonNode call(String token, String store, String path) throws Exception {
        var b = get(path).header("Authorization", "Bearer " + token);
        if (store != null) {
            b.header("X-Store-No", store);
        }
        return json.readTree(mvc.perform(b).andReturn().getResponse().getContentAsString());
    }

    /** 新店主：账户登录 → 快速开店 → 门店改成新模型 → 重新登录（新账户令牌） */
    private String[] newModelOwner(String phone) throws Exception {
        String first = login(phone);
        JsonNode profile = json.readTree(mvc.perform(post("/biz/merchant/quick-start")
                        .header("Authorization", "Bearer " + first).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeName\":\"Daily routes shop\"}"))
                .andReturn().getResponse().getContentAsString()).path("data");
        String store = profile.path("currentStoreNo").asText();
        assertFalse(store.isBlank());
        jdbc.update("update mch_store set commerce_model='STORE_V1', business_type_code='RETAIL' where store_no=?",
                store);
        return new String[] {login(phone), store};
    }

    @Test
    @DisplayName("★★★ 新模型门店的店主选了门店：商品、订单、主体、收款这些日常接口按门店成员放行，不再 70006")
    void selectedStoreOpensDailyRoutes() throws Exception {
        String[] o = newModelOwner("15965020101");
        for (String path : new String[] {"/biz/goods?size=1", "/biz/order?caller=RETAIL", "/biz/entities",
                "/biz/payment/collect-status", "/biz/after-sale/worklist"}) {
            JsonNode r = call(o[0], o[1], path);
            assertEquals(0, r.path("code").asInt(), path + " → " + r);
        }
    }

    @Test
    @DisplayName("★★ 新店主给自己的店设货架类目（POST）：同样按门店成员放行")
    void ownerSetsStoreCategories() throws Exception {
        String[] o = newModelOwner("15965020104");
        String body = mvc.perform(post("/biz/store/" + o[1] + "/categories")
                        .header("Authorization", "Bearer " + o[0]).header("X-Store-No", o[1])
                        .contentType(MediaType.APPLICATION_JSON).content("{\"categoryNos\":[\"CAT210\"]}"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(0, json.readTree(body).path("code").asInt(), body);
    }

    @Test
    @DisplayName("★★★ 没选门店：日常接口回 SELECT_STORE（让 App 去选店），而不是「联系管理员」")
    void noSelectionAsksToSelectStore() throws Exception {
        String[] o = newModelOwner("15965020102");
        JsonNode r = call(o[0], null, "/biz/goods?size=1");
        assertEquals(70006, r.path("code").asInt(), r.toString());
        assertEquals("SELECT_STORE", r.path("data").path("nextAction").asText(), r.toString());
    }

    @Test
    @DisplayName("★★ 默认拒绝不变：选了门店，未登记的新命名空间仍然拒绝")
    void unregisteredStillDenied() throws Exception {
        String[] o = newModelOwner("15965020103");
        JsonNode r = call(o[0], o[1], "/biz/stores/" + o[1] + "/not-registered");
        assertEquals(70006, r.path("code").asInt(), r.toString());
    }
}
