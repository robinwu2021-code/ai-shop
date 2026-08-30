package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestPlan;
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
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.SkuMapper skuMapper;

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper goodsMapper;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper planMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★ 闸门关着时（生产默认）缺资质照样能上架 —— 只展示、不限制")
    void listingIsNotBlockedWhileGateIsOff() throws Exception {
        /*
         * 这一条守的是**默认值本身**：`shop.category.gate.enforce` 默认 false，
         * 于是缺授权码的商品照样上得了架（判据仍在跑、命中打 WARN，见
         * MerchantGoodsServiceImpl#requireCategoryAuthorized）。
         *
         * <p>为什么值得单独测：受理入口铺开之前，这是线上 267 件商品能不能卖的开关。
         * 有人把默认值改回 true 的话，症状不是报错而是**商家突然全线上不了架** ——
         * 而那时没人会想到是一行配置。CategoryTreeFlowTest 测的是相反那一半（开着时拦得对）。
         */
        String token = merchant("12600199001", "闸门关着·菜摊");
        // CAT110 蔬菜挂着 FRESH_VEG 门槛，而这家新店一个授权码都没有
        String body0 = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT110\",\"title\":\"青菜一把（闸门关）\","
                                + "\"subtitle\":\"测试\",\"cover\":\"🥬\",\"images\":[],"
                                + "\"specGroups\":[],\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":9}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body0).get("data").get("goodsNo").asString();
        approveGoods(goodsNo);

        String body = mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("code").asInt())
                .as("闸门关着时缺资质不该拦 —— 拦了就是把 267 件商品挡在架下")
                .isZero();
    }

    @Test
    @DisplayName("★★ 商家手打的规格要能归到库里：别名与写法差异都得认")
    void handTypedSpecStillLandsOnTheLibrary() throws Exception {
        /*
         * 守的是「跨店可比」那条链的最后一环：SKU 落库时要盖上 option_value_nos。
         * 盖不上的话三家店的同一档规格永远聚不到一起 —— 比不了价、排不了序，
         * 规格库辛苦维护的档位与别名全部白费。
         *
         * <p>两个用例都是**商家真会那么打**的写法，而且各自只有一条路能认出来：
         * <ul>
         *   <li>{@code 10斤} —— 正式标签是「5kg」，「10斤」只存在于**别名**里
         *       （既不是任何值的标签，也不是任何类目的改名，我核过全库）。
         *       此前 resolveValueNos 压根不查别名那一列，于是这一档必然归不了一。
         *       而线上真有商家这么写：10斤、20斤、5斤、3斤 —— 生鲜按斤卖是常态。</li>
         *   <li>{@code 500 ML} —— 库里是「500ml」。中间一个空格、单位大写，
         *       此前直接 `label.trim()` 比对，差一个字符就分家。</li>
         *   <li>{@code 20片} —— 计件那一类。正式标签是「20件装」，商家按量词写。
         *       库里原先连 20 这一档都没有（只到 12），见 V223。</li>
         * </ul>
         *
         * <p><b>别拿「单个」这类做用例</b>：CAT280/CAT740 把 C1 改名叫「单个」，
         * 而类目改名那一轮只按维度查、不按类目筛，于是它从改名那条路也能解出来 ——
         * 别名坏了测试照样绿。我第一版就踩了这个，特此记下。
         *
         * <p>线上实测：394 个 SKU 里带 option_value_nos 的是 0 个。
         * 这条测试就是拿来钉住那个 0 不再回来的。
         */
        String token = merchant("12600199077", "手打规格·杂货铺");
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT110\",\"title\":\"手打规格测试\","
                                + "\"subtitle\":\"测试\",\"cover\":\"📦\",\"images\":[],"
                                + "\"specGroups\":["
                                + "{\"name\":\"重量\",\"templateNo\":\"SD_WEIGHT\",\"options\":[\"10斤\"]},"
                                + "{\"name\":\"容量\",\"templateNo\":\"SD_VOLUME\",\"options\":[\"500 ML\"]},"
                                + "{\"name\":\"数量\",\"templateNo\":\"SD_COUNT\",\"options\":[\"20片\"]}],"
                                + "\"skus\":[{\"optionValues\":[\"10斤\",\"500 ML\",\"20片\"],"
                                + "\"price\":500,\"stock\":9}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();

        java.util.List<ai.neargo.shop.product.entity.PrdSku> rows =
                ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                        skuMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<ai.neargo.shop.product.entity.PrdSku>lambdaQuery()
                                .eq(ai.neargo.shop.product.entity.PrdSku::getGoodsNo, goodsNo)));
        assertThat(rows).as("商品存下来了就该有 SKU 行").isNotEmpty();

        String nos = rows.get(0).getOptionValueNos();
        assertThat(nos)
                .as("两个规格都该盖上值编号 —— 空的话这件商品跟别家永远比不了价")
                .isNotNull();
        JsonNode arr = json.readTree(nos);
        assertThat(arr.size()).as("三个规格组就该有三个值编号").isEqualTo(3);
        /*
         * **断到确切编号，不能只断「非空」**。只断非空的话，商家自助建值那条路
         * （ensureValue 会给本店造一个新值）也能让它变绿 —— 而那恰恰是要防的：
         * 每家店各造一个「单个」，跨店比价照样比不了，测试却是绿的。
         * 要的是落到**平台库里那一条**。
         */
        assertThat(arr.get(0).asString())
                .as("「10斤」只在 5kg 的别名里 —— 认不出来就说明别名那一列还是没人查")
                .isEqualTo("SV_WEIGHT_W5KG");
        assertThat(arr.get(1).asString())
                .as("「500 ML」跟库里的「500ml」是同一档 —— 差一个空格、一个大小写都不该分家")
                .isEqualTo("SV_VOLUME_V500ML");
        assertThat(arr.get(2).asString())
                .as("「20片」和别家的「20只」是同一个数 —— 量词不同不该分家")
                .isEqualTo("SV_COUNT_C20");
    }

    @Test
    @DisplayName("★ 草稿不进运营的待审队列 —— 队列里混着半成品，运营分不出哪些真要审")
    void draftStaysOutOfTheQueue() throws Exception {
        String token = merchant("12600127020", "草稿队列测试店");
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goodsBody(null, "只写了一半的货", 800, 5)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();

        String ops = opsLogin();
        assertThat(inQueue(ops, goodsNo)).as("草稿不该出现在队列里").isFalse();

        mvc().perform(post("/biz/goods/" + goodsNo + "/submit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(inQueue(ops, goodsNo)).as("提交之后才进队列").isTrue();
    }

    @Test
    @DisplayName("★★ 改截单不触发重审 —— 走 save 的话改一次截单等于停一天生意")
    void changingCutoffKeepsTheListingLive() throws Exception {
        String token = merchant("12600127021", "截单测试·菜摊");
        // CAT110 蔬菜要 FRESH_VEG，先把码授了，否则卡在上架准入而不是本条要测的东西
        String merchantNo = merchantNoOf(token);
        mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/ops/merchants/" + merchantNo + "/auth-codes")
                        .header("Authorization", "Bearer " + opsLogin("bd", "bd123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codes\":[\"FRESH_VEG\"],\"reason\":\"已核验\"}"))
                .andExpect(jsonPath("$.code").value(0));

        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"小白菜\",\"subtitle\":\"当季\",\"categoryNo\":\"CAT110\","
                                + "\"cover\":\"c.jpg\",\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();
        approveGoods(goodsNo);
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"));

        // ★ 只改截单：仍在售，没有回到待审
        mvc().perform(post("/biz/goods/" + goodsNo + "/presale").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cutoffAt\":1800000000000,\"arrivalDesc\":\"次日 17:00 前到点\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"))
                .andExpect(jsonPath("$.data.cutoffAt").value(1800000000000L));
    }

    private boolean inQueue(String opsToken, String goodsNo) throws Exception {
        String body = mvc().perform(get("/ops/goods/audit-queue?size=100")
                        .header("Authorization", "Bearer " + opsToken))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode g : json.readTree(body).get("data").get("records")) {
            if (goodsNo.equals(g.get("goodsNo").asString())) {
                return true;
            }
        }
        return false;
    }

    /** 按状态取商家自己的商品列表 */
    private JsonNode records(String token, String status) throws Exception {
        String body = mvc().perform(get("/biz/goods").header("Authorization", "Bearer " + token)
                        .param("status", status))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records");
    }

    @Test
    @DisplayName("★★ 新建落草稿、显式提交才进队列 —— 保存即提审会让队列里全是半成品")
    void newGoodsStartsAsDraft() throws Exception {
        String token = merchant("12600127001", "商品测试店A");

        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goodsBody(null, "手工辣椒酱", 1580, 20)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                // ★ 草稿，不是待审：填一半点保存不该进运营的队列，
                // 而商家看到「审核中」会以为自己在等结论
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.onSale").value(false))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();

        // 显式提交才进待审
        mvc().perform(post("/biz/goods/" + goodsNo + "/submit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        // 重复提交无副作用 —— 端上重复点击是常态，报错只会让他以为提交失败
        mvc().perform(post("/biz/goods/" + goodsNo + "/submit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        // ★ 未过审时自己按上架必须被拒。这是商家自己能点的按钮，
        // 能把待审商品推到 C 端的话，审核这道关就不存在了
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

        // ★ 改标题 → 回到待审（PENDING）且强制下架
        mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goodsBody(goodsNo, "进口红酒", 39900, 5)))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.onSale").value(false));
    }

    /**
     * <b>「不传 = 不改」与「传空数组 = 清空」必须分开。</b>
     *
     * <p>这里原先是无条件覆盖，而 {@code writeJson(null)} 返回 {@code "[]"} ——
     * 于是「端上没带 images」被当成了「把轮播图全删掉」。b-app 的提交体里恰好
     * 从来没有这一项，所以**每改一次标题，详情页的轮播图就全没了**，
     * 且不报错：C 端只剩封面，看着像商家本来就没传图。
     *
     * <p>紧邻的 fulfillments 一直是判空的，两者相差一个 if —— 这类
     * 「同一个方法里两种写法」的缺陷，读代码时最容易滑过去。
     */
    @Test
    @DisplayName("★ 保存不带 images 不清空轮播图；显式传空数组才清空")
    void omittedImagesAreKept() throws Exception {
        String token = merchant("12600127013", "轮播图测试店");

        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"三图商品\",\"subtitle\":\"测试\",\"type\":\"NORMAL\","
                                + "\"cover\":\"c.jpg\",\"images\":[\"a.jpg\",\"b.jpg\",\"c.jpg\"],"
                                + "\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":5}]}"))
                .andExpect(jsonPath("$.data.images.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();

        // ★ 只改标题、提交体里没有 images —— 三张图必须还在
        mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"title\":\"改过标题\","
                                + "\"subtitle\":\"测试\",\"categoryNo\":\"CAT210\",\"cover\":\"c.jpg\","
                                + "\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":5}]}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(get("/biz/goods/" + goodsNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.images.length()").value(3));

        // 显式传空数组才是「清空」—— 两者分开，否则商家没有删图的路径
        mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"title\":\"改过标题\","
                        + "\"subtitle\":\"测试\",\"categoryNo\":\"CAT210\",\"cover\":\"c.jpg\","
                        + "\"images\":[],\"specGroups\":[],"
                        + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":5}]}"));
        mvc().perform(get("/biz/goods/" + goodsNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.images.length()").value(0));
    }

    /**
     * <b>品类由类目派生，请求里的 type 一律不采信。</b>
     *
     * <p>此前建品页有两个并列的分类控件，商家把同一件事填两遍，而且填得出
     * 「叶菜类目 + 日用品品类」这种组合 —— 页面只提示不阻断，没有一处会拦。
     * 代价要到下单那一刻才显形：生鲜要截单、按约重结算，而这件商品声称自己是日用品。
     *
     * <p>这条测的是**结构上不可能**，不是「端上会记得填对」：直接构造一个矛盾的
     * 请求体打进去，看库里落的是哪一个。
     */
    @Test
    @DisplayName("★ 品类由类目派生 —— 请求里塞一个矛盾的 type 也没用")
    void categoryDrivesType() throws Exception {
        String token = merchant("12600127014", "派生品类测试店");

        // CAT110 蔬菜 = FRESH；请求里故意写 NORMAL
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"矛盾的菜\",\"subtitle\":\"测试\",\"type\":\"NORMAL\","
                                + "\"categoryNo\":\"CAT110\",\"cover\":\"c.jpg\",\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0))
                // ★ 落库的是类目带出来的 FRESH，不是请求里那个 NORMAL
                .andExpect(jsonPath("$.data.type").value("FRESH"))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();

        /*
         * 改到一个 STANDARD 的类目，品类要跟着变 —— 否则「改类目」只改了归类，
         * 而履约仍按旧品类走，这正是两个输入点时代那个矛盾换了个方向重演。
         */
        mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"title\":\"矛盾的菜\","
                                + "\"subtitle\":\"测试\",\"categoryNo\":\"CAT210\",\"cover\":\"c.jpg\","
                                + "\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":10}]}"))
                .andExpect(jsonPath("$.data.type").value("NORMAL"));

        /*
         * 不带 categoryNo 的编辑**直接拒**（类目必填，P1-1 收尾）。
         *
         * 这条原先断言的是「不带类目就保留原品类」—— 那是类目还选填时的兜底。
         * 类目变必填之后，兜底本身就是要消掉的东西：留着它，一个漏传 categoryNo 的
         * 客户端就能让商品悄悄落进「默认日用品」，而商家以为自己建的是生鲜。
         * **宁可让保存报错，也不要一次静默的形态漂移。**
         */
        mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"title\":\"改个名\","
                                + "\"subtitle\":\"测试\",\"cover\":\"c.jpg\",\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(10400));

        // 查无此项的类目号也要拒，**不能兜底成日用品** —— 那是把一条错误数据
        // 静默转成一条合法数据（专用码 80004，让端上说得出「重新选一个类目」）
        mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"查无此类目\",\"subtitle\":\"测试\","
                                + "\"categoryNo\":\"CAT_NOPE\",\"cover\":\"c.jpg\",\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(80007));
    }

    /**
     * 子类目的 {@code template} 一律继承父节点 —— 运营传什么都忽略。
     *
     * <p>品类改成派生之后，这棵树就承载了履约与合规判定：父节点是 FRESH、
     * 子节点被填成 STANDARD 的话，同一支上的商品会走两套履约，而树渲染出来
     * 看不出任何异常。此前没有任何一处拦这个。
     */
    @Test
    @DisplayName("★ 子类目的形态继承父节点 —— 运营改不了，形态锁在根这一层")
    void childCategoryInheritsTemplate() throws Exception {
        String ops = opsLogin("goods", "goods123");
        // 挂在 CAT100（食品生鲜，FRESH）下，却声称自己是 STANDARD
        mvc().perform(post("/ops/categories").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"继承测试子类目\",\"parentNo\":\"CAT100\","
                                + "\"template\":\"STANDARD\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.template").value("FRESH"));
    }

    /**
     * <b>编辑一次商品，其余市场的价格行不能消失。</b>
     *
     * <p>上一版是按 {@code skuNo@market} 逐行比对：端上只回填得了当前市场那一格
     * （SkuVO 当时不下发 priceByMarket），提交上来的价格表只有 {CN}，
     * 于是 AE/US 两行被逻辑删 —— 那两个市场的买家从此看不到这件商品，
     * 而商家在 B 端看不出任何异常。与 titleI18n 是逐字同款的形状。
     *
     * <p>两头都测：① 保存后三行还在 ② 详情把整张价格表发回来（否则下次保存照样丢）。
     */
    @Test
    @DisplayName("★ 多市场定价：只改标题不该删掉其余市场的价，且详情要发回整张表")
    void otherMarketsSurviveAnEdit() throws Exception {
        String token = merchant("12600127015", "多市场定价店");

        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"三市场商品\",\"subtitle\":\"测试\","
                                + "\"categoryNo\":\"CAT210\",\"cover\":\"c.jpg\",\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":5,"
                                + "\"priceByMarket\":{\"CN\":1000,\"AE\":60,\"US\":15}}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();

        // ★ 详情必须把整张价格表发回来 —— 拿不到它，端上下次保存只能提交当前市场那一格
        mvc().perform(get("/biz/goods/" + goodsNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.skus[0].priceByMarket.CN").value(1000))
                .andExpect(jsonPath("$.data.skus[0].priceByMarket.AE").value(60))
                .andExpect(jsonPath("$.data.skus[0].priceByMarket.US").value(15));

        String skuNo = json.readTree(mvc().perform(get("/biz/goods/" + goodsNo)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("skus").get(0).get("skuNo").asString();

        // ★ 模拟老客户端：只提交 CN 一格（这正是上一版删掉另两行的那个请求）
        mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"title\":\"改了个标题\","
                                + "\"subtitle\":\"测试\",\"categoryNo\":\"CAT210\",\"cover\":\"c.jpg\","
                                + "\"specGroups\":[],"
                                + "\"skus\":[{\"skuNo\":\"" + skuNo + "\",\"optionValues\":[],"
                                + "\"price\":1000,\"stock\":5}]}"))
                .andExpect(jsonPath("$.code").value(0));

        mvc().perform(get("/biz/goods/" + goodsNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.skus[0].priceByMarket.AE").value(60))
                .andExpect(jsonPath("$.data.skus[0].priceByMarket.US").value(15));
    }

    /**
     * <b>标准品库的核心断言</b>（TDD-标准品库 §3.2）。
     *
     * <p>端上「从标准品开始」只是把字段**填进表单**，而填充过的表单商家能随便改。
     * 标题、图、规格文案改了没关系；但**类目与 optionCode 不能改** ——
     * 前者决定形态（生鲜要截单、服务不发货），后者是跨店可比的唯一依据。
     * code 能被改掉的话，标准品退化成一个填表助手，而 `optionCode`（B-4.5）
     * 「一期只写入不消费」要消费的那个前提又落空了。
     *
     * <p>所以这条测的是**结构上不可能**：直接构造一个把两样都改掉的请求打进去，
     * 看库里落的是哪一个。
     */
    @Test
    @DisplayName("★★ 引用标准品：类目与 optionCode 以标准品为准，改不掉")
    void stdConvergesCategoryAndCodes() throws Exception {
        String token = merchant("12600127016", "标准品测试店");

        // STD1001 本地菠菜：类目 CAT110（蔬菜 / FRESH —— V168 降二级后从叶菜上移），
        // 规格 code = W500G/W1JIN/W2JIN
        // 请求里故意把类目改成日用百货，把 code 改成自己编的
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stdNo\":\"STD1001\",\"categoryNo\":\"CAT210\","
                                + "\"title\":\"李婶家的菠菜\",\"subtitle\":\"今早现摘\",\"cover\":\"c.jpg\","
                                + "\"specGroups\":[{\"name\":\"份量\",\"options\":[\"500g\",\"1斤\"],"
                                + "\"optionCodes\":[\"MY_OWN_1\",\"MY_OWN_2\"]}],"
                                + "\"skus\":[{\"optionValues\":[\"500g\"],\"price\":500,\"stock\":10},"
                                + "{\"optionValues\":[\"1斤\"],\"price\":900,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0))
                // ★ 类目回到标准品那一档 —— 形态因此也跟着回到 FRESH
                .andExpect(jsonPath("$.data.categoryNo").value("CAT110"))
                .andExpect(jsonPath("$.data.type").value("FRESH"))
                // ★ code 以标准品为准，商家编的那两个没写进去
                .andExpect(jsonPath("$.data.specGroups[0].optionCodes[0]").value("W500G"))
                .andExpect(jsonPath("$.data.specGroups[0].optionCodes[1]").value("W1JIN"))
                // 展示文案是商家自己的 —— 「份量」不参与聚合，改了没关系
                .andExpect(jsonPath("$.data.specGroups[0].name").value("份量"))
                .andExpect(jsonPath("$.data.title").value("李婶家的菠菜"))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();
        assertThat(goodsNo).isNotBlank();

        // 查无此标准品要拒，**不能忽略**：忽略的话商品照样建出来、只是没了收敛，
        // 而 std_no 那一列还写着它 —— 一条自称「来自标准品」却不受约束的数据
        mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stdNo\":\"STD_NOPE\",\"categoryNo\":\"CAT210\","
                                + "\"title\":\"查无此标准品\",\"subtitle\":\"测试\",\"cover\":\"c.jpg\","
                                + "\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(10404));
    }

    @Test
    @DisplayName("★ 标准品搜索：按别名也搜得到；搜不到不是错误")
    void stdSearchMatchesAlias() throws Exception {
        String token = merchant("12600127017", "标准品搜索店");

        // 「洋芋」只出现在 keywords 里，标题是「土豆」—— 对不上的结果不是报错，
        // 是商家以为标准库里没有，然后自建一个，跨店可比在这一次就丢了
        mvc().perform(get("/biz/spu-std").header("Authorization", "Bearer " + token)
                        .param("keyword", "洋芋"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.title=='土豆')]").exists());

        // 搜不到返回空列表，不是 404 —— 端上要能顺畅地转去自建品
        mvc().perform(get("/biz/spu-std").header("Authorization", "Bearer " + token)
                        .param("keyword", "张姐家的酱菜"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
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
    @DisplayName("C 端令牌打不开商家后台 —— 而且要是 401，不是 403")
    void consumerTokenIsRejectedByBizChain() throws Exception {
        /*
         * **A7 的闸**：ctk_ 不该能操作 /biz/**。
         *
         * 为什么连 401/403 都要断言死：A7 落地时 BizContextFilter 的判据漏改，
         * 结果是所有人（含店主）都拿不到 BizContext —— 一律 403。403 看着像
         * 「这个人没这个权限」，是业务判定；而真相是**整条链的登录态没建起来**。
         * 那一版跑遍了全部单测都不红，只有场景测试打真链路才现形。
         *
         * 反过来也一样：如果哪天这里从 401 变成 403，说明 C 端令牌又能过认证了，
         * 只是权限不够而已 —— 那是两回事，中间隔着一次越权。
         */
        /*
         * 正向用 createGoods（而不是随便找个只读端点）：它要 BizContext 里有主体、
         * 有 GOODS 权限，**这一步在漏改那一版是 403**。挑一个不需要作用域的端点
         * 就会变成一条恒绿的闸 —— 恒绿的闸和没有闸是一回事。
         */
        String bizToken = merchant("12600127099", "换端测试店");
        createGoods(bizToken, "换端测试商品");

        // 同一条链，换成 C 端令牌：连认证都不该过 —— 是 401，不是「权限不够」的 403
        String consumerToken = login("12600133900");
        mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goodsBody(null, "越权商品", 1000, 10)))
                .andExpect(jsonPath("$.code").value(10401));
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
        // 新建落的是草稿，要提交之后才筛得到「待审」
        mvc().perform(get("/biz/goods").header("Authorization", "Bearer " + token)
                        .param("status", "DRAFT"))
                .andExpect(jsonPath("$.data.records[0].status").value("DRAFT"));
        for (JsonNode g : records(token, "DRAFT")) {
            mvc().perform(post("/biz/goods/" + g.get("goodsNo").asString() + "/submit")
                    .header("Authorization", "Bearer " + token));
        }
        mvc().perform(get("/biz/goods").header("Authorization", "Bearer " + token)
                        .param("status", "PENDING"))
                .andExpect(jsonPath("$.data.records[0].status").value("PENDING"));
        /*
         * 老词也要继续收：库里那列叫 AUDITING，老客户端（以及照着旧文档写的调用方）
         * 还在传它。不收的话它落进 default 分支「当作不过滤」——
         * 筛「审核中」筛出全部，比筛出空更难发现。
         */
        mvc().perform(get("/biz/goods").header("Authorization", "Bearer " + token)
                        .param("status", "AUDITING"))
                .andExpect(jsonPath("$.data.records[0].status").value("PENDING"));
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
                        .<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNo)
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getIsDefault, true));
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

        // 再开一家分店 —— 多门店是 PRO 才有的能力，测试要说出「这家商家买了包」
        TestPlan.grantPro(mvc(), json, planMapper, token);
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

        // 开一家分店，把店员只授权到分店（多门店是 PRO 才有的能力）
        TestPlan.grantPro(mvc(), json, planMapper, token);
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
        return TestLogin.merchantStaff(mvc(), json, otpStore, phone);
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
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;

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

    @Test
    @DisplayName("★★★ 详情要带回三语原文 —— 否则用中文编辑一次，英文与阿语就被清空了")
    void detailCarriesI18nSoEditingKeepsTranslations() throws Exception {
        String token = merchant("13500135090", "三语店");
        String body = "{\"categoryNo\":\"CAT210\",\"title\":\"叶菜\",\"subtitle\":\"当季\",\"type\":\"NORMAL\","
                + "\"titleI18n\":{\"en\":\"Leafy Greens\",\"ar\":\"خضار ورقية\"},"
                + "\"subtitleI18n\":{\"en\":\"In season\"},"
                + "\"cover\":\"🥬\",\"images\":[],\"specGroups\":[],"
                + "\"skus\":[{\"optionValues\":[],\"price\":550,\"stock\":40}]}";
        String goodsNo = json.readTree(mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("goodsNo").asString();

        /*
         * 编辑页按语言逐格填，保存是**整份覆盖**。详情不带原文的话，
         * 页面只能回填当前那一格，另外两格是空的 —— 保存即清空。
         * 而这个故障不报错：C 端缺译文时回落中文，看起来一切正常。
         */
        mvc().perform(get("/biz/goods/" + goodsNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.titleI18n.en").value("Leafy Greens"))
                .andExpect(jsonPath("$.data.titleI18n.ar").value("خضار ورقية"))
                .andExpect(jsonPath("$.data.subtitleI18n.en").value("In season"));

        // C 端不下发：那边的 title 已经按语言拍平，整份译文没有用处
        mvc().perform(get("/mp/goods/" + goodsNo))
                .andExpect(jsonPath("$.data.titleI18n").doesNotExist());
    }

    @Test
    @DisplayName("★★★ 编辑页的 round-trip：详情回不来的字段，保存一次就没了")
    void detailMustCarryEverythingSaveOverwrites() throws Exception {
        String token = merchant("13500135091", "回环店");

        /*
         * **这条守卫防的是一整类缺陷，不是某一个字段。**
         *
         * 商品保存是**整份覆盖**：端上把整个表单发上来，没带的字段就是空。
         * 而编辑页只能填回「详情接口给了什么」—— 于是任何一个
         * 「保存收得下、详情不返回」的字段，都会在**编辑一次之后静默消失**。
         * 已经这样丢过两次：商品主图（cover）、三语标题（titleI18n）。
         * 两次都不报错，第二次要等多市场的买家反馈才可能被发现。
         *
         * 做法就是把编辑页那条路径原样走一遍：
         *   建 → 读详情 → **只用详情里的字段**再存一次 → 再读 → 两次详情必须一致。
         * 新增可编辑字段时，只要忘了在详情里回传，这条就会红。
         */
        String rich = "{\"title\":\"回环菜\",\"subtitle\":\"回环副标\",\"type\":\"NORMAL\","
                + "\"titleI18n\":{\"en\":\"Round Trip\",\"ar\":\"ذهاب وإياب\"},"
                + "\"subtitleI18n\":{\"en\":\"Sub\"},"
                + "\"cover\":\"🥬\",\"images\":[\"img-a\",\"img-b\"],"
                + "\"categoryNo\":\"CAT110\","
                + "\"specGroups\":[{\"name\":\"规格\",\"options\":[\"大\",\"小\"]}],"
                + "\"skus\":[{\"optionValues\":[\"大\"],\"price\":800,\"stock\":7},"
                + "{\"optionValues\":[\"小\"],\"price\":500,\"stock\":9}]}";
        String goodsNo = json.readTree(mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(rich))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("goodsNo").asString();

        JsonNode first = detail(token, goodsNo);

        /*
         * **先断言详情真的把存进去的东西还回来了。**
         *
         * 少了这一段，下面那个「两次详情相等」是空转的：
         * 一个详情根本不返回的字段，两次都是 null，当然相等 ——
         * 我第一版就是这么写的，把 titleI18n 的修复撤掉后它依然是绿的。
         * **能被「两边都没有」满足的断言，等于没有断言。**
         */
        assertThat(first.get("cover").asString()).as("详情必须回传主图").isEqualTo("🥬");
        assertThat(first.get("categoryNo").asString()).as("详情必须回传类目").isEqualTo("CAT110");
        assertThat(first.get("images").size()).as("详情必须回传图集").isEqualTo(2);
        assertThat(first.get("titleI18n")).as("详情必须回传三语标题").isNotNull();
        assertThat(first.get("titleI18n").get("en").asString()).isEqualTo("Round Trip");
        assertThat(first.get("titleI18n").get("ar").asString()).isEqualTo("ذهاب وإياب");
        assertThat(first.get("subtitleI18n").get("en").asString()).isEqualTo("Sub");
        assertThat(first.get("specGroups").size()).as("详情必须回传规格组").isEqualTo(1);
        assertThat(first.get("skus").size()).as("详情必须回传整个 SKU 矩阵").isEqualTo(2);

        // 再存一次，**只用详情给回来的东西**——这正是编辑页能做的全部
        mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resaveBodyFrom(goodsNo, first)))
                .andExpect(jsonPath("$.code").value(0));
        JsonNode again = detail(token, goodsNo);

        for (String f : new String[]{"title", "subtitle", "cover", "categoryNo", "type",
                "titleI18n", "subtitleI18n", "images", "specGroups"}) {
            assertThat(again.get(f))
                    .as("字段 %s 在「读详情 → 原样存回」之后变了 —— 详情没把它回传", f)
                    .isEqualTo(first.get(f));
        }
        // SKU 的价与库存同理：编辑页把整个矩阵重发一遍
        assertThat(again.get("skus").toString())
                .as("SKU 的价/库存/规格值在 round-trip 之后变了")
                .isEqualTo(first.get("skus").toString());
    }

    private JsonNode detail(String token, String goodsNo) throws Exception {
        return json.readTree(mvc().perform(get("/biz/goods/" + goodsNo)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("data");
    }

    /** 把详情原样拼回保存体 —— 编辑页做的就是这件事（它没有别的信息来源）。 */
    private String resaveBodyFrom(String goodsNo, JsonNode d) {
        StringBuilder skus = new StringBuilder();
        for (JsonNode k : d.get("skus")) {
            if (skus.length() > 0) {
                skus.append(",");
            }
            skus.append("{\"skuNo\":\"").append(k.get("skuNo").asString())
                    .append("\",\"optionValues\":").append(k.get("optionValues"))
                    .append(",\"price\":").append(k.get("price").asLong())
                    .append(",\"stock\":").append(k.get("stock").asInt()).append("}");
        }
        StringBuilder groups = new StringBuilder();
        for (JsonNode g : d.get("specGroups")) {
            if (groups.length() > 0) {
                groups.append(",");
            }
            groups.append("{\"name\":\"").append(g.get("name").asString())
                    .append("\",\"options\":").append(g.get("options")).append("}");
        }
        return "{\"goodsNo\":\"" + goodsNo + "\""
                + ",\"title\":\"" + d.get("title").asString() + "\""
                + ",\"subtitle\":\"" + d.get("subtitle").asString() + "\""
                + ",\"type\":\"" + d.get("type").asString() + "\""
                + ",\"cover\":\"" + d.get("cover").asString() + "\""
                + ",\"categoryNo\":\"" + d.get("categoryNo").asString() + "\""
                + ",\"images\":" + d.get("images")
                + ",\"titleI18n\":" + nodeOrEmpty(d.get("titleI18n"))
                + ",\"subtitleI18n\":" + nodeOrEmpty(d.get("subtitleI18n"))
                + ",\"specGroups\":[" + groups + "]"
                + ",\"skus\":[" + skus + "]}";
    }

    /** 详情没给这个字段时，编辑页发的就是空 —— 守卫要的正是这个「诚实的空」 */
    private String nodeOrEmpty(JsonNode n) {
        return n == null || n.isNull() ? "{}" : n.toString();
    }

    @Test
    @DisplayName("★★ 成本价与详情图往返 —— 有列没有写入路径，等于这两个字段不存在")
    void costPriceAndDetailImagesRoundTrip() throws Exception {
        String token = merchant("12600127040", "成本价测试店");
        String body = "{\"categoryNo\":\"CAT210\",\"title\":\"带成本的货\",\"subtitle\":\"测试\","
                + "\"cover\":\"🥫\",\"images\":[],\"detailImages\":[\"/img/d1.jpg\",\"/img/d2.jpg\"],"
                + "\"specGroups\":[],"
                + "\"skus\":[{\"optionValues\":[],\"price\":1200,\"stock\":5,\"costPrice\":800}]}";
        String saved = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode savedData = json.readTree(saved).get("data");
        String goodsNo = savedData.get("goodsNo").asString();
        String skuNo = savedData.get("skus").get(0).get("skuNo").asString();

        // 商家侧读得回来：编辑页要用它算毛利
        mvc().perform(get("/biz/goods/" + goodsNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.skus[0].costPrice").value(800))
                .andExpect(jsonPath("$.data.detailImages.length()").value(2));

        /*
         * **买家侧恒空**。进货价是商家的经营秘密 —— 出现在买家端的响应里就等于公开了，
         * 而这种泄露不会有任何一处报错。详情图相反：它本来就是给买家看的。
         */
        String pub = mvc().perform(get("/mp/goods/" + goodsNo)).andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(pub).get("data");
        if (data != null && !data.isNull()) {
            assertThat(data.get("skus").get(0).get("costPrice").isNull())
                    .as("成本价不能下发给买家").isTrue();
            assertThat(data.get("detailImages").size()).as("详情图要发给买家").isEqualTo(2);
        }

        // 只改标题时不带这两个字段 = 不改（与 images / detail 同一口径）
        mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"categoryNo\":\"CAT210\","
                                + "\"title\":\"改个名字\",\"subtitle\":\"测试\",\"cover\":\"🥫\","
                                + "\"specGroups\":[],"
                                // 带原 skuNo：不带就是「这是一条新规格」，旧行连同成本一起作废
                                + "\"skus\":[{\"skuNo\":\"" + skuNo + "\",\"optionValues\":[],"
                                + "\"price\":1200,\"stock\":5}]}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(get("/biz/goods/" + goodsNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.skus[0].costPrice").value(800))
                .andExpect(jsonPath("$.data.detailImages.length()").value(2));
    }

    @Test
    @DisplayName("★★★ SKU 数量上限 —— 接口层不拦的话，512 行规格能直接灌进商品详情")
    void tooManySkusAreRejected() throws Exception {
        String token = merchant("12600127041", "规格上限测试店");
        StringBuilder skus = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            if (i > 0) skus.append(",");
            skus.append("{\"optionValues\":[\"v").append(i).append("\"],\"price\":100,\"stock\":1}");
        }
        mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"规格爆炸\",\"subtitle\":\"测试\","
                                + "\"cover\":\"🥫\",\"specGroups\":[{\"name\":\"型号\",\"options\":[\"v0\"]}],"
                                + "\"skus\":[" + skus + "]}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    private String goodsBody(String goodsNo, String title, long price, int stock) {
        // 类目必填（P1-1 收尾）：CAT210 纸品清洁 —— 无 required_code，不会卡在资质准入上
        return "{" + (goodsNo == null ? "" : "\"goodsNo\":\"" + goodsNo + "\",")
                + "\"categoryNo\":\"CAT210\","
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

    private String opsLogin() throws Exception {
        return opsLogin("goods", "goods123");
    }

    @Test
    @DisplayName("★★★ 上架是编译点：过期的平台文案要在上架时刷新进快照")
    void publishRefreshesSnapshotFromSpecLibrary() throws Exception {
        String ops = opsLogin();
        String dimBody = mvc().perform(post("/ops/spec-dims").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"BAKEDIM\",\"name\":\"烘焙份量\",\"valueType\":\"ENUM\","
                                + "\"usageType\":\"SALE\",\"universal\":true}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String dimNo = json.readTree(dimBody).get("data").get("dimNo").asString();
        String vBody = mvc().perform(post("/ops/spec-values").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimNo\":\"" + dimNo + "\",\"code\":\"BKBIG\",\"label\":\"大包\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String valueNo = json.readTree(vBody).get("data").get("valueNo").asString();

        String biz = merchant("12600199201", "烘焙测试店");
        String gBody = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT110\",\"title\":\"烘焙测试品\","
                                + "\"subtitle\":\"t\",\"cover\":\"c\",\"images\":[],"
                                + "\"specGroups\":[{\"name\":\"烘焙份量\",\"templateNo\":\"" + dimNo
                                + "\",\"options\":[\"大包\"],\"optionCodes\":[\"BKBIG\"]}],"
                                + "\"skus\":[{\"optionValues\":[\"大包\"],\"price\":600,\"stock\":4}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(gBody).get("data").get("goodsNo").asString();
        approveGoods(goodsNo);

        // 商品还没上架（草稿期），平台把这一档改叫「特大包」—— 快照此刻还是「大包」，正确
        mvc().perform(post("/ops/spec-values").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valueNo\":\"" + valueNo + "\",\"dimNo\":\"" + dimNo
                                + "\",\"code\":\"BKBIG\",\"label\":\"特大包\"}"))
                .andExpect(jsonPath("$.code").value(0));

        // ⚠️ 缺陷：toggle 现在只翻状态位，不重烘焙 —— 带着两周前的文案就上架了
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));

        ai.neargo.shop.product.entity.PrdGoods g =
                ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                        goodsMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<ai.neargo.shop.product.entity.PrdGoods>lambdaQuery()
                                .eq(ai.neargo.shop.product.entity.PrdGoods::getGoodsNo, goodsNo)));
        assertThat(g.getSpecGroups())
                .as("上架＝编译点：快照文案要刷成当前平台文案")
                .contains("特大包");
        var sku = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.product.entity.PrdSku>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdSku::getGoodsNo, goodsNo))).get(0);
        assertThat(sku.getOptionValues()).as("SKU 文案同步刷新").contains("特大包");
        assertThat(sku.getOptionValueNos()).as("身份不变 —— 改的是文案").contains(valueNo);

        // 幂等：快照没变的重复上架不该动 SKU —— 每次上架都写库的话 updated_at 全乱
        var stamp = sku.getUpdatedAt();
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        var again = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectById(sku.getId()));
        assertThat(again.getUpdatedAt()).as("无变化的重复上架不写 SKU").isEqualTo(stamp);
    }

    @Test
    @DisplayName("★★★ 草稿引用的档被停用 → 上架被拦且点名；草稿不挡停用")
    void publishRejectsArchivedValueWithDetail() throws Exception {
        String ops = opsLogin();
        String dimBody = mvc().perform(post("/ops/spec-dims").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"BAKEGONE\",\"name\":\"停用烘焙份量\",\"valueType\":\"ENUM\","
                                + "\"usageType\":\"SALE\",\"universal\":true}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String dimNo = json.readTree(dimBody).get("data").get("dimNo").asString();
        String vBody = mvc().perform(post("/ops/spec-values").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimNo\":\"" + dimNo + "\",\"code\":\"BKGONE\",\"label\":\"临期装\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String valueNo = json.readTree(vBody).get("data").get("valueNo").asString();

        String biz = merchant("12600199202", "停用烘焙店");
        String gBody = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT110\",\"title\":\"停用烘焙品\","
                                + "\"subtitle\":\"t\",\"cover\":\"c\",\"images\":[],"
                                + "\"specGroups\":[{\"name\":\"停用烘焙份量\",\"templateNo\":\"" + dimNo
                                + "\",\"options\":[\"临期装\"],\"optionCodes\":[\"BKGONE\"]}],"
                                + "\"skus\":[{\"optionValues\":[\"临期装\"],\"price\":300,\"stock\":2}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(gBody).get("data").get("goodsNo").asString();
        approveGoods(goodsNo);

        /*
         * **草稿不挡停用**（在用检查收窄为 on_sale=1 之后）——
         * 商品此刻没上架，运营停这一档要放行。失效引用改由上架校验拦。
         */
        mvc().perform(post("/ops/spec-values/" + valueNo + "/archive")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0));

        // ⚠️ 缺陷：现在 toggle 不做校验，带着已停用的档就上了架
        String toggleBody = mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(toggleBody).get("code").asInt())
                .as("上架必须被拦 —— 引用的档已停用").isEqualTo(80017);
        assertThat(json.readTree(toggleBody).get("msg").asString())
                .as("错误信息要点名哪一档，商家才知道改什么").contains("临期装");
    }

    private void approveGoods(String goodsNo) throws Exception {
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                        .header("Authorization", "Bearer " + opsLogin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private String merchantNoOf(String token) throws Exception {
        String body = mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
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
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
