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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 商品编码（条码 / 货号 / 单位）批量导入（P4）。
 *
 * <p>这一组测的全是**「成功了但数据没了」**那一类失败 ——
 * 批量写入的危险不在于报错，在于它报成功：
 *
 * <ul>
 *   <li>ERP 导出的表里空列是常态，按「空 = 清空」处理就能一次抹平全店条码</li>
 *   <li>只有货号没有 skuNo 的表认不出行，若当成「新行」或「跳过」，
 *       商家看到的是「导入成功 0 行」，而他以为改好了</li>
 *   <li>货号是唯一键，撞了要当场说清是哪一行，不能让数据库抛在第 137 行</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class SkuIdentityImportFlowTest {

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

    @Test
    @DisplayName("★★ CSV 里的空格子是「不改」，不是「清空」")
    void blankCellKeepsCurrentValue() throws Exception {
        String token = merchant("12600311001", "编码导入·空格子");
        String goodsNo = createGoods(token, "饼干两档");
        String skuNo = firstSkuNo(token, goodsNo);

        // 先给它一个条码
        importCsv(token, "skuNo,条码,货号,单位\n" + skuNo + ",6901234567890,HX-001,包");
        assertThat(barcodeOf(token, goodsNo)).isEqualTo("6901234567890");

        /*
         * 他从 ERP 导了一份只填货号的表回来 —— 条码那一列整列是空的。
         * 若按单条编辑的口径（空串 = 清空），这一下就把条码抹了，
         * 而接口照样回「成功 1 行」。全店两百行时这是不可逆的。
         */
        JsonNode r = importCsv(token, "skuNo,条码,货号,单位\n" + skuNo + ",,HX-002,");
        assertThat(barcodeOf(token, goodsNo))
                .as("空格子不该清空条码 —— 那是 ERP 导出的常态，不是他的意思")
                .isEqualTo("6901234567890");
        assertThat(r.get("willSet").asInt()).isEqualTo(1);   // 货号确实改了
    }

    @Test
    @DisplayName("★★ 要清空得显式写 -，这样「清空」是他说的，不是文件的副作用")
    void dashClearsExplicitly() throws Exception {
        String token = merchant("12600311002", "编码导入·清空");
        String goodsNo = createGoods(token, "清空用");
        String skuNo = firstSkuNo(token, goodsNo);

        importCsv(token, "skuNo,条码\n" + skuNo + ",6901111111111");
        assertThat(barcodeOf(token, goodsNo)).isEqualTo("6901111111111");

        importCsv(token, "skuNo,条码\n" + skuNo + ",-");
        assertThat(barcodeOf(token, goodsNo))
                .as("写了 - 就该真清掉 —— 否则填错的条码永远擦不掉")
                .isNull();
    }

    @Test
    @DisplayName("★★ 整列不在表头里 = 这一列一个字都不碰")
    void missingColumnTouchesNothing() throws Exception {
        String token = merchant("12600311003", "编码导入·缺列");
        String goodsNo = createGoods(token, "缺列用");
        String skuNo = firstSkuNo(token, goodsNo);

        importCsv(token, "skuNo,条码,货号\n" + skuNo + ",6902222222222,HX-100");
        // 他只想改货号，于是把条码那一列整个删了
        importCsv(token, "skuNo,货号\n" + skuNo + ",HX-200");

        assertThat(barcodeOf(token, goodsNo))
                .as("表头里没有条码列，条码就该原样不动")
                .isEqualTo("6902222222222");
        assertThat(codeOf(token, goodsNo)).isEqualTo("HX-200");
    }

    @Test
    @DisplayName("★★ 只有货号没有 skuNo 的表：按货号认行，认不出要指名报错")
    void resolvesByMerchantCodeWhenSkuNoAbsent() throws Exception {
        String token = merchant("12600311004", "编码导入·货号回退");
        String goodsNo = createGoods(token, "货号回退");
        String skuNo = firstSkuNo(token, goodsNo);
        importCsv(token, "skuNo,条码,货号\n" + skuNo + ",6903333333333,HX-A1");

        /*
         * 这是那个记在案的坑：他的 ERP 只认货号。
         * 若不先把货号解析成行就按位写，写的是别人；若当成找不到而跳过，
         * 他看到「0 行更新」也不知道为什么。两种都比报错糟。
         */
        JsonNode ok = importCsv(token, "货号,单位\nHX-A1,斤");
        assertThat(ok.get("willSet").asInt()).isEqualTo(1);
        assertThat(unitOf(token, goodsNo)).isEqualTo("斤");
        assertThat(barcodeOf(token, goodsNo))
                .as("按货号认出来的行，条码列不在表头里就不该被动")
                .isEqualTo("6903333333333");

        JsonNode bad = planCsv(token, "货号,单位\nHX-NOT-EXIST,斤");
        assertThat(bad.get("willSet").asInt()).isZero();
        assertThat(bad.get("problems").get(0).get("reason").asString())
                .as("认不出要说清是哪个货号，而不是安静跳过")
                .contains("HX-NOT-EXIST");
        assertThat(bad.get("problems").get(0).get("line").asInt())
                .as("行号要与他在 Excel 里看到的对得上（表头是第 1 行）")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 货号撞了当场报，且不写这一行 —— 唯一键不能靠数据库抛")
    void duplicateCodeIsRejectedPerRow() throws Exception {
        String token = merchant("12600311005", "编码导入·撞号");
        String goodsNo = createGoods(token, "撞号用");
        String[] skus = twoSkuNos(token, goodsNo);
        importCsv(token, "skuNo,货号\n" + skus[0] + ",HX-DUP");

        JsonNode r = planCsv(token, "skuNo,货号\n" + skus[1] + ",HX-DUP");
        assertThat(r.get("willSet").asInt()).isZero();
        assertThat(r.get("problems").get(0).get("reason").asString()).contains("HX-DUP");

        // 同一份文件里两行写成同一个货号，也是冲突
        JsonNode r2 = planCsv(token, "skuNo,货号\n" + skus[0] + ",HX-SAME\n" + skus[1] + ",HX-SAME");
        assertThat(r2.get("problems")).isNotEmpty();
    }

    @Test
    @DisplayName("★★ 试算不写库 —— 「先算后做」只有在真的没写时才成立")
    void planDoesNotWrite() throws Exception {
        String token = merchant("12600311006", "编码导入·试算");
        String goodsNo = createGoods(token, "试算用");
        String skuNo = firstSkuNo(token, goodsNo);

        JsonNode r = planCsv(token, "skuNo,条码\n" + skuNo + ",6904444444444");
        assertThat(r.get("willSet").asInt()).isEqualTo(1);
        assertThat(barcodeOf(token, goodsNo))
                .as("试算说会改，但库里现在必须还是原样")
                .isNull();
    }

    @Test
    @DisplayName("★ 别家的规格行认不出来 —— 作用域来自登录态，不是文件里的编号")
    void otherMerchantsSkuIsNotFound() throws Exception {
        String a = merchant("12600311007", "编码导入·甲店");
        String b = merchant("12600311008", "编码导入·乙店");
        String goodsA = createGoods(a, "甲店的货");
        String skuA = firstSkuNo(a, goodsA);

        JsonNode r = planCsv(b, "skuNo,条码\n" + skuA + ",6905555555555");
        assertThat(r.get("willSet").asInt()).isZero();
        assertThat(r.get("problems").get(0).get("reason").asString()).contains("不是本店");
    }

    @Test
    @DisplayName("★ 导出的表能原样导回去：一行不改，就该报 0 行改动")
    void exportRoundTripsWithNoChange() throws Exception {
        String token = merchant("12600311009", "编码导入·回环");
        String goodsNo = createGoods(token, "回环用");
        String skuNo = firstSkuNo(token, goodsNo);
        importCsv(token, "skuNo,条码,货号,单位\n" + skuNo + ",6906666666666,HX-RT,件");

        String body = mvc().perform(get("/biz/sku-identity/export")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String csv = json.readTree(body).get("data").get("csv").asString();
        /*
         * 导出这一条**必须断言内容**而不是只看状态码：第一版返回
         * ResponseEntity<byte[]>，被全局 ApiResponseWrapper 包了一手，
         * 稳定回 500 的 JSON —— 只断言 200 的话，这个接口坏了测试也不会红。
         */
        assertThat(csv).contains("6906666666666").contains("HX-RT");

        JsonNode r = planCsv(token, csv);
        assertThat(r.get("willSet").asInt())
                .as("导出→原样导回，不该有任何改动；有就说明导出与导入的口径对不上")
                .isZero();
        assertThat(r.get("noChange").asInt()).isPositive();
    }

    // ------------------------------------------------------------------ 脚手架

    private JsonNode importCsv(String token, String csv) throws Exception {
        return post("/biz/sku-identity/import", token, csv);
    }

    private JsonNode planCsv(String token, String csv) throws Exception {
        return post("/biz/sku-identity/import/plan", token, csv);
    }

    private JsonNode post(String path, String token, String csv) throws Exception {
        String body = mvc().perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(java.util.Map.of("csv", csv))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private String createGoods(String token, String title) throws Exception {
        String body = mvc().perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/biz/goods/save").header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"categoryNo\":\"CAT110\",\"title\":\"" + title + "\","
                                        + "\"subtitle\":\"测试\",\"cover\":\"🍪\",\"images\":[],"
                                        + "\"specGroups\":[],\"skus\":["
                                        + "{\"optionValues\":[],\"price\":500,\"stock\":9},"
                                        + "{\"optionValues\":[],\"price\":900,\"stock\":9}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("goodsNo").asString();
    }

    private JsonNode skus(String token, String goodsNo) throws Exception {
        String body = mvc().perform(get("/biz/goods/" + goodsNo)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("skus");
    }

    private String firstSkuNo(String token, String goodsNo) throws Exception {
        return skus(token, goodsNo).get(0).get("skuNo").asString();
    }

    private String[] twoSkuNos(String token, String goodsNo) throws Exception {
        JsonNode s = skus(token, goodsNo);
        return new String[]{s.get(0).get("skuNo").asString(), s.get(1).get("skuNo").asString()};
    }

    private String textOrNull(JsonNode n) {
        return n == null || n.isNull() ? null : n.asString();
    }

    private String barcodeOf(String token, String goodsNo) throws Exception {
        return textOrNull(skus(token, goodsNo).get(0).get("barcode"));
    }

    private String codeOf(String token, String goodsNo) throws Exception {
        return textOrNull(skus(token, goodsNo).get(0).get("merchantSkuCode"));
    }

    private String unitOf(String token, String goodsNo) throws Exception {
        return textOrNull(skus(token, goodsNo).get(0).get("saleUnit"));
    }

    private String merchant(String phone, String name) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                        + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                        + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                        + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();

        String bd = opsLogin("bd", "bd123");
        mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
