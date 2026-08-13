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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 一期主数据收敛（{@code docs/technical/TDD-一期主数据收敛.md}，迁移 V22）。
 *
 * <p>一期按<b>自营模式</b>上线小程序（ADR-012 B 方案）：平台是销售者、商家是供应商，
 * 于是商家能选的行业、能覆盖的范围、能上架的类目<b>都不能超出平台自己营业执照的经营范围</b>。
 * 超出一件，违法的是平台。
 *
 * <p>这组用例守两件事：
 * <ol>
 *   <li><b>收敛真的生效</b> —— 端上拿不到超范围的取值，也写不进超范围的值</li>
 *   <li><b>收敛是可逆的</b> —— 停用的行仍在库里、仍对运营可见，
 *       拿到 EDI 切平台模式时在后台放开即可，不用改代码</li>
 * </ol>
 * 第二件同样重要：删掉而不是停用的话，切回去要重建全部数据，
 * 而存量商家的 {@code industry} 会指向不存在的行。
 */
@SpringBootTest
@ActiveProfiles("test")
class Phase1MasterDataTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;


    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 门店分类（行业）

    @Test
    @DisplayName("★ 入驻可选行业只剩执照能覆盖的两个 —— 端上下发的是启用集，不是全集")
    void onlyLicensedIndustriesAreOfferedToMerchants() throws Exception {
        String body = mvc().perform(get("/common/master-data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        List<String> codes = new ArrayList<>();
        for (JsonNode i : json.readTree(body).get("data").get("industries")) {
            codes.add(i.get("industry").asString());
        }
        // 餐饮/休闲娱乐/交通/线上/其他都超出平台执照范围（V22）。
        // 「其他」尤其不能留：它等于「平台不知道自己在卖什么」，
        // 留着的话运营图省事一律选它，上面所有准入判断就被整个绕过去了
        assertThat(codes).containsExactlyInAnyOrder("RETAIL", "LIFE_SERVICE");
    }

    @Test
    @DisplayName("★ 停用的行业仍对运营可见 —— 全量 + 开关，切平台模式时不用改代码")
    void disabledIndustriesStayVisibleToOps() throws Exception {
        String ops = opsLogin("admin", "admin123");
        String body = mvc().perform(get("/ops/industries").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode catering = null;
        for (JsonNode i : json.readTree(body).get("data")) {
            if ("CATERING".equals(i.get("industry").asString())) {
                catering = i;
            }
        }
        assertThat(catering).as("停用不是删除：行必须还在，否则切回平台模式要重建数据").isNotNull();
        assertThat(catering.get("enabled").asBoolean()).isFalse();
        // 停用理由要留在 remark 里 —— 三个月后要放开时，看这一行就知道当初卡在哪
        assertThat(catering.get("remark").asString()).isNotBlank();
    }

    // ---------------------------------------------------------------- 经营范围

    @Test
    @DisplayName("★ 非法的经营范围写不进库 —— 此前是「传什么存什么」，传 ABC 也能存")
    void illegalServiceScopeIsRejected() throws Exception {
        String token = merchant("12600171001", "范围测试·非法值");

        String body = saveStore(token, "ABC");
        // 值域是代码的事实，运营在后台放开档位也不该顺带获得「写入任意字符串」的能力
        assertThat(json.readTree(body).get("code").asInt()).isEqualTo(ai.neargo.shop.common.ErrorCode.SERVICE_SCOPE_NOT_ALLOWED.code());
    }

    @Test
    @DisplayName("★ 一期未开放的 PLATFORM 档被拒，COMMUNITY 照常通过")
    void platformScopeIsClosedInPhase1() throws Exception {
        String token = merchant("12600171002", "范围测试·档位");

        // PLATFORM 是合法值，但一期没有任何商品形态支撑它
        //（无虚拟商品、无卡券、无平台自营快递品）
        assertThat(json.readTree(saveStore(token, "PLATFORM")).get("code").asInt()).isEqualTo(ai.neargo.shop.common.ErrorCode.SERVICE_SCOPE_NOT_ALLOWED.code());
        assertThat(json.readTree(saveStore(token, "COMMUNITY")).get("code").asInt()).isZero();
    }

    // ---------------------------------------------------------------- 商品分类

    @Test
    @DisplayName("★ 家政不再被维修资质卡住 —— 一级类目上挂 required_code 是 V22 修掉的 D2")
    void housekeepingIsNotGatedByRepairQualification() throws Exception {
        String ops = opsLogin("goods", "goods123");
        JsonNode rows = listCategories(ops);

        // 一级「生活服务」不该有门槛：资质是按最细的经营范围批的，挂在一级会牵连整棵子树。
        // 挂着的话，一期唯一要上的服务品类（家政）会被一张**它不需要的**维修资质挡住，
        // 而商家看到的只是「你还没有资质授权」
        JsonNode services = findFlat(rows, "CAT300");
        assertThat(services).isNotNull();
        assertThat(services.get("requiredCode").isNull()).isTrue();

        JsonNode housekeeping = findFlat(rows, "CAT310");
        assertThat(housekeeping).isNotNull();
        assertThat(housekeeping.get("requiredCode").asString()).isEqualTo("HOUSEKEEPING");
    }

    @Test
    @DisplayName("★ 授了 HOUSEKEEPING 就能上架家政商品；授之前被拒")
    void housekeepingGateOpensWithItsOwnCode() throws Exception {
        String token = merchant("12600171003", "类目测试·家政");
        String merchantNo = merchantNoOf(token);
        String goodsNo = saveGoods(token, "日常保洁 2 小时", "CAT310", "SERVICE");
        approveGoods(goodsNo);

        assertThat(json.readTree(toggleOnSale(token, goodsNo)).get("code").asInt()).isEqualTo(70002);

        authorize(merchantNo, "HOUSEKEEPING", "已核验营业执照含家政服务");
        assertThat(json.readTree(toggleOnSale(token, goodsNo)).get("code").asInt()).isZero();
    }

    @Test
    @DisplayName("★ 预包装食品是新门槛：没有 PACKAGED_FOOD 上不了架")
    void packagedFoodNeedsItsOwnAuthorization() throws Exception {
        String token = merchant("12600171004", "类目测试·粮油");
        String goodsNo = saveGoods(token, "菜籽油 5L", "CAT131", "NORMAL");
        approveGoods(goodsNo);

        assertThat(json.readTree(toggleOnSale(token, goodsNo)).get("code").asInt()).isEqualTo(70002);
    }

    @Test
    @DisplayName("★ 资质只挂叶子节点 —— 挂在中间层会连坐整棵子树（约定见 TDD §0）")
    void requiredCodeOnlyOnLeafNodes() throws Exception {
        String ops = opsLogin("goods", "goods123");
        JsonNode rows = listCategories(ops);

        List<String> offenders = new ArrayList<>();
        for (JsonNode row : rows) {
            if (row.get("requiredCode") == null || row.get("requiredCode").isNull()) {
                continue;
            }
            String no = row.get("categoryNo").asString();
            for (JsonNode other : rows) {
                JsonNode parent = other.get("parentNo");
                if (parent != null && !parent.isNull() && no.equals(parent.asString())) {
                    offenders.add(no);
                    break;
                }
            }
        }
        assertThat(offenders).as("这些类目挂了资质码却还有子类目").isEmpty();
    }

    @Test
    @DisplayName("★ 停用的类目不出现在端上的树里，但运营仍看得到")
    void inactiveCategoryLeavesTheTreeButStaysInOps() throws Exception {
        // 卡券一期停用：执照无预付卡相关项，且储值会把负债直接记到平台账上
        assertThat(find(tree(), "CAT400")).as("停用的类目还在 C 端树里的话，用户点进去是空列表").isNull();

        String ops = opsLogin("goods", "goods123");
        String body = mvc().perform(get("/ops/categories")
                        .param("showArchived", "true")
                        .header("Authorization", "Bearer " + ops))
                .andReturn().getResponse().getContentAsString();
        assertThat(findFlat(json.readTree(body).get("data").get("records"), "CAT400"))
                .as("库里那一行要留着 —— 切平台模式后在运营端恢复即可").isNotNull();
    }

    @Test
    @DisplayName("★ 端上的类目树里没有任何商品指向停用节点（迁移顺序：先改指、再停用）")
    void noGoodsLeftOnInactiveCategory() throws Exception {
        String ops = opsLogin("goods", "goods123");
        JsonNode rows = listCategories(ops);

        // listCategories 默认不含已停用的，所以「树里能查到」等价于「这个类目可用」。
        // 反过来说：只要 CAT400 不在这份列表里，就没有可用类目引用它 ——
        // 演示商品在 V22 里已经从 CAT300 改指到 CAT310
        assertThat(findFlat(rows, "CAT400")).isNull();
        assertThat(findFlat(rows, "CAT310")).isNotNull();
    }

    // ---------------------------------------------------------------- 运营维护面（阶段二）

    @Test
    @DisplayName("★ 授权码列表对运营是全量：停用的也在，且带影响面计数")
    void opsSeesAllAuthCodesWithImpact() throws Exception {
        String ops = opsLogin("goods", "goods123");
        JsonNode rows = json.readTree(mvc().perform(get("/ops/auth-codes")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString()).get("data");

        JsonNode repair = findByCode(rows, "SERVICE_REPAIR");
        assertThat(repair).as("停用不是删除：运营要能看见并恢复它").isNotNull();
        assertThat(repair.get("enabled").asBoolean()).isFalse();
        // 不带计数的开关是盲操作 —— 停一个 300 家店在用的码，和停一个没人用的，是两件事
        assertThat(repair.get("merchantCount").isNumber()).isTrue();
        assertThat(repair.get("categoryCount").isNumber()).isTrue();

        // 发证时的可选项是另一个口径：**绝不能把停用的码发出去**
        JsonNode forGranting = json.readTree(mvc().perform(get("/ops/merchants/auth-codes")
                        .header("Authorization", "Bearer " + opsLogin("bd", "bd123")))
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(findByCode(forGranting, "SERVICE_REPAIR")).isNull();
    }

    @Test
    @DisplayName("★ 仍有类目引用时不许停用 —— 否则那些类目会永远拒绝所有人")
    void cannotDisableAuthCodeStillReferenced() throws Exception {
        String ops = opsLogin("goods", "goods123");
        // HOUSEKEEPING 正被 CAT310 引用
        String body = mvc().perform(post("/ops/auth-codes/HOUSEKEEPING/enabled")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"reason\":\"试着停一下\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("code").asInt()).isEqualTo(80002);
    }

    @Test
    @DisplayName("★ 停用要写理由 —— 它决定一批商家还能不能上新品")
    void disablingAuthCodeNeedsReason() throws Exception {
        String ops = opsLogin("goods", "goods123");
        String body = mvc().perform(post("/ops/auth-codes/DAILY/enabled")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"reason\":\"  \"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("code").asInt()).isEqualTo(10400);
    }

    @Test
    @DisplayName("★ 运营新建授权码即刻可发放 —— 一期之后放开类目不该再等发版")
    void opsCanCreateAuthCode() throws Exception {
        String ops = opsLogin("goods", "goods123");
        mvc().perform(post("/ops/auth-codes")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"FRESH_MEAT\",\"name\":\"鲜肉\","
                                + "\"requiredQualification\":\"食品经营许可证\",\"sort\":35}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(true));

        // 新建即启用，所以它立刻出现在「发证可选项」里，不需要再点一次启用
        JsonNode forGranting = json.readTree(mvc().perform(get("/ops/merchants/auth-codes")
                        .header("Authorization", "Bearer " + opsLogin("bd", "bd123")))
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(findByCode(forGranting, "FRESH_MEAT")).isNotNull();
    }

    @Test
    @DisplayName("★ 经营范围三档全量可见，PLATFORM 一期是关的")
    void opsSeesAllServiceScopes() throws Exception {
        String ops = opsLogin("admin", "admin123");
        JsonNode rows = json.readTree(mvc().perform(get("/ops/service-scopes")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString()).get("data");

        assertThat(rows).hasSize(3);
        assertThat(scopeOf(rows, "PLATFORM").get("enabled").asBoolean()).isFalse();
        assertThat(scopeOf(rows, "COMMUNITY").get("enabled").asBoolean()).isTrue();
        assertThat(scopeOf(rows, "COMMUNITY").get("merchantCount").isNumber()).isTrue();
    }

    @Test
    @DisplayName("★ 在后台放开 PLATFORM 之后，商家立刻就能选它 —— 切平台模式不用发版")
    void openingScopeInOpsTakesEffectImmediately() throws Exception {
        String token = merchant("12600171005", "范围测试·后台放开");
        String ops = opsLogin("admin", "admin123");

        assertThat(json.readTree(saveStore(token, "PLATFORM")).get("code").asInt()).isEqualTo(ai.neargo.shop.common.ErrorCode.SERVICE_SCOPE_NOT_ALLOWED.code());

        mvc().perform(post("/ops/service-scopes/PLATFORM/enabled")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"reason\":\"EDI 已下证，切平台模式\"}"))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(json.readTree(saveStore(token, "PLATFORM")).get("code").asInt()).isZero();

        // 关回去，免得影响别的用例（平台参数是全局的）
        mvc().perform(post("/ops/service-scopes/PLATFORM/enabled")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"reason\":\"用例复原\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("★ 不许把经营范围全关 —— 全关等于所有商家都保存不了门店")
    void cannotCloseEveryServiceScope() throws Exception {
        String ops = opsLogin("admin", "admin123");
        mvc().perform(post("/ops/service-scopes/CITY/enabled")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"reason\":\"先关一档\"}"))
                .andExpect(jsonPath("$.code").value(0));

        String body = mvc().perform(post("/ops/service-scopes/COMMUNITY/enabled")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"reason\":\"再关最后一档\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("code").asInt()).isEqualTo(10400);

        mvc().perform(post("/ops/service-scopes/CITY/enabled")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"reason\":\"用例复原\"}"));
    }

    @Test
    @DisplayName("商品运营改不了经营范围 —— 那是主数据，与行业同级")
    void goodsOpsCannotTouchServiceScopes() throws Exception {
        String goodsOps = opsLogin("goods", "goods123");
        mvc().perform(get("/ops/service-scopes").header("Authorization", "Bearer " + goodsOps))
                .andExpect(jsonPath("$.code").value(10403));
    }

    @Test
    @DisplayName("BD 改不了授权码字典 —— 他只负责发证，不负责定义有哪些证")
    void bdCannotEditAuthCodeDictionary() throws Exception {
        String bd = opsLogin("bd", "bd123");
        /*
         * 分开的理由：BD 遇到一家没资质的店，若能直接把那个码的资质要求删掉，
         * 这一改影响的是全平台所有商家，而审计里看起来只是一次「改了个字典」。
         */
        mvc().perform(get("/ops/auth-codes").header("Authorization", "Bearer " + bd))
                .andExpect(jsonPath("$.code").value(10403));
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode findByCode(JsonNode rows, String code) {
        for (JsonNode n : rows) {
            if (code.equals(n.get("code").asString())) {
                return n;
            }
        }
        return null;
    }

    private JsonNode scopeOf(JsonNode rows, String scope) {
        for (JsonNode n : rows) {
            if (scope.equals(n.get("scope").asString())) {
                return n;
            }
        }
        throw new AssertionError("没有这一档：" + scope);
    }

    private String saveStore(String token, String scope) throws Exception {
        return mvc().perform(post("/biz/store").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"announcement\":\"营业中\",\"openHours\":\"08:00-20:00\","
                                + "\"address\":\"文一西路 1 号\",\"featured\":[],"
                                + "\"serviceScope\":\"" + scope + "\","
                                + "\"serviceCommunityNos\":[\"CM001\"]}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String toggleOnSale(String token, String goodsNo) throws Exception {
        return mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andReturn().getResponse().getContentAsString();
    }

    private void authorize(String merchantNo, String code, String reason) throws Exception {
        String bd = opsLogin("bd", "bd123");
        mvc().perform(put("/ops/merchants/" + merchantNo + "/auth-codes")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codes\":[\"" + code + "\"],\"reason\":\"" + reason + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private JsonNode tree() throws Exception {
        String body = mvc().perform(get("/mp/category/tree"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private JsonNode listCategories(String opsToken) throws Exception {
        String body = mvc().perform(get("/ops/categories")
                        .param("size", "200")
                        .header("Authorization", "Bearer " + opsToken))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records");
    }

    private JsonNode find(JsonNode nodes, String categoryNo) {
        if (nodes == null) {
            return null;
        }
        for (JsonNode n : nodes) {
            if (categoryNo.equals(n.get("categoryNo").asString())) {
                return n;
            }
            JsonNode hit = find(n.get("children"), categoryNo);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private JsonNode findFlat(JsonNode rows, String categoryNo) {
        for (JsonNode n : rows) {
            if (categoryNo.equals(n.get("categoryNo").asString())) {
                return n;
            }
        }
        return null;
    }

    private String saveGoods(String token, String title, String categoryNo, String type) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"subtitle\":\"测试\",\"type\":\"" + type + "\","
                                + "\"categoryNo\":\"" + categoryNo + "\",\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("goodsNo").asString();
    }

    private void approveGoods(String goodsNo) throws Exception {
        String goodsOps = opsLogin("goods", "goods123");
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                        .header("Authorization", "Bearer " + goodsOps)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private String merchantNoOf(String token) throws Exception {
        String body = mvc().perform(get("/biz/context").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
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

        String bd = opsLogin("bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
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

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
