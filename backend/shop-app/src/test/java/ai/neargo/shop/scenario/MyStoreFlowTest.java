package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.user.entity.UsrStoreView;
import ai.neargo.shop.user.mapper.UserMappers.StoreViewMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 我的店 / 附近 / 进店（TDD-C端门店化与门店门户 AC1–AC5、AC8）。
 *
 * <p>这条链最容易出的错是「页面上照样有一排店，只是单位不对」：主体名顶替门店名、
 * 四家店合成一行、没坐标的店以「0 米」排第一。所以断言一律落在<b>门店号与门店名</b>上，
 * 不断「列表非空」。
 */
@SpringBootTest
@ActiveProfiles("test")
class MyStoreFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private MchStoreMapper storeMapper;
    @Autowired
    private StoreViewMapper viewMapper;
    @Autowired
    private SubOrderMapper subOrderMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ AC1 主体号进店落到具体门店：我的店里是门店名，不是主体名")
    void entityLinkLandsOnDefaultStore() throws Exception {
        String entityNo = approvedMerchantNo("12600136001", "门店化测试主体甲", "CM-MYS-A");
        MchStore def = defaultStore(entityNo);
        rename(def, "甲 · 福田店");
        String buyer = TestLogin.consumer(mvc(), json, otpStore, "12600136101");

        // 老链接带的是主体号
        call(post("/mp/store/" + entityNo + "/enter"), buyer, "{\"source\":\"SCAN\"}");

        JsonNode mine = data(get("/mp/store/mine"), buyer);
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).get("storeNo").asString()).isEqualTo(def.getStoreNo()).startsWith("ST");
        assertThat(mine.get(0).get("storeName").asString()).isEqualTo("甲 · 福田店");
        assertThat(mine.get(0).get("entityNo").asString()).isEqualTo(entityNo);
        assertThat(mine.get(0).get("relation").get("firstSource").asString()).isEqualTo("SCAN");
    }

    @Test
    @DisplayName("★ AC2 首次来源只定一次：先分享后列表，仍记分享与分享人；次数累加")
    void firstSourceIsSticky() throws Exception {
        String entityNo = approvedMerchantNo("12600136002", "门店化测试主体乙", "CM-MYS-B");
        String storeNo = defaultStore(entityNo).getStoreNo();
        String buyer = TestLogin.consumer(mvc(), json, otpStore, "12600136102");
        String userNo = userNoOf(buyer);

        call(post("/mp/store/" + storeNo + "/enter"), buyer, "{\"source\":\"SHARE\",\"inviterNo\":\"U-INV-1\"}");
        call(post("/mp/store/" + storeNo + "/enter"), buyer, "{\"source\":\"LIST\",\"inviterNo\":\"U-INV-2\"}");

        UsrStoreView v = view(userNo, storeNo);
        assertThat(v.getFirstSource()).isEqualTo("SHARE");
        assertThat(v.getFirstInviterNo()).isEqualTo("U-INV-1");
        assertThat(v.getViewCount()).isEqualTo(2);
        assertThat(v.getEntityNo()).isEqualTo(entityNo);
    }

    @Test
    @DisplayName("AC2 非分享来源不记分享人；不认识的来源按 LIST，不猜成分享")
    void inviterOnlyForShare() throws Exception {
        String entityNo = approvedMerchantNo("12600136003", "门店化测试主体丙", "CM-MYS-C");
        String storeNo = defaultStore(entityNo).getStoreNo();
        String buyer = TestLogin.consumer(mvc(), json, otpStore, "12600136103");

        call(post("/mp/store/" + storeNo + "/enter"), buyer, "{\"source\":\"WHATEVER\",\"inviterNo\":\"U-INV-3\"}");

        UsrStoreView v = view(userNoOf(buyer), storeNo);
        assertThat(v.getFirstSource()).isEqualTo("LIST");
        assertThat(v.getFirstInviterNo()).isNull();
    }

    @Test
    @DisplayName("种子商家的老链接（主体号 M0002）落到它的默认门店 ST-M0002")
    void seedEntityLink() throws Exception {
        String buyer = TestLogin.consumer(mvc(), json, otpStore, "12600136109");
        call(post("/mp/store/M0002/enter"), buyer, "{}");
        JsonNode mine = data(get("/mp/store/mine"), buyer);
        assertThat(storeNos(mine)).containsExactly("ST-M0002");
    }

    @Test
    @DisplayName("★ 不带 source、不带 body 的进店照常成功，按 LIST 记（老调用方都不带）")
    void enterWithoutSource() throws Exception {
        String entityNo = approvedMerchantNo("12600136008", "门店化测试主体庚", "CM-MYS-G");
        String storeNo = defaultStore(entityNo).getStoreNo();
        String buyer = TestLogin.consumer(mvc(), json, otpStore, "12600136108");

        call(post("/mp/store/" + entityNo + "/enter"), buyer, "{}");
        mvc().perform(authed(post("/mp/store/" + storeNo + "/enter"), buyer))
                .andExpect(jsonPath("$.code").value(0));

        UsrStoreView v = view(userNoOf(buyer), storeNo);
        assertThat(v.getFirstSource()).isEqualTo("LIST");
        assertThat(v.getViewCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ AC3 同主体两家店是两行；只逛过的超过 30 天掉出，买过的一直在")
    void twoStoresTwoRowsAndKeepRules() throws Exception {
        String entityNo = approvedMerchantNo("12600136004", "门店化测试主体丁", "CM-MYS-D");
        MchStore a = defaultStore(entityNo);
        MchStore b = addStore(entityNo, "STMYS0004B", "丁 · 二号店", "ACTIVE", null, null);
        String buyer = TestLogin.consumer(mvc(), json, otpStore, "12600136104");
        String userNo = userNoOf(buyer);

        call(post("/mp/store/" + a.getStoreNo() + "/enter"), buyer, "{\"source\":\"LIST\"}");
        call(post("/mp/store/" + b.getStoreNo() + "/enter"), buyer, "{\"source\":\"LIST\"}");
        assertThat(storeNos(data(get("/mp/store/mine"), buyer)))
                .containsExactlyInAnyOrder(a.getStoreNo(), b.getStoreNo());

        // 两家都退到 31 天前；a 有一张成交单
        long old = System.currentTimeMillis() - 31L * 86_400_000L;
        for (String no : List.of(a.getStoreNo(), b.getStoreNo())) {
            UsrStoreView v = view(userNo, no);
            v.setLastAt(old);
            viewMapper.updateById(v);
        }
        paidSubOrder(userNo, entityNo, a.getStoreNo(), OrdSubOrder.COMPLETED);
        // 取消的单不算成交 —— 否则「买过 1 次」会挂在一张取消掉的单上
        paidSubOrder(userNo, entityNo, b.getStoreNo(), "CANCELLED");

        JsonNode mine = data(get("/mp/store/mine"), buyer);
        assertThat(storeNos(mine)).containsExactly(a.getStoreNo());
        assertThat(mine.get(0).get("relation").get("orderCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ AC4/AC5 附近：去掉我的店、按距离升序，没坐标的排最后而不是以 0 米排第一；停用的不列")
    void nearbyOrderAndExclusions() throws Exception {
        String community = "CM-MYS-E";
        String entityNo = approvedMerchantNo("12600136005", "门店化测试主体戊", community);
        MchStore def = defaultStore(entityNo);
        // 买家在 (22.540000, 114.050000)
        int lat = 22_540_000, lng = 114_050_000;
        setCoords(def, null, null);
        MchStore far = addStore(entityNo, "STMYS0005F", "戊 · 远店", "ACTIVE", 22_560_000, 114_050_000);
        MchStore near = addStore(entityNo, "STMYS0005N", "戊 · 近店", "ACTIVE", 22_541_000, 114_050_000);
        MchStore visited = addStore(entityNo, "STMYS0005V", "戊 · 逛过", "ACTIVE", 22_540_100, 114_050_000);
        MchStore closed = addStore(entityNo, "STMYS0005R", "戊 · 停用", "READONLY", 22_540_000, 114_050_000);
        String buyer = TestLogin.consumer(mvc(), json, otpStore, "12600136105");
        call(post("/mp/store/" + visited.getStoreNo() + "/enter"), buyer, "{\"source\":\"LIST\"}");
        call(post("/mp/store/" + closed.getStoreNo() + "/enter"), buyer, "{\"source\":\"LIST\"}");

        // keyword 收窄到本主体：全量跑时有几十家全城可达的店，默认一页 20 条会把没坐标的那家挤出去
        JsonNode page = data(get("/mp/store/nearby").param("communityNo", community).param("keyword", "戊")
                .param("latE6", String.valueOf(lat)).param("lngE6", String.valueOf(lng)), buyer);
        // 全量跑时别的测试建的 PLATFORM / CITY 范围商家对所有社区可达，也会出现在这一页 ——
        // 顺序只比本主体的门店；「有距离的全在没距离的前面」对整页成立
        JsonNode records = page.get("records");
        List<String> all = storeNos(records);
        assertThat(all).doesNotContain(visited.getStoreNo(), closed.getStoreNo());
        List<String> own = all.stream().filter(no -> no.startsWith("STMYS0005") || no.equals(def.getStoreNo())).toList();
        assertThat(own).containsExactly(near.getStoreNo(), far.getStoreNo(), def.getStoreNo());
        boolean seenNull = false;
        for (JsonNode r : records) {
            boolean isNull = r.get("distanceM").isNull();
            assertThat(seenNull && !isNull).as("有距离的排在了没距离的后面：%s", r.get("storeNo")).isFalse();
            seenNull |= isNull;
            if (r.get("storeNo").asString().equals(near.getStoreNo())) {
                assertThat(r.get("distanceM").asInt()).isBetween(100, 120);
            }
            if (r.get("storeNo").asString().equals(def.getStoreNo())) {
                assertThat(isNull).isTrue();
            }
        }

        // 停用的店不在附近，但在我的店里（压淡显示，藏起来用户会以为店没了）
        JsonNode mine = data(get("/mp/store/mine"), buyer);
        assertThat(storeNos(mine)).contains(closed.getStoreNo(), visited.getStoreNo());
        for (JsonNode c : mine) {
            if (c.get("storeNo").asString().equals(closed.getStoreNo())) {
                assertThat(c.get("status").asString()).isEqualTo("READONLY");
            }
        }
    }

    @Test
    @DisplayName("AC8 游客：我的店回空、附近照常可逛")
    void guestSeesNearbyOnly() throws Exception {
        String community = "CM-MYS-F";
        String entityNo = approvedMerchantNo("12600136006", "门店化测试主体己", community);
        String storeNo = defaultStore(entityNo).getStoreNo();

        JsonNode mine = data(get("/mp/store/mine"), null);
        assertThat(mine.size()).isZero();
        JsonNode page = data(get("/mp/store/nearby").param("communityNo", community).param("keyword", "己"), null);
        // 全量跑时会混进全城可达的商家，只断「本店在、且都是营业中的」
        assertThat(storeNos(page.get("records"))).contains(storeNo);
        page.get("records").forEach(r -> assertThat(r.get("status").asString()).isEqualTo("ACTIVE"));
    }

    @Test
    @DisplayName("不存在的门店号 404（不按主体号兜底 —— ST 开头就是门店号）")
    void unknownStoreIs404() throws Exception {
        String buyer = TestLogin.consumer(mvc(), json, otpStore, "12600136107");
        JsonNode r = json.readTree(mvc().perform(authed(post("/mp/store/ST-NOPE/enter"), buyer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(r.get("code").asInt()).isEqualTo(10404);
    }

    // ---------------------------------------------------------------- helpers

    private MchStore defaultStore(String entityNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getEntityNo, entityNo).eq(MchStore::getIsDefault, true)
                        .last("limit 1")));
    }

    private void rename(MchStore s, String name) {
        s.setName(name);
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> storeMapper.updateById(s));
    }

    private void setCoords(MchStore s, Integer lat, Integer lng) {
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                storeMapper.update(null, Wrappers.<MchStore>lambdaUpdate()
                        .eq(MchStore::getId, s.getId())
                        .set(MchStore::getLatE6, lat).set(MchStore::getLngE6, lng)));
    }

    private MchStore addStore(String entityNo, String storeNo, String name, String status, Integer lat, Integer lng) {
        MchStore s = new MchStore();
        s.setEntityNo(entityNo);
        s.setStoreNo(storeNo);
        s.setName(name);
        s.setIsDefault(false);
        s.setStatus(status);
        s.setLatE6(lat);
        s.setLngE6(lng);
        s.setTenantNo("MAIN");
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> storeMapper.insert(s));
        return s;
    }

    private void paidSubOrder(String userNo, String entityNo, String storeNo, String status) {
        OrdSubOrder o = new OrdSubOrder();
        String no = "SO-MYS-" + System.nanoTime();
        o.setSubOrderNo(no);
        o.setOrderNo("O" + no);
        o.setUserNo(userNo);
        o.setEntityNo(entityNo);
        o.setStoreNo(storeNo);
        o.setStatus(status);
        o.setTenantNo("MAIN");
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> subOrderMapper.insert(o));
    }

    private UsrStoreView view(String userNo, String storeNo) {
        return viewMapper.selectOne(Wrappers.<UsrStoreView>lambdaQuery()
                .eq(UsrStoreView::getUserNo, userNo).eq(UsrStoreView::getStoreNo, storeNo));
    }

    private static List<String> storeNos(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.get("storeNo").asString()));
        return out;
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b, String token) {
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }

    private void call(MockHttpServletRequestBuilder b, String token, String body) throws Exception {
        mvc().perform(authed(b, token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0));
    }

    private JsonNode data(MockHttpServletRequestBuilder b, String token) throws Exception {
        String body = mvc().perform(authed(b, token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private String userNoOf(String token) throws Exception {
        return data(get("/mp/user/profile"), token).get("userNo").asString();
    }

    private String approvedMerchantNo(String phone, String name, String communityNo) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"生鲜\",\"desc\":\"社区生鲜店\","
                                + "\"serviceScope\":\"COMMUNITY\",\"communityNos\":[\"" + communityNo + "\"],"
                                + "\"licenses\":[\"https://cdn/l.jpg\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        String bd = TestLogin.operator(mvc(), json, "bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return data(get("/mp/merchant/apply"), user).get("merchantNo").asString();
    }
}
