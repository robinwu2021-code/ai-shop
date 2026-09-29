package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.OrderService.CreateOrderCommand;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 下单落店（TDD-C端门店化与门店门户 §2.7 · AC7 / AC19）。
 *
 * <p>直接量 {@code storesOfEntities}：{@code OrderVO} 不带门店号，从接口外面看不出单落在哪家店 ——
 * 而这件事错了不报错，只会在对账时表现成两家店的账都对不上。
 *
 * <p>用种子主体 M0001（默认店 ST-M0001）再挂分店；<b>改了默认店的状态一律在 {@link #cleanup} 里还原</b>。
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreOrderRoutingTest {

    private static final String ENTITY = "M0001";
    private static final String DEFAULT_STORE = "ST-M0001";

    @Autowired
    private OrderService orderService;
    @Autowired
    private MchStoreMapper storeMapper;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OtpStore otpStore;

    private final List<Runnable> undo = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = undo.size() - 1; i >= 0; i--) {
            undo.get(i).run();
        }
        undo.clear();
    }

    @Test
    @DisplayName("★ AC19 不传门店偏好：与改造前相同，落默认店")
    void noChoiceKeepsDefault() {
        addStore("ACTIVE");
        assertThat(route(null)).isEqualTo(DEFAULT_STORE);
    }

    @Test
    @DisplayName("★ AC19 在分店的门户里下单：落到那家分店")
    void choiceLandsOnChosenStore() {
        String b = addStore("ACTIVE");
        assertThat(route(Map.of(ENTITY, b))).isEqualTo(b);
    }

    @Test
    @DisplayName("AC19 别家主体的门店号被忽略，按老规则落默认店")
    void foreignStoreIgnored() {
        assertThat(route(Map.of(ENTITY, "ST-M0002"))).isEqualTo(DEFAULT_STORE);
    }

    @Test
    @DisplayName("★ AC7 指定的店暂停营业：拒单 STORE_PAUSED，不悄悄换到别家")
    void pausedChoiceRejected() {
        String b = addStore("READONLY");
        assertThatThrownBy(() -> route(Map.of(ENTITY, b)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.STORE_PAUSED));
    }

    @Test
    @DisplayName("★ AC7 默认店暂停、有别的营业店：落到营业店（此前 READONLY 的默认店照样收单）")
    void pausedDefaultFallsBackToOpenStore() {
        String b = addStore("ACTIVE");
        setDefaultStatus("READONLY");
        assertThat(route(null)).isEqualTo(b);
    }

    @Test
    @DisplayName("AC7 默认店暂停、没有别的营业店：拒单")
    void pausedDefaultWithoutAlternativeRejected() {
        setDefaultStatus("READONLY");
        assertThatThrownBy(() -> route(null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.STORE_PAUSED));
    }

    @Test
    @DisplayName("★ 请求体 storeChoices 一路接到落店：预览里指定暂停的店回 20008")
    void requestFieldIsWired() throws Exception {
        String b = addStore("READONLY");
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        String token = TestLogin.consumer(mvc, json, otpStore, "12600137001");
        String body = "{\"fulfillment\":\"STORE_PICKUP\","
                + "\"items\":[{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}],"
                + "\"storeChoices\":[{\"merchantNo\":\"" + ENTITY + "\",\"storeNo\":\"" + b + "\"}]}";
        JsonNode r = json.readTree(mvc.perform(post("/mp/order/preview").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
        assertThat(r.get("code").asInt()).as(r.toString()).isEqualTo(ErrorCode.STORE_PAUSED.code());

        // 对照：不带 storeChoices 的同一请求能预览 —— 证明上面的 20008 来自这个字段，不是别的闸
        String plain = body.replaceAll(",\"storeChoices\":\\[[^]]*]", "");
        JsonNode ok = json.readTree(mvc.perform(post("/mp/order/preview").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(plain))
                .andReturn().getResponse().getContentAsString());
        assertThat(ok.get("code").asInt()).as(ok.toString()).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private String route(Map<String, String> choices) {
        OrderServiceImpl impl = AopTestUtils.getTargetObject(orderService);
        CreateOrderCommand cmd = new CreateOrderCommand(List.of(), "STORE_PICKUP", null, null, null, 0L, null,
                null, null, null, null, null, false, null, choices);
        return impl.storesOfEntities(cmd, List.of(ENTITY)).get(ENTITY);
    }

    private String addStore(String status) {
        MchStore s = new MchStore();
        s.setEntityNo(ENTITY);
        s.setStoreNo("STRT" + System.nanoTime());
        s.setName("落店测试分店");
        s.setIsDefault(false);
        s.setStatus(status);
        s.setFeatured("[]");
        s.setTenantNo("MAIN");
        DataScopeContext.executeWithoutScope(() -> storeMapper.insert(s));
        undo.add(() -> DataScopeContext.executeWithoutScope(() -> storeMapper.deleteById(s.getId())));
        return s.getStoreNo();
    }

    private void setDefaultStatus(String status) {
        MchStore def = DataScopeContext.executeWithoutScope(() -> storeMapper.selectOne(
                Wrappers.<MchStore>lambdaQuery().eq(MchStore::getStoreNo, DEFAULT_STORE)));
        String before = def.getStatus();
        DataScopeContext.executeWithoutScope(() -> storeMapper.update(null, Wrappers.<MchStore>lambdaUpdate()
                .eq(MchStore::getStoreNo, DEFAULT_STORE).set(MchStore::getStatus, status)));
        undo.add(() -> DataScopeContext.executeWithoutScope(() -> storeMapper.update(null,
                Wrappers.<MchStore>lambdaUpdate().eq(MchStore::getStoreNo, DEFAULT_STORE)
                        .set(MchStore::getStatus, before))));
    }
}
