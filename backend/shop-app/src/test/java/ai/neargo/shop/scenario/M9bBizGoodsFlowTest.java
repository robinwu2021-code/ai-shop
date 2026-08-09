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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M9b B 端商品管理。
 *
 * <p>这一组测的不是"接口通不通"，而是<b>三条能被绕过的规则</b>：
 * 未过审能不能自己上架、改完商品要不要重审、别家的商品能不能碰。
 * 三条都是"漏掉也不会报错，只是审核形同虚设"的类型。
 */
@SpringBootTest
@ActiveProfiles("test")
class M9bBizGoodsFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.user.service.OtpStore otpStore;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 新建商品落到待审核且不在售 —— 录完就能卖等于没有审核")
    void newGoodsStartsAuditing() throws Exception {
        String token = merchant("12600127001", "商品测试店A");

        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goodsBody(null, "手工辣椒酱", 1580, 20)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("AUDITING"))
                .andExpect(jsonPath("$.data.onSale").value(false))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();

        // ★ 未过审时自己按上架必须被拒。这是商家自己能点的按钮，
        // 能把 AUDITING 推到 C 端的话，审核这道关就不存在了
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("★ 改动后回到待审核 —— 否则「改成别的东西再卖」能绕开审核")
    void editSendsBackToAudit() throws Exception {
        String token = merchant("12600127002", "商品测试店B");
        String goodsNo = createAndApprove(token, "白菜");

        // 过审后确认能上架
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"));

        // ★ 改标题 → 回到 AUDITING 且强制下架
        mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goodsBody(goodsNo, "进口红酒", 39900, 5)))
                .andExpect(jsonPath("$.data.status").value("AUDITING"))
                .andExpect(jsonPath("$.data.onSale").value(false));
    }

    @Test
    @DisplayName("★ 补货不触发重审 —— 每天都在做的事不该每次都重新过审")
    void restockKeepsApproval() throws Exception {
        String token = merchant("12600127003", "商品测试店C");
        String goodsNo = createAndApprove(token, "土鸡蛋");
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));

        String skuNo = json.readTree(mvc().perform(get("/biz/goods/" + goodsNo)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("skus").get(0).get("skuNo").asString();

        mvc().perform(post("/biz/goods/" + goodsNo + "/stock")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuNo\":\"" + skuNo + "\",\"stock\":99}"))
                .andExpect(jsonPath("$.code").value(0))
                // 仍在售、仍过审 —— 补个货把商品下架了，商家会以为系统坏了
                .andExpect(jsonPath("$.data.status").value("ON_SALE"))
                .andExpect(jsonPath("$.data.skus[0].stock").value(99));
    }

    @Test
    @DisplayName("★ 碰不到别家的商品，且按 404 而不是 403（403 等于确认这个编号存在）")
    void cannotTouchOthersGoods() throws Exception {
        String a = merchant("12600127004", "商品测试店D");
        String b = merchant("12600127005", "商品测试店E");
        String goodsNo = createAndApprove(a, "A 家的商品");

        mvc().perform(get("/biz/goods/" + goodsNo).header("Authorization", "Bearer " + b))
                .andExpect(jsonPath("$.code").value(10404));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + b)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":false}"))
                .andExpect(jsonPath("$.code").value(10404));
    }

    @Test
    @DisplayName("列表按状态筛选，且默认包含下架与审核中（看不到就不知道要改什么）")
    void listIncludesNonSellable() throws Exception {
        String token = merchant("12600127006", "商品测试店F");
        createGoods(token, "只录不上架");

        mvc().perform(get("/biz/goods").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()")
                        .value(org.hamcrest.Matchers.greaterThan(0)));
        mvc().perform(get("/biz/goods").header("Authorization", "Bearer " + token)
                        .param("status", "AUDITING"))
                .andExpect(jsonPath("$.data.records[0].status").value("AUDITING"));
        // 未知筛选值不该让列表变空 —— 那看着像"一件商品都没有"
        mvc().perform(get("/biz/goods").header("Authorization", "Bearer " + token)
                        .param("status", "WHATEVER"))
                .andExpect(jsonPath("$.data.records.length()")
                        .value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    @DisplayName("规格模板：存成自己的，别家看不到")
    void specTemplateIsPrivate() throws Exception {
        String a = merchant("12600127007", "商品测试店G");
        String b = merchant("12600127008", "商品测试店H");

        mvc().perform(post("/biz/spec-templates").header("Authorization", "Bearer " + a)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"辣度\",\"options\":[{\"label\":\"微辣\"},{\"label\":\"特辣\"}]}"))
                .andExpect(jsonPath("$.code").value(0))
                // 商家存的一律是 MERCHANT —— 平台模板是跨店可比的基础，改不得
                .andExpect(jsonPath("$.data.scope").value("MERCHANT"));

        mvc().perform(get("/biz/spec-templates").header("Authorization", "Bearer " + a))
                .andExpect(jsonPath("$.data[?(@.name=='辣度')]").exists());
        mvc().perform(get("/biz/spec-templates").header("Authorization", "Bearer " + b))
                .andExpect(jsonPath("$.data[?(@.name=='辣度')]").doesNotExist());
    }

    @Test
    @DisplayName("拍照识别一期恒返回「没认出来」—— 前端据此降级为手填，而不是预填一个瞎猜")
    void recognizeDegradesHonestly() throws Exception {
        String token = merchant("12600127009", "商品测试店I");
        mvc().perform(post("/biz/goods/recognize").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\":\"https://cdn/x.jpg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confidence").value(0.0));
    }

    /**
     * <b>本组最值得测的一条。</b>
     *
     * <p>先前 toggle 只改 {@code on_sale}，而 C 端按社区查商品读的是
     * {@code prd_community_pool} —— 于是商家点了上架、B 端列表显示"在售"，
     * 买家<b>在任何地方都搜不到这件货</b>，且没有任何报错。
     *
     * <p>这与入驻那个「审核通过但商家对谁都不可见」是同一个形状的故障：
     * 一个状态位改了，而真正决定可见性的那张关联表没跟着动。
     * 所以这里不验接口返回，验的是<b>买家真的搜得到</b>。
     */
    @Test
    @DisplayName("★ 上架后 C 端真的搜得到；下架后立刻搜不到")
    void onSaleGoodsIsVisibleToBuyers() throws Exception {
        String token = merchant("12600127010", "商品可见性店");
        String goodsNo = createAndApprove(token, "会飞的扫帚");

        // 上架前：不该出现在买家的社区列表里
        mvc().perform(get("/mp/goods").param("communityNo", "CM001").param("size", "50"))
                .andExpect(jsonPath("$.data.records[?(@.title=='会飞的扫帚')]").doesNotExist());

        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"));

        // ★ 上架后必须搜得到 —— 只断言接口 200 的话，这个缺口会原样漏过去
        mvc().perform(get("/mp/goods").param("communityNo", "CM001").param("size", "50"))
                .andExpect(jsonPath("$.data.records[?(@.title=='会飞的扫帚')]").exists());

        // 下架后立刻消失：留在池里的话买家还能搜到，点进去才发现买不了
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":false}"));
        mvc().perform(get("/mp/goods").param("communityNo", "CM001").param("size", "50"))
                .andExpect(jsonPath("$.data.records[?(@.title=='会飞的扫帚')]").doesNotExist());
    }

    /**
     * M2 双写：下单时 {@code store_no} 与 {@code entity_no} 都要落库。
     *
     * <p>单店时两者恒等，所以这条测试**验的不是值对不对，是那一列没被漏写** ——
     * 漏写不会有任何症状（履约侧按空兜底回默认门店），一直到多门店放开那天，
     * 才发现历史订单全都不知道属于哪家店，而那时已经补不回来了。
     */
    @Test
    @DisplayName("★ 下单双写门店：merchant_no 是结算键，store_no 是履约键")
    void orderCarriesStoreNo() throws Exception {
        String token = merchant("12600127011", "双写测试店");
        String goodsNo = createAndApprove(token, "双写测试商品");
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));

        // 该主体的默认门店
        String merchantNo = json.readTree(mvc().perform(get("/biz/merchant/profile")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("merchantNo").asString();
        var store = storeMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.user.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.user.merchant.entity.MchStore::getEntityNo, merchantNo)
                        .eq(ai.neargo.shop.user.merchant.entity.MchStore::getIsDefault, true));
        assertThat(store).as("入驻通过必须建出默认门店").isNotNull();

        String buyer = login("12600127012");
        String orderNo = placeOrder(buyer, goodsNo);

        var sub = subOrderMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.trade.entity.OrdSubOrder>lambdaQuery()
                        .eq(ai.neargo.shop.trade.entity.OrdSubOrder::getOrderNo, orderNo)
                        .last("limit 1"));
        assertThat(sub.getEntityNo()).as("结算键").isEqualTo(merchantNo);
        assertThat(sub.getStoreNo()).as("履约键 —— 漏写不会有任何症状，直到多门店放开那天")
                .isEqualTo(store.getStoreNo());
    }

    @Test
    @DisplayName("★ 订单按当前门店隔离 —— 建了三家店，单不能混在一起")
    void ordersAreScopedToCurrentStore() throws Exception {
        String token = merchant("12600127021", "多店订单测试");
        String goodsNo = createAndApprove(token, "多店测试商品");
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));
        placeOrder(login("12600127022"), goodsNo);

        // 默认店（下单落的就是它）
        String defaultStore = json.readTree(mvc().perform(get("/biz/context")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("currentStoreNo").asString();
        assertThat(defaultStore).isNotBlank();

        // 再开一家分店
        String second = json.readTree(mvc().perform(post("/biz/store/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"文三路分店\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("storeNo").asString();

        // 默认店有单
        mvc().perform(get("/biz/order").header("Authorization", "Bearer " + token)
                        .header("X-Store-No", defaultStore))
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThan(0)));

        /*
         * ★ 新分店应当是 0 单。
         * 没有门店维度之前这里会返回默认店的单 —— 而那正是「建了三家店，
         * 订单还是混在一起」的样子：数字是真的，只是不是这家店的。
         */
        mvc().perform(get("/biz/order").header("Authorization", "Bearer " + token)
                        .header("X-Store-No", second))
                .andExpect(jsonPath("$.data.total").value(0));

        // 老板要跨店汇总时显式要：allStores=true
        mvc().perform(get("/biz/order").header("Authorization", "Bearer " + token)
                        .header("X-Store-No", second).param("allStores", "true"))
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    @DisplayName("★ 「全部门店」对店员 = 他被授权的那几家，不是主体全部")
    void allStoresIsScopedToGrantedStoresForStaff() throws Exception {
        String token = merchant("12600127031", "全部门店语义测试");
        String goodsNo = createAndApprove(token, "语义测试商品");
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));
        placeOrder(login("12600127032"), goodsNo);

        // 开一家分店，把店员只授权到分店
        String branch = json.readTree(mvc().perform(post("/biz/store/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"语义分店\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("storeNo").asString();
        String staffPhone = "12600127033";
        String accountNo = json.readTree(mvc().perform(post("/biz/staff")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginPhone\":\"" + staffPhone + "\"}"))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("mchAccountNo").asString();
        mvc().perform(post("/biz/staff/" + accountNo + "/store")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeNo\":\"" + branch + "\",\"role\":\"CLERK\"}"));

        String staffToken = staffLogin(staffPhone);

        /*
         * ★ 这条是 E2E（J2）先发现的越权：`allStores=true` 曾经等于「不按门店过滤」，
         * 于是只被授权到分店的店员，点一下「全部门店」就看到了主体名下所有店的单。
         * 它不报错，只会安静地多看到一些东西 —— 所以必须由一条用例钉住。
         */
        mvc().perform(get("/biz/order").header("Authorization", "Bearer " + staffToken)
                        .param("allStores", "true"))
                .andExpect(jsonPath("$.data.total").value(0));

        // 老板的「全部门店」仍然是主体全部
        mvc().perform(get("/biz/order").header("Authorization", "Bearer " + token)
                        .param("allStores", "true"))
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    /** 员工登录：走商家账号那条路，不建 C 端账号 */
    private String staffLogin(String phone) throws Exception {
        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        String body = mvc().perform(post("/biz/auth/staff-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    @Test
    @DisplayName("越权门店号不认 —— 回落默认店，不能查出别家的单")
    void foreignStoreHeaderIsIgnored() throws Exception {
        String token = merchant("12600127023", "越权测试店A");
        String other = merchant("12600127024", "越权测试店B");
        String otherStore = json.readTree(mvc().perform(get("/biz/context")
                        .header("Authorization", "Bearer " + other))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("currentStoreNo").asString();

        // 拿 B 店的门店号去查 A 的订单：**不认**，按 A 自己的默认店处理
        String body = mvc().perform(get("/biz/context").header("Authorization", "Bearer " + token)
                        .header("X-Store-No", otherStore))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("data").get("currentStoreNo").asString())
                .as("越权门店号必须被丢弃")
                .isNotEqualTo(otherStore);
    }

    @Autowired
    private ai.neargo.shop.user.mapper.UserMappers.MchStoreMapper storeMapper;

    @Autowired
    private ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper subOrderMapper;

    /** 下单，返回主单号。 */
    private String placeOrder(String buyerToken, String goodsNo) throws Exception {
        String detail = mvc().perform(get("/mp/goods/" + goodsNo))
                .andReturn().getResponse().getContentAsString();
        String skuNo = json.readTree(detail).get("data").get("skus").get(0).get("skuNo").asString();

        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\","
                                + "\"items\":[{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo
                                + "\",\"qty\":1}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("orderNo").asString();
    }

    // ---------------------------------------------------------------- helpers

    private String goodsBody(String goodsNo, String title, long price, int stock) {
        return "{" + (goodsNo == null ? "" : "\"goodsNo\":\"" + goodsNo + "\",")
                + "\"title\":\"" + title + "\",\"subtitle\":\"测试\",\"type\":\"NORMAL\","
                + "\"cover\":\"🥫\",\"images\":[],\"specGroups\":[],"
                + "\"skus\":[{\"optionValues\":[],\"price\":" + price + ",\"stock\":" + stock + "}]}";
    }

    private String createGoods(String token, String title) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goodsBody(null, title, 1000, 10)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("goodsNo").asString();
    }

    /** 录一件商品并让平台审过 —— 上架的前置。 */
    private String createAndApprove(String token, String title) throws Exception {
        String goodsNo = createGoods(token, title);
        String ops = opsLogin("goods", "goods123");
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit").header("Authorization", "Bearer " + ops)
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        return goodsNo;
    }

    /** 走完「入驻 → 通过 → 重新登录」，返回可用于 /biz/** 的 token。 */
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

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
