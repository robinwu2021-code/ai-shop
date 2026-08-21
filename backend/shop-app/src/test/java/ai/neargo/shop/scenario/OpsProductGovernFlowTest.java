package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运营端商品治理补齐（TDD-运营端商品治理补齐：P-3.3 库存与预售 · P-3.4 规格模板）。
 *
 * <p>这个文件守的是两件「配了也没人读」最容易发生的事：
 * <ol>
 *   <li><b>预售额度必须落在真实的下单闸门上</b> —— 只存一个数字的话，
 *       额度配 500 和配 0 对买家完全一样，而运营以为自己开了预售。
 *       所以这里不断言「接口返回了 500」，断言的是<b>第几件买得到、第几件买不到</b></li>
 *   <li><b>平台模板必须能被商家真的用上</b> —— E27 记的「模板是死的」正是
 *       「平台维护得了、商家查不到」。所以这里跨端验：ops 建 → biz 查得到 →
 *       biz 拿它建出来的商品身上真的带着 optionCode</li>
 * </ol>
 *
 * <p>手机号段：商户 {@code 126004xxxxx}、买家 {@code 130004xxxxx}。
 * 全量跑时与别的类共库，号段撞了会复用到别人的账号 —— 那种假绿只数行数是数不出来的。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsProductGovernFlowTest {

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

    // ---------------------------------------------------------------- P-3.3 预售额度

    @Test
    @DisplayName("★★★ 预售额度是真闸门：现货 1 件 + 额度 3 件 → 第 2/3/4 件买得到，第 5 件买不到")
    void presaleQuotaIsARealGate() throws Exception {
        String biz = merchant("12600400001", "预售额度店");
        String merchantNo = merchantNoOf(biz);
        String goodsNo = listedGoods(biz, 1);
        String skuNo = firstSku(goodsNo);
        String ops = opsLogin();

        // ① 现货那一件 —— 正常路径，证明后面的成败与「这件商品本来能不能买」无关
        assertThat(buy("13000400001", goodsNo, skuNo, 1, "pq-1")).isZero();
        /*
         * ② 现货已经被锁光。**没开预售时缺货就是缺货** ——
         * 这一步不是废话：少了它，后面「买得到」可能只是因为库存本来就够。
         */
        assertThat(buy("13000400002", goodsNo, skuNo, 1, "pq-2"))
                .as("额度还没配就买得到，说明第二级闸门根本没在判额度")
                .isEqualTo(20001);

        // ③ 配额度 3
        mvc().perform(post("/ops/skus/" + skuNo + "/presale")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"presaleQuota\":3,\"cutoffAt\":\"" + iso(2) + "\","
                                + "\"arriveAt\":\"" + iso(5) + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.presaleQuota").value(3))
                .andExpect(jsonPath("$.data.soldCount").value(0));

        // ④ 额度内的三件真的成交 —— 这三行撤掉 StockPortImpl 的回落就全红
        assertThat(buy("13000400003", goodsNo, skuNo, 1, "pq-3")).isZero();
        assertThat(buy("13000400004", goodsNo, skuNo, 1, "pq-4")).isZero();
        assertThat(buy("13000400005", goodsNo, skuNo, 1, "pq-5")).isZero();

        // ⑤ 第 4 件预售被额度顶回来 —— 「额度」不是建议值
        assertThat(buy("13000400006", goodsNo, skuNo, 1, "pq-6"))
                .as("额度用尽还能下单 = 额度是个摆设")
                .isEqualTo(20001);

        // ⑥ 已售真的记在 SKU 上（不是算出来的假数）
        JsonNode row = presaleRow(ops, merchantNo, skuNo);
        assertThat(row.get("presaleQuota").asInt()).isEqualTo(3);
        assertThat(row.get("soldCount").asInt())
                .as("三单预售只记到 %d —— sold_count 没跟着锁定走，额度就永远用不完",
                        row.get("soldCount").asInt())
                .isEqualTo(3);
        assertThat(row.get("stock").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 取消预售单要把额度还回去 —— 不还的话「没卖多少却说额度满了」")
    void cancellingAPresaleOrderReturnsTheQuota() throws Exception {
        String biz = merchant("12600400010", "预售取消店");
        String merchantNo = merchantNoOf(biz);
        String goodsNo = listedGoods(biz, 0);   // 纯预售：一件现货都没有
        String skuNo = firstSku(goodsNo);
        String ops = opsLogin();

        setPresale(ops, skuNo, 1, iso(2), iso(5));

        String buyer = login("13000400011");
        String orderNo = order(buyer, goodsNo, skuNo, "pc-1");
        assertThat(orderNo).as("额度 1 的第一单必须成交").isNotBlank();
        assertThat(presaleRow(ops, merchantNo, skuNo).get("soldCount").asInt()).isEqualTo(1);

        // 额度已满 —— 先证明它确实满了，否则下面「取消后又能买」说明不了任何事
        assertThat(buy("13000400012", goodsNo, skuNo, 1, "pc-2")).isEqualTo(20001);

        mvc().perform(post("/mp/order/" + orderNo + "/cancel")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"不要了\"}"))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(presaleRow(ops, merchantNo, skuNo).get("soldCount").asInt())
                .as("取消后 sold_count 没减回去 —— 额度会随取消数一路缩水")
                .isZero();
        // 还回去的额度真的能再卖一件（不是只有那个数字变好看了）
        assertThat(buy("13000400013", goodsNo, skuNo, 1, "pc-3")).isZero();
    }

    @Test
    @DisplayName("★ 截单时间真的截得住：截单前买得到，改成已过截单立刻买不到")
    void cutoffStopsNewOrders() throws Exception {
        String biz = merchant("12600400020", "截单时间店");
        String goodsNo = listedGoods(biz, 0);
        String skuNo = firstSku(goodsNo);
        String ops = opsLogin();

        setPresale(ops, skuNo, 10, iso(2), iso(5));
        assertThat(buy("13000400021", goodsNo, skuNo, 1, "co-1"))
                .as("截单前就买不到，后面的「截住了」说明不了是截单起的作用")
                .isZero();

        // 截单挪到过去（到货时间不传 = 不改，仍是 +5 天，校验依然成立）
        setPresale(ops, skuNo, 10, Instant.now().minus(1, ChronoUnit.HOURS).toString(), null);
        assertThat(buy("13000400022", goodsNo, skuNo, 1, "co-2"))
                .as("已截单还在收单 —— 次日现采的采购单已经下了，这批订单没有货")
                .isEqualTo(20001);
    }

    @Test
    @DisplayName("预售配置的两条硬校验：截单必须早于到货、额度不能为负")
    void presaleConfigValidations() throws Exception {
        String biz = merchant("12600400030", "预售校验店");
        String skuNo = firstSku(listedGoods(biz, 3));
        String ops = opsLogin();

        // 截单晚于到货 = 货到了还能继续下单，而那批订单没有对应的采购
        assertThat(presaleCode(ops, skuNo, "{\"presaleQuota\":5,\"cutoffAt\":\"" + iso(6)
                + "\",\"arriveAt\":\"" + iso(3) + "\"}"))
                .isEqualTo(ai.neargo.shop.common.ErrorCode.PRESALE_CUTOFF_AFTER_ARRIVAL.code());
        // 负额度会让 sold_count + qty <= presale_quota 恒不成立：「开了预售反而更买不了」
        assertThat(presaleCode(ops, skuNo, "{\"presaleQuota\":-1}")).isEqualTo(10400);
        /*
         * 边界相等也不行（截单那一刻货正好到，仍然没有采购冗余）。
         *
         * ⚠️ 两处必须用**同一个**时间串：`iso(3)` 调两次会差几十微秒，
         * 于是 cutoff 严格早于 arrive，校验正确放行 —— 而这条用例本来想测的是相等。
         * 第一版就是这么写的，红了之后查了半天实现，问题在测试自己身上。
         */
        String sameMoment = iso(3);
        assertThat(presaleCode(ops, skuNo, "{\"presaleQuota\":5,\"cutoffAt\":\"" + sameMoment
                + "\",\"arriveAt\":\"" + sameMoment + "\"}"))
                .isEqualTo(ai.neargo.shop.common.ErrorCode.PRESALE_CUTOFF_AFTER_ARRIVAL.code());
    }

    @Test
    @DisplayName("★ 超卖告警只报「运营自己调出来的」—— 调额度到已售之下，这条 SKU 立刻出现在告警里")
    void oversellAlertComesFromShrinkingTheQuota() throws Exception {
        String biz = merchant("12600400040", "超卖告警店");
        String merchantNo = merchantNoOf(biz);
        String goodsNo = listedGoods(biz, 0);
        String skuNo = firstSku(goodsNo);
        String ops = opsLogin();

        setPresale(ops, skuNo, 3, iso(2), iso(5));
        assertThat(buy("13000400041", goodsNo, skuNo, 1, "ov-1")).isZero();
        assertThat(buy("13000400042", goodsNo, skuNo, 1, "ov-2")).isZero();

        // 正常成交超不出去 —— 闸门是 sold_count + qty <= quota
        assertThat(oversellSkuNos(ops))
                .as("没人调过额度就报超卖，说明这张表报的不是它声称的东西")
                .doesNotContain(skuNo);

        // 次日现采临时收紧：额度 3 → 1，而已售 2。**刻意不拦** —— 拦住等于把问题藏起来
        mvc().perform(post("/ops/skus/" + skuNo + "/presale")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"presaleQuota\":1,\"cutoffAt\":\"" + iso(2) + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.presaleQuota").value(1))
                .andExpect(jsonPath("$.data.soldCount").value(2));

        assertThat(oversellSkuNos(ops))
                .as("那 2 件已经卖出去了，没人认领的话就是两个发不出货的买家")
                .contains(skuNo);

        // presaleOnly 是 SQL 里的过滤：交给前端 filter 的话，真实库里这条大概率不在第一页
        JsonNode row = presaleRow(ops, merchantNo, skuNo);
        assertThat(row.get("soldCount").asInt()).isGreaterThan(row.get("presaleQuota").asInt());
    }

    @Test
    @DisplayName("presaleOnly 只出开了预售的 SKU —— 否则那个 tab 会长期显示为空而接口 200")
    void presaleOnlyFiltersInSql() throws Exception {
        String biz = merchant("12600400050", "预售筛选店");
        String merchantNo = merchantNoOf(biz);
        String withPresale = firstSku(listedGoods(biz, 5));
        String withoutPresale = firstSku(listedGoods(biz, 5));
        String ops = opsLogin();

        setPresale(ops, withPresale, 8, null, null);

        JsonNode all = skuRows(ops, merchantNo, false);
        assertThat(skuNosOf(all)).contains(withPresale, withoutPresale);

        JsonNode only = skuRows(ops, merchantNo, true);
        assertThat(skuNosOf(only)).contains(withPresale).doesNotContain(withoutPresale);
        for (JsonNode r : only) {
            assertThat(r.get("presaleQuota").asInt()).isPositive();
            assertThat(r.get("merchantNo").asString()).isEqualTo(merchantNo);
        }
    }

    // ---------------------------------------------------------------- P-3.2 SKU 粒度处置

    @Test
    @DisplayName("★ SKU 级压下架 = 主体下架 + 撤池，但**不撤过审**（商家自己就能恢复）")
    void forceOffSkuSuspendsWithoutRevokingAudit() throws Exception {
        String biz = merchant("12600400060", "SKU压下架店");
        String goodsNo = listedGoods(biz, 10);
        String skuNo = firstSku(goodsNo);
        String ops = opsLogin();

        assertThat(buy("13000400061", goodsNo, skuNo, 1, "fs-1")).isZero();

        mvc().perform(post("/ops/skus/" + skuNo + "/force-off")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"这个规格的净含量标错了\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("OFF_SALE"));

        // 撤池落到买家侧 —— 只改 status 的话这一步会成功，处置就是假的
        assertThat(buy("13000400062", goodsNo, skuNo, 1, "fs-2")).isNotEqualTo(0);
        // 过审结论还在：商家改完自己点一下就能回来，不必走一遍重新提审
        mvc().perform(get("/biz/goods/" + goodsNo).header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.data.status").value("OFF_SALE"))
                .andExpect(jsonPath("$.data.auditReason").value("平台下架：这个规格的净含量标错了"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"));
        assertThat(buy("13000400063", goodsNo, skuNo, 1, "fs-3")).isZero();
    }

    @Test
    @DisplayName("SKU 级审核解析到父商品：驳回后整件商品是 REJECTED，理由回到商家")
    void auditSkuResolvesToParentGoods() throws Exception {
        String biz = merchant("12600400070", "SKU审核店");
        String goodsNo = pendingGoods(biz, 10);
        String skuNo = firstSku(goodsNo);
        String ops = opsLogin();

        mvc().perform(post("/ops/skus/" + skuNo + "/audit")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pass\":false,\"reason\":\"资质与类目不符\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        // 审的是「这件商品能不能卖」——结论落在父商品上，B 端看得到理由
        mvc().perform(get("/biz/goods/" + goodsNo).header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.auditReason").value("资质与类目不符"));
    }

    // ---------------------------------------------------------------- P-3.4 规格模板

    @Test
    @DisplayName("★★★ 跨端：平台建的模板，商家真的查得到、真的能拿它建品（E27「模板是死的」）")
    void platformTemplateReachesMerchantAndCarriesCodes() throws Exception {
        String ops = opsLogin();
        String name = "净含量-" + System.nanoTime();
        String templateNo = saveTemplate(ops, "{\"categoryType\":\"FRESH\",\"name\":\"" + name + "\","
                + "\"options\":[{\"code\":\"W500\",\"label\":\"500g\"},"
                + "{\"code\":\"W1000\",\"label\":\"1kg\"}]}");

        // 平台端：scope 由后端写死，请求体里没有它也不该出现商家模板
        mvc().perform(get("/ops/spec-templates").param("keyword", name)
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.data.records[0].scope").value("PLATFORM"))
                .andExpect(jsonPath("$.data.records[0].categoryType").value("FRESH"))
                .andExpect(jsonPath("$.data.records[0].archivedAt").doesNotExist());

        // ★ 商家端真的查得到 —— 这一步才是 E27 那条断裂
        String biz = merchant("12600400080", "模板取用店");
        JsonNode mine = templateByNo(biz, templateNo);
        assertThat(mine).as("平台刚建的模板商家查不到 = 模板依然是死的").isNotNull();
        assertThat(mine.get("scope").asString()).isEqualTo("PLATFORM");
        assertThat(mine.get("options").get(0).get("code").asString()).isEqualTo("W500");

        // ★ 拿它建品，商品身上真的带着 optionCode（没有 code 的模板与手输没有区别）
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"按模板建的生鲜\",\"type\":\"NORMAL\","
                                + "\"specGroups\":[{\"name\":\"净含量\",\"options\":[\"500g\",\"1kg\"],"
                                + "\"optionCodes\":[\"W500\",\"W1000\"],\"templateNo\":\"" + templateNo + "\"}],"
                                + "\"skus\":[{\"optionValues\":[\"500g\"],\"price\":1000,\"stock\":5},"
                                + "{\"optionValues\":[\"1kg\"],\"price\":1800,\"stock\":5}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode group = json.readTree(body).get("data").get("specGroups").get(0);
        assertThat(group.get("templateNo").asString()).isEqualTo(templateNo);
        assertThat(group.get("optionCodes").toString())
                .as("建出来的商品没带 code —— 三家店的「500g」就再也聚合不到一起")
                .contains("W500").contains("W1000");
    }

    @Test
    @DisplayName("★ 归档立刻对商家生效 —— 归档了商家还能选，等于没归档")
    void archiveHidesTemplateFromMerchantsImmediately() throws Exception {
        String ops = opsLogin();
        String name = "换季规格-" + System.nanoTime();
        String templateNo = saveTemplate(ops, "{\"categoryType\":\"FRESH\",\"name\":\"" + name + "\","
                + "\"options\":[{\"code\":\"S1\",\"label\":\"小份\"}]}");
        String biz = merchant("12600400090", "模板归档店");
        assertThat(templateByNo(biz, templateNo)).isNotNull();

        mvc().perform(post("/ops/spec-templates/" + templateNo + "/archive")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.archivedAt").isNotEmpty());

        assertThat(templateByNo(biz, templateNo))
                .as("归档后商家还能选到 —— 运营会以为自己把那套错的规格下线了")
                .isNull();
        // 平台侧默认也不出，要显式 showArchived 才看得到（归档不是删除）
        assertThat(templateNos(ops, name, false)).doesNotContain(templateNo);
        assertThat(templateNos(ops, name, true)).contains(templateNo);

        mvc().perform(post("/ops/spec-templates/" + templateNo + "/unarchive")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.archivedAt").doesNotExist());
        assertThat(templateByNo(biz, templateNo)).isNotNull();
    }

    @Test
    @DisplayName("★ 平台模板的三条校验：code 必填、组内 code 不重、同类目不重名")
    void platformTemplateValidations() throws Exception {
        String ops = opsLogin();
        String name = "校验规格-" + System.nanoTime();

        // 没有 code 的平台模板与商家手输的没有任何区别 —— 它唯一的作用是让人以为规格统一了
        assertThat(saveTemplateCode(ops, "{\"categoryType\":\"FRESH\",\"name\":\"" + name + "\","
                + "\"options\":[{\"label\":\"没有 code\"}]}"))
                .isEqualTo(ai.neargo.shop.common.ErrorCode.SPEC_TEMPLATE_CODE_REQUIRED.code());
        assertThat(saveTemplateCode(ops, "{\"categoryType\":\"FRESH\",\"name\":\"" + name + "\","
                + "\"options\":[{\"code\":\"  \",\"label\":\"空白 code\"}]}"))
                .isEqualTo(ai.neargo.shop.common.ErrorCode.SPEC_TEMPLATE_CODE_REQUIRED.code());
        // 两个 code 相同的选项聚合时会把「500g」和「1kg」并成一个 —— 正是 code 要防的事
        assertThat(saveTemplateCode(ops, "{\"categoryType\":\"FRESH\",\"name\":\"" + name + "\","
                + "\"options\":[{\"code\":\"X\",\"label\":\"甲\"},{\"code\":\"X\",\"label\":\"乙\"}]}"))
                .isEqualTo(ai.neargo.shop.common.ErrorCode.SPEC_TEMPLATE_DUPLICATE.code());

        String templateNo = saveTemplate(ops, "{\"categoryType\":\"FRESH\",\"name\":\"" + name + "\","
                + "\"options\":[{\"code\":\"X\",\"label\":\"甲\"}]}");
        // 同类目重名：商家下拉里出现两个「重量」，选哪个都对不上
        assertThat(saveTemplateCode(ops, "{\"categoryType\":\"FRESH\",\"name\":\"" + name + "\","
                + "\"options\":[{\"code\":\"Y\",\"label\":\"乙\"}]}"))
                .isEqualTo(ai.neargo.shop.common.ErrorCode.SPEC_TEMPLATE_DUPLICATE.code());
        // 但改自己不算重名 —— 少了那一句，改一下选项就报「已有同名模板」
        mvc().perform(post("/ops/spec-templates").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateNo\":\"" + templateNo + "\",\"categoryType\":\"FRESH\","
                                + "\"name\":\"" + name + "\","
                                + "\"options\":[{\"code\":\"X\",\"label\":\"甲\"},"
                                + "{\"code\":\"Z\",\"label\":\"丙\"}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.options.length()").value(2));
        // 换个类目同名是允许的（模板按品类预置）
        assertThat(saveTemplateCode(ops, "{\"categoryType\":\"STANDARD\",\"name\":\"" + name + "\","
                + "\"options\":[{\"code\":\"Y\",\"label\":\"乙\"}]}")).isZero();
    }

    @Test
    @DisplayName("商家自存的模板在平台端一律 404 —— 别家存了什么不该被平台顺手改掉")
    void merchantTemplateIsInvisibleToPlatform() throws Exception {
        String biz = merchant("12600400100", "自存模板店");
        String body = mvc().perform(post("/biz/spec-templates").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"我自己的规格\","
                                + "\"options\":[{\"label\":\"随手写的\"}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String templateNo = json.readTree(body).get("data").get("templateNo").asString();
        String ops = opsLogin();

        assertThat(templateNos(ops, "我自己的规格", true)).doesNotContain(templateNo);
        String archived = mvc().perform(post("/ops/spec-templates/" + templateNo + "/archive")
                        .header("Authorization", "Bearer " + ops))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(archived).get("code").asInt()).isEqualTo(10404);
    }

    // ---------------------------------------------------------------- 装配

    private String iso(int daysFromNow) {
        return Instant.now().plus(daysFromNow, ChronoUnit.DAYS).toString();
    }

    private void setPresale(String ops, String skuNo, int quota, String cutoff, String arrive)
            throws Exception {
        String body = "{\"presaleQuota\":" + quota
                + (cutoff == null ? "" : ",\"cutoffAt\":\"" + cutoff + "\"")
                + (arrive == null ? "" : ",\"arriveAt\":\"" + arrive + "\"") + "}";
        mvc().perform(post("/ops/skus/" + skuNo + "/presale")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0));
    }

    private int presaleCode(String ops, String skuNo, String body) throws Exception {
        String res = mvc().perform(post("/ops/skus/" + skuNo + "/presale")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("code").asInt();
    }

    private JsonNode skuRows(String ops, String merchantNo, boolean presaleOnly) throws Exception {
        String body = mvc().perform(get("/ops/skus")
                        .param("merchantNo", merchantNo)
                        .param("presaleOnly", String.valueOf(presaleOnly))
                        .param("size", "50")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records");
    }

    private JsonNode presaleRow(String ops, String merchantNo, String skuNo) throws Exception {
        for (JsonNode r : skuRows(ops, merchantNo, true)) {
            if (skuNo.equals(r.get("skuNo").asString())) {
                return r;
            }
        }
        throw new AssertionError("预售列表里没有 " + skuNo + " —— 额度配了却查不到");
    }

    private java.util.List<String> skuNosOf(JsonNode rows) {
        java.util.List<String> out = new java.util.ArrayList<>();
        rows.forEach(r -> out.add(r.get("skuNo").asString()));
        return out;
    }

    private java.util.List<String> oversellSkuNos(String ops) throws Exception {
        String body = mvc().perform(get("/ops/skus/oversell").header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return skuNosOf(json.readTree(body).get("data"));
    }

    private String saveTemplate(String ops, String body) throws Exception {
        String res = mvc().perform(post("/ops/spec-templates").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("data").get("templateNo").asString();
    }

    private int saveTemplateCode(String ops, String body) throws Exception {
        String res = mvc().perform(post("/ops/spec-templates").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("code").asInt();
    }

    private java.util.List<String> templateNos(String ops, String keyword, boolean showArchived)
            throws Exception {
        String body = mvc().perform(get("/ops/spec-templates")
                        .param("keyword", keyword)
                        .param("showArchived", String.valueOf(showArchived))
                        .header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> out = new java.util.ArrayList<>();
        json.readTree(body).get("data").get("records")
                .forEach(r -> out.add(r.get("templateNo").asString()));
        return out;
    }

    /** 商家侧下发的模板里找这一条；查不到返回 null。 */
    private JsonNode templateByNo(String bizToken, String templateNo) throws Exception {
        String body = mvc().perform(get("/biz/spec-templates").param("categoryType", "FRESH")
                        .header("Authorization", "Bearer " + bizToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode t : json.readTree(body).get("data")) {
            if (templateNo.equals(t.get("templateNo").asString())) {
                return t;
            }
        }
        return null;
    }

    /** @return 下单响应的 code，0 = 成功 */
    private int buy(String phone, String goodsNo, String skuNo, int qty, String idem) throws Exception {
        String buyer = login(phone);
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":" + qty + "}"));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("code").asInt();
    }

    /** 下单并返回主订单号（取消要用它）。 */
    private String order(String buyerToken, String goodsNo, String skuNo, String idem) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + buyerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}"));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyerToken)
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("orderNo").asString();
    }

    /** 建品并过审上架。 */
    private String listedGoods(String token, int stock) throws Exception {
        String goodsNo = pendingGoods(token, stock);
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                .header("Authorization", "Bearer " + opsLogin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));
        return goodsNo;
    }

    /** 建品但不过审（SKU 级审核用例要一个待审的）。 */
    private String pendingGoods(String token, int stock) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"商品治理测试品\",\"type\":\"NORMAL\","
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":" + stock + "}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();
        // 记下建它的人：0 库存的商品在 C 端拿不到 SKU，只能走 B 端详情取 skuNo
        bizOfGoods.put(goodsNo, token);
        return goodsNo;
    }

    private String firstSku(String goodsNo) throws Exception {
        String body = mvc().perform(get("/biz/goods/" + goodsNo)
                        .header("Authorization", "Bearer " + bizOfGoods.get(goodsNo)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("skus").get(0).get("skuNo").asString();
    }

    /** goodsNo → 建它的商家 token（0 库存的商品在 C 端查不到 SKU，只能走 B 端详情）。 */
    private final java.util.Map<String, String> bizOfGoods = new java.util.HashMap<>();

    private String merchantNoOf(String bizToken) throws Exception {
        String body = mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + bizToken))
                .andExpect(status().isOk())
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
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + opsLogin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        return login(phone);
    }

    private String opsLogin() throws Exception {
        return TestLogin.admin(mvc(), json);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
