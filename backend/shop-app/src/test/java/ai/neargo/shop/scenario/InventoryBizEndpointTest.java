package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.support.TestLogin;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家端进销存九屏的**契约**测试 —— 打真实 HTTP 层，用 <b>b-app 真正发出去的那份 body</b>。
 *
 * <h2>为什么非要有这一条</h2>
 * 九屏的写动作（过账、提交盘点、发出/收货、加仓）**一个都没在界面上点通过** ——
 * 浏览器面板这几轮点击持续超时，只有截图能用。而「类型过了」证明不了 body 对：
 * 运营端那三页就是类型全过、mock 自查全绿，接上真后端两个调不通。
 *
 * <p>所以这里的 body 一律**照抄 b-app 的 http 层**（`b-app/src/api/http.ts` 与各页面
 * 拼出来的形状），不是照后端的 record 反推 —— 反推只能证明后端自洽。
 *
 * <p>几条特别容易静默错的：
 * <ul>
 *   <li>{@code PUT /counts/{no}/lines} 发的是**裸数组**（`http.put(path, lines)`）。
 *       包一层 {@code {lines:[…]}} 的话后端解成空列表，而空列表在盘点里是
 *       「一件都没盘」—— 不报错，过账之后什么都没发生</li>
 *   <li>{@code occurredAt} 端上拼的是 {@code "2026-08-26T00:00:00"}（日期 + 零点），
 *       后端收 {@code LocalDateTime}。格式对不上是 400，而端上会显示成「保存失败」</li>
 *   <li>盘点单读回来的 {@code bookQty} 是**开单那一刻的快照**，不是当前余额。
 *       这一条界面直接拿来算差异，错了会把中间卖掉的量记成盘亏</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryBizEndpointTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OtpStore otpStore;
    @Autowired
    private InventoryAclService acl;
    @Autowired
    private ai.neargo.shop.inventory.service.LocationService locations;
    @Autowired
    private ai.neargo.shop.event.OutboxDispatcher dispatcher;
    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.SkuMapper skuMapper;
    @Autowired
    private ai.neargo.shop.inventory.service.StockQueryService query;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    // ------------------------------------------------------------------ 读

    @Test
    @DisplayName("★★★ 库存页的三个读口：字段名与 b-app 的 StockSummary / StockBalance 逐字相同")
    void stockListFieldsMatchBApp() throws Exception {
        Shop s = shop();

        JsonNode summary = ok(get("/biz/inventory/summary"), s.token);
        for (String f : List.of("itemCount", "shortageCount", "staleCount")) {
            assertThat(summary.has(f)).as("StockSummary 读 %s", f).isTrue();
        }

        // filter=all：b-app 的「全部」那一栏。默认 todo 只给有待办标记的，新店可能是空的
        JsonNode rows = ok(get("/biz/inventory/balances?filter=all&size=100"), s.token);
        assertThat(rows.isArray()).as("balances 返回数组").isTrue();
        assertThat(rows.isEmpty()).as("种子里放了三件货").isFalse();

        JsonNode b = rows.get(0);
        for (String f : List.of("itemId", "name", "specText", "baseUom",
                "onHand", "reserved", "available", "flags")) {
            assertThat(b.has(f)).as("StockBalance 读 %s —— 少一列界面上永远空白且不报错", f).isTrue();
        }
        assertThat(b.get("flags").isArray()).as("flags 是数组，界面按它判「要处理」").isTrue();
    }

    @Test
    @DisplayName("★★★ 跨店总览：一件货一行，按「断了几家店」排，默认只给缺货的")
    void crossStoreCollapsesLocationsIntoOneRow() throws Exception {
        Shop s = shop();
        String other = okText(post("/biz/inventory/locations")
                .content("{\"name\":\"二号仓\"}"), s.token());

        /*
         * 种子那件货只在当前门店有余额，二号仓一件都没有 —— 也就是
         * 「一家店有、一家店断」。这正是这一屏要答的那个问题。
         */
        JsonNode rows = ok(get("/biz/inventory/cross-store?filter=all&size=50"), s.token());
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.isEmpty()).as("种子里有货，不该是空的").isFalse();

        JsonNode r = rows.get(0);
        for (String f : List.of("itemId", "name", "onHand", "available", "shortageLocations", "byLocation")) {
            assertThat(r.has(f)).as("CrossStoreRow 读 %s", f).isTrue();
        }

        /*
         * **一件货一行**，不是「一件货 × 一个库位」一行。同一个 itemId 出现两次
         * 就说明合并没做 —— 那正是多门店商家在 balances 那一屏看到的样子。
         */
        List<String> ids = new java.util.ArrayList<>();
        rows.forEach(x -> ids.add(x.get("itemId").asString()));
        assertThat(ids).as("同一件货只能占一行").doesNotHaveDuplicates();

        /*
         * **默认只给缺货的**。这一屏的用途是补货，全给的话真断了的那几件
         * 反而淹没在「都还有」里。刚建的二号仓一件货都没有，所以默认这一支非空。
         */
        JsonNode shortOnly = ok(get("/biz/inventory/cross-store?size=50"), s.token());
        assertThat(shortOnly.isEmpty()).as("有库位一件都没有，默认这一支该给得出东西").isFalse();
        shortOnly.forEach(x -> assertThat(x.get("shortageLocations").asInt())
                .as("默认这一支里每一行都该至少断一家店").isGreaterThan(0));

        assertThat(other).as("这条用例依赖新建的第二个库位").isNotBlank();
    }

    @Test
    @DisplayName("★★★ /biz/context 要下发 stockByInventory —— 切真相源那天不该再发一次版")
    void bizContextReportsStockAuthority() throws Exception {
        Shop s = shop();

        JsonNode sw = ok(get("/biz/context"), s.token()).get("switches");
        assertThat(sw).as("switches 整个字段要在 —— 端上 `?? {}` 会把缺字段变成「全 false」").isNotNull();
        assertThat(sw.has("stockByInventory"))
                .as("端上据它决定商品页的「修改库存」还能不能直接改").isTrue();

        /*
         * 测试环境没设 `shop.inventory.stock-authority`，取默认 PLATFORM，
         * 所以这里必须是 false —— **平台仍是真相源时不该拦商家改库存**，
         * 那本来就是在改真相源。真为 true 的那一格由端上的替身覆盖
         *（mock 的 mBizScope 可以把它拨成 true）。
         */
        assertThat(sw.get("stockByInventory").asBoolean())
                .as("默认（PLATFORM）下必须是 false，否则一上线商家的高频操作就点不动了")
                .isFalse();
    }

    @Test
    @DisplayName("★★★ 按 SKU 查账：有账给明细，没账给 null，别家的也给 null")
    void itemBySkuBridgesTheTwoDomains() throws Exception {
        Shop s = shop();

        /*
         * 这是商品页与进销存之间**唯一的一条可见通路**：商家在商品页看到的
         * 「库存 1000」是平台侧的数，进销存是另一本账，两处都叫「库存」。
         */
        JsonNode hit = ok(get("/biz/inventory/item-by-sku?skuNo=" + s.skuA()), s.token());
        assertThat(hit.isNull()).as("种子这件货已经投影过来了，该给得出账").isFalse();
        for (String f : List.of("itemId", "name", "onHand", "byLocation")) {
            assertThat(hit.has(f)).as("StockItemDetail 读 %s", f).isTrue();
        }

        /*
         * **没账回 null，不是 404。**刚建的 SKU 在投影跑到之前本来就没有物料，
         * 那是常态不是错误 —— 回 404 会被端上的通用错误处理弹成「加载失败」，
         * 而商家看到的应该是「这件货还没建账」。
         */
        JsonNode none = ok(get("/biz/inventory/item-by-sku?skuNo=SK_NOT_PROJECTED_YET"), s.token());
        assertThat(none.isNull()).as("没投影过来的 SKU 要回 null").isTrue();

        /*
         * **别家的 SKU 也回 null。**itemIdOfSku 是全局反查，它不认得调用者是谁。
         * 如果这里回 403 或 NOT_FOUND，那个「异常」本身就是答案：它告诉试探的人
         * 「这个 SKU 存在，只是不属于你」。回同一个 null，什么都问不出来。
         */
        Shop other = shop();
        JsonNode foreign = ok(get("/biz/inventory/item-by-sku?skuNo=" + other.skuA()), s.token());
        assertThat(foreign.isNull())
                .as("别家的 SKU 要和「没有账」给同一个回答，否则回答本身泄露了存在性")
                .isTrue();
    }

    @Test
    @DisplayName("★★★ balances 的 locationId：数出自哪个库位，以及别家的库位读不到")
    void balancesHonoursLocationAndRejectsForeignOnes() throws Exception {
        Shop s = shop();

        /*
         * 这条钉的是调拨那个毛病（2026-09-02）：挑货弹层一律读**当前门店**的余额，
         * 而调拨的调出方可以是另一个库位 —— 商家看到「可用 30」，过账时扣的是
         * 另一个库位，`INV_INSUFFICIENT`。数字来自一个库位，扣减发生在另一个库位。
         */
        String warehouse = okText(post("/biz/inventory/locations")
                .content("{\"name\":\"对照仓\"}"), s.token);

        int here = ok(get("/biz/inventory/balances?filter=all&size=100&locationId="
                + s.location), s.token).size();
        assertThat(here)
                .as("传自己门店的库位，要和不传时一样 —— 否则这个参数根本没接上")
                .isEqualTo(ok(get("/biz/inventory/balances?filter=all&size=100"), s.token).size());

        /*
         * **对照量本身要能证伪**：新仓一件货都没有，所以它必须比有货的那个少。
         * 只断言「新仓是 0」的话，参数写错拼成了别的名字、后端整个忽略它时，
         * 这条也会绿 —— 那时两边都回当前门店的数，而当前门店恰好也可能是 0。
         */
        int fresh = ok(get("/biz/inventory/balances?filter=all&size=100&locationId="
                + warehouse), s.token).size();
        assertThat(here).as("种子给当前门店入了货，它必须非零，否则下面那条比不出东西")
                .isGreaterThan(0);
        assertThat(fresh).as("新建的仓一件货都没有 —— 数出自 locationId 指的那个库位").isZero();

        /*
         * **别家的库位读不到。** 这个参数是端上传来的一串 ID，不校验的话改一个字符
         * 就能读到别家的库存，而读接口不会报错、只会安静地回一批数。
         * 权限注解挡的是「这个人能不能看库存」，挡不住「看谁的库存」。
         */
        Shop other = shop();
        String body = mvc().perform(
                        get("/biz/inventory/balances?filter=all&size=100&locationId="
                                + other.location())
                                .header("Authorization", "Bearer " + s.token())
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())   // 信封裹着，HTTP 永远 200
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("code").asInt())
                .as("拿别家的库位去读余额必须被拒 —— 回 0 就是安静地把别人的库存给出去了")
                .isNotZero();
    }

    @Test
    @DisplayName("★★★ 明细页：byLocation 与台账分页的字段名")
    void itemDetailAndLedgerFieldsMatchBApp() throws Exception {
        Shop s = shop();

        JsonNode d = ok(get("/biz/inventory/items/" + s.itemA), s.token);
        for (String f : List.of("itemId", "name", "specText", "baseUom", "barcode", "itemCode",
                "onHand", "reserved", "available", "byLocation")) {
            assertThat(d.has(f)).as("StockItemDetail 读 %s", f).isTrue();
        }
        assertThat(d.get("byLocation").isArray()).isTrue();

        JsonNode page = ok(get("/biz/inventory/ledger?itemId=" + s.itemA + "&size=20"), s.token);
        assertThat(page.has("entries")).as("b-app 读 entries").isTrue();
        assertThat(page.has("nextCursor")).as("翻页拿服务端给的 nextCursor").isTrue();

        JsonNode row = page.get("entries").get(0);
        for (String f : List.of("id", "docKind", "docNo", "reasonCode",
                "qtyDelta", "balanceAfter", "occurredAt", "operator")) {
            assertThat(row.has(f)).as("StockLedgerRow 读 %s", f).isTrue();
        }
    }

    // ------------------------------------------------------------------ 写

    @Test
    @DisplayName("★★★ 记一笔进货：b-app 拼的 body 能过，且**过账才动库存**")
    void purchaseBodyFromBAppWorks() throws Exception {
        Shop s = shop();
        int before = onHand(s, s.itemA);

        // 逐字照 purchase-edit 页的 draftReq()
        String body = """
                {"sourceType":"PURCHASE","supplierName":"老周粮油",
                 "occurredAt":"2026-08-25T00:00:00",
                 "lines":[{"itemId":"%s","qty":20,"uom":"袋","unitCostMinor":4200}]}
                """.formatted(s.itemA);
        String no = okText(post("/biz/inventory/inbounds").content(body), s.token);

        assertThat(onHand(s, s.itemA)).as("**草稿不动库存** —— 动了说明创建即过账").isEqualTo(before);

        ok(post("/biz/inventory/inbounds/" + no + "/post"), s.token);
        assertThat(onHand(s, s.itemA)).as("过账之后才加").isEqualTo(before + 20);
    }

    @Test
    @DisplayName("★★★ 点哪个数就给哪一档 —— 数字说一个数、点下去给另一个，不报错")
    void statFiltersAreExact() throws Exception {
        Shop s = shop();

        /*
         * **先造一件真的缺货**。不造的话下面两个循环跑零次，
         * 而断言零次的测试是恒绿的 —— 撤掉后端那两档它照样过。
         * （第一版就是这样：我撤掉 shortage/stale 两个 case，测试纹丝不动。）
         */
        String outBody = """
                {"purpose":"SCRAP","reasonCode":"BROKEN","occurredAt":"2026-08-26T00:00:00",
                 "lines":[{"itemId":"%s","qty":10,"uom":"袋"}]}
                """.formatted(s.itemA);
        String outNo = okText(post("/biz/inventory/outbounds").content(outBody), s.token);
        ok(post("/biz/inventory/outbounds/" + outNo + "/post"), s.token);
        assertThat(onHand(s, s.itemA)).as("先把它清成 0，它才会缺货").isEqualTo(0);

        int all = ok(get("/biz/inventory/balances?filter=all&size=100"), s.token).size();
        int shortage = ok(get("/biz/inventory/balances?filter=shortage&size=100"), s.token).size();
        int stale = ok(get("/biz/inventory/balances?filter=stale&size=100"), s.token).size();
        int todo = ok(get("/biz/inventory/balances?filter=todo&size=100"), s.token).size();

        /*
         * 界面上那四个数是可点的，点「缺货 6」就该给这 6 条。
         * 此前 shortage / stale 这两个值后端根本不认，落到 default（todo，两者的并集）——
         * 于是点「滞销」给出的列表里混着缺货，而**没有任何报错**：
         * 返回 200、返回一列合法的货，只是不是他点的那一列。
         */
        assertThat(todo)
                .as("要处理 = 缺货 ∪ 滞销，不该比两者之和还多")
                .isLessThanOrEqualTo(shortage + stale);
        assertThat(all)
                .as("全部要至少和要处理一样多 —— 反过来说明 filter 没被认出来")
                .isGreaterThanOrEqualTo(todo);

        // **判据要能证伪**：这一列必须非空，否则下面的循环跑零次，等于什么都没验
        assertThat(shortage).as("测试店里得真有缺货的货，否则下面的循环是空转").isGreaterThan(0);

        // 精确档里不许混进另一档：缺货那一列每一条都得真的缺货
        for (JsonNode b : ok(get("/biz/inventory/balances?filter=shortage&size=100"), s.token)) {
            assertThat(b.get("flags").toString())
                    .as("filter=shortage 却返回了不缺货的行：" + b.get("name").asText())
                    .contains("SHORTAGE");
        }
        for (JsonNode b : ok(get("/biz/inventory/balances?filter=stale&size=100"), s.token)) {
            assertThat(b.get("flags").toString())
                    .as("filter=stale 却返回了不滞销的行：" + b.get("name").asText())
                    .contains("STALE");
        }
    }

    @Test
    @DisplayName("★★★ 作废：库存要退回去，且流水留痕 —— 这是「录错了怎么办」的唯一答案")
    void voidingAPostedInboundPutsStockBack() throws Exception {
        Shop s = shop();
        int before = onHand(s, s.itemA);

        String body = """
                {"sourceType":"PURCHASE","supplierName":"老周粮油",
                 "occurredAt":"2026-08-25T00:00:00",
                 "lines":[{"itemId":"%s","qty":7,"uom":"袋","unitCostMinor":4200}]}
                """.formatted(s.itemA);
        String no = okText(post("/biz/inventory/inbounds").content(body), s.token);
        ok(post("/biz/inventory/inbounds/" + no + "/post"), s.token);
        assertThat(onHand(s, s.itemA)).isEqualTo(before + 7);

        int rowsBefore = ok(get("/biz/inventory/ledger?itemId=" + s.itemA), s.token)
                .get("entries").size();

        ok(post("/biz/inventory/inbounds/" + no + "/void"), s.token);

        /*
         * 三条一起才算作废：数退回去、**流水多一行**（不是把原来那行删掉）、
         * 单据留在列表里标成已作废。
         *
         * 只验第一条会漏掉最坏的一种实现：直接扣回余额而不写流水 ——
         * 账面看着对，而「这 7 袋去哪了」从此答不出来，几个月后对账才发现。
         */
        assertThat(onHand(s, s.itemA))
                .as("作废之后库存要回到这张单之前")
                .isEqualTo(before);
        assertThat(ok(get("/biz/inventory/ledger?itemId=" + s.itemA), s.token).get("entries").size())
                .as("作废要**写一行反向流水**，不是抹掉原来那一行 —— 历史正是这些表存在的理由")
                .isGreaterThan(rowsBefore);
        assertThat(ok(get("/biz/inventory/documents?no=" + no), s.token).toString())
                .as("单据不消失，标成已作废")
                .contains("VOIDED");
    }

    @Test
    @DisplayName("★★★ 报损出库：b-app 的 body（purpose=SCRAP + reasonCode）")
    void scrapBodyFromBAppWorks() throws Exception {
        Shop s = shop();
        int before = onHand(s, s.itemA);

        String body = """
                {"purpose":"SCRAP","reasonCode":"EXPIRED","occurredAt":"2026-08-26T00:00:00",
                 "lines":[{"itemId":"%s","qty":2,"uom":"袋"}]}
                """.formatted(s.itemA);
        String no = okText(post("/biz/inventory/outbounds").content(body), s.token);
        ok(post("/biz/inventory/outbounds/" + no + "/post"), s.token);

        assertThat(onHand(s, s.itemA)).isEqualTo(before - 2);
    }

    @Test
    @DisplayName("★★★ 盘点整条链：账面数是**快照**，填数发的是**裸数组**")
    void countChainFromBAppWorks() throws Exception {
        Shop s = shop();
        int book = onHand(s, s.itemA);

        // ① 开单。b-app：http.post(path, { itemIds })
        String no = okText(post("/biz/inventory/counts")
                .content("{\"itemIds\":[\"" + s.itemA + "\"]}"), s.token);

        /*
         * ② 开单之后**再卖掉一件**。账面数必须还是开单那一刻的数 ——
         * 拿当前余额顶替的话，这一件会被算成盘亏，凭空多出一笔损失。
         */
        String outNo = okText(post("/biz/inventory/outbounds").content("""
                {"purpose":"SCRAP","reasonCode":"BROKEN","occurredAt":"2026-08-26T00:00:00",
                 "lines":[{"itemId":"%s","qty":1,"uom":"袋"}]}
                """.formatted(s.itemA)), s.token);
        ok(post("/biz/inventory/outbounds/" + outNo + "/post"), s.token);
        assertThat(onHand(s, s.itemA)).as("当前余额已经少一件").isEqualTo(book - 1);

        JsonNode doc = ok(get("/biz/inventory/counts/" + no), s.token);
        for (String f : List.of("countNo", "status", "locationId", "startedAt", "operator", "lines")) {
            assertThat(doc.has(f)).as("StockCount 读 %s", f).isTrue();
        }
        JsonNode line = doc.get("lines").get(0);
        for (String f : List.of("itemId", "name", "specText", "baseUom",
                "bookQty", "countedQty", "diffQty", "reasonCode")) {
            assertThat(line.has(f)).as("StockCountLine 读 %s", f).isTrue();
        }
        assertThat(line.get("bookQty").asInt())
                .as("**账面数是开单那一刻的快照** —— 等于当前余额就说明它是现算的，"
                        + "中间卖掉的那一件会被记成盘亏")
                .isEqualTo(book);
        assertThat(line.get("countedQty").isNull())
                .as("还没盘的是 null 不是 0 —— 0 的意思是「盘了，一件不差」")
                .isTrue();

        // ③ 填数。**裸数组**，与 b-app 的 http.put(path, lines) 一致
        ok(put("/biz/inventory/counts/" + no + "/lines")
                .content("[{\"itemId\":\"" + s.itemA + "\",\"countedQty\":" + (book - 3)
                        + ",\"reasonCode\":\"BROKEN\"}]"), s.token);

        JsonNode filled = ok(get("/biz/inventory/counts/" + no), s.token);
        assertThat(filled.get("lines").get(0).get("countedQty").asInt())
                .as("裸数组没被解成空列表 —— 解错了这里还是 null，而过账会什么都不做")
                .isEqualTo(book - 3);

        /*
         * ④ 过账。**按差异过账，不是覆盖成实盘数** —— 这两者只在
         * 「盘的过程中没动过」时才相同，而这一条正是它们分开的场景：
         *   账面 10、实盘 7 → 差异 −3；期间卖掉 1，当前是 9；过账后 9 − 3 = 6。
         * 覆盖成 7 的话，那笔卖出会被盘点悄悄吃掉 —— 卖了却没扣。
         */
        ok(post("/biz/inventory/counts/" + no + "/post"), s.token);
        assertThat(onHand(s, s.itemA))
                .as("过账按差异走：当前 %d 加上差异 −3。覆盖成实盘数会把期间那笔卖出吃掉", book - 1)
                .isEqualTo(book - 1 - 3);
    }


    @Test
    @DisplayName("★★★ 建品就要上账 —— 否则那个 SKU 在库存里根本不存在，且不报错")
    void newSkuLandsOnTheBooks() throws Exception {
        /*
         * **补的是一条断了的边**（2026-08-28）。两个域只在 `sku_no` 这一点连着，
         * 而在此之前接这一点的**只有搬运跑批** —— `upsertItem` 全仓唯一的生产调用点
         * 就在 `InventoryBackfillServiceImpl` 里。于是建 SKU 不会建账：
         * 商家在库存里看不到那件货、盘不着、进不了货，**而任何地方都不会报错**。
         * 跑批还要 worker profile，线上没有常驻调度。
         */
        String token = merchant("12600288001", "建品上账·粮油");
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT110\",\"title\":\"东北五常大米\","
                                + "\"subtitle\":\"测试\",\"cover\":\"🍚\",\"images\":[],"
                                + "\"specGroups\":[],\"skus\":[{\"optionValues\":[],"
                                + "\"price\":5900,\"stock\":0,\"saleUnit\":\"袋\"}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();

        String skuNo = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<ai.neargo.shop.product.entity.PrdSku>lambdaQuery()
                                .eq(ai.neargo.shop.product.entity.PrdSku::getGoodsNo, goodsNo))
                        .get(0).getSkuNo());

        // Outbox 是「只写库、异步投」，所以要推一把才看得到消费方的结果
        dispatcher.dispatchPending();

        assertThat(acl.itemIdOfSku(skuNo))
                .as("建了 SKU 却没上账 —— 商家在库存里找不到这件货，而没有任何地方会报错")
                .isNotNull();

        /*
         * **名字要是商品标题，不是货号。** 搬运曾经传 `goodsNo`，
         * 于是库存清单上是一列 `G0001 · 10斤装`，商家认不出是什么货。
         */
        String ownerId = acl.ownerOfSku(skuNo);
        assertThat(query.itemDetail(ownerId, acl.itemIdOfSku(skuNo)).name())
                .as("物料名应当是商品标题")
                .isEqualTo("东北五常大米");

        /*
         * **上了账还不够 —— 得挑得到。**
         *
         * 余额行是按需建的（`ensureBalanceRow`），一件从没进过货的物料没有那一行；
         * 而挑货弹层此前读的是 `balances`，于是这件货在弹层里不存在，
         * **商家没法给它记第一笔进货** —— 断边 ① 只解决了一半。
         * 线上 2026-08-28 就有一件这样的：207 个物料、206 行余额。
         */
        String pick = ok(get("/biz/inventory/pickable?q=五常"), token).toString();
        assertThat(pick)
                .as("刚建的货（0 库存、没有余额行）必须挑得到，否则第一笔进货无从下手")
                .contains(acl.itemIdOfSku(skuNo));
    }

    @Test
    @DisplayName("★★ 下架的货在挑货弹层里标得出来，而没同步过的不许被当成下架")
    void offSaleItemsAreMarkedAndUnknownIsNotGuessed() throws Exception {
        Shop s = shop();
        String ownerId = acl.ownerOfSku(s.skuA);

        /*
         * **先验默认那一半。** `source_on_sale` 是 2026-08-30 才加的列，
         * 存量物料全是 null。把 null 当成下架，就是给一整批还在正常卖的货
         * 凭空贴上「已下架」—— 而商家没有任何办法看出那是假的。
         */
        assertThat(pickableFlagsOf(s, s.itemA))
                .as("还没同步过上架状态的货，不许被标成已下架")
                .doesNotContain("OFF_SALE");

        acl.markItemOnSale(s.entityNo, s.skuA, false);
        assertThat(pickableFlagsOf(s, s.itemA))
                .as("下架之后要标出来 —— 线上有 13 组同名同规格的货，不标就分不出是哪一件")
                .contains("OFF_SALE");

        // 上回架要能回来：只加不减的标记会让商家永远看着一件在售的货写着「已下架」
        acl.markItemOnSale(s.entityNo, s.skuA, true);
        assertThat(pickableFlagsOf(s, s.itemA))
                .as("重新上架后标记要消失")
                .doesNotContain("OFF_SALE");
    }

    /** 从挑货接口里取某件货的 flags —— 验的是**端上真正读到的那一份**，不是服务层内部状态 */
    private List<String> pickableFlagsOf(Shop s, String itemId) throws Exception {
        JsonNode arr = ok(get("/biz/inventory/pickable"), s.token);
        for (JsonNode n : arr) {
            if (itemId.equals(n.path("itemId").asString())) {
                List<String> out = new java.util.ArrayList<>();
                n.path("flags").forEach(f -> out.add(f.asString()));
                return out;
            }
        }
        throw new AssertionError("挑货列表里没有 " + itemId + " —— 这件货挑不到，那是另一个缺陷");
    }

    @Test
    @DisplayName("★★★ 调拨整条链：发出 → 在途 → 收货，合计守恒")
    void transferChainFromBAppWorks() throws Exception {
        Shop s = shop();
        int before = onHand(s, s.itemA);

        JsonNode locs = ok(get("/biz/inventory/locations"), s.token);
        assertThat(locs.isArray()).isTrue();
        String warehouse = okText(post("/biz/inventory/locations")
                .content("{\"name\":\"城西仓-" + SEQ.incrementAndGet() + "\"}"), s.token);

        String no = okText(post("/biz/inventory/transfers").content("""
                {"fromLocationId":"%s","toLocationId":"%s",
                 "lines":[{"itemId":"%s","qty":3}]}
                """.formatted(s.location, warehouse, s.itemA)), s.token);

        ok(post("/biz/inventory/transfers/" + no + "/ship"), s.token);

        JsonNode doc = ok(get("/biz/inventory/transfers/" + no), s.token);
        for (String f : List.of("transferNo", "status", "fromLocationId", "fromLocationName",
                "toLocationId", "toLocationName", "shippedAt", "receivedAt", "totalQty", "lines")) {
            assertThat(doc.has(f)).as("StockTransfer 读 %s", f).isTrue();
        }
        assertThat(doc.get("lines").isEmpty())
                .as("发出之后行取自出库单，不该是空的")
                .isFalse();

        ok(post("/biz/inventory/transfers/" + no + "/receive"), s.token);
        assertThat(onHand(s, s.itemA))
                .as("**全程总量守恒** —— 调拨是搬家不是增减，货只是换了个库位")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("★★ 改数（明细页那颗按钮）：b-app 的 {itemId,countedQty,reasonCode}")
    void adjustBodyFromBAppWorks() throws Exception {
        Shop s = shop();

        ok(post("/biz/inventory/adjust")
                .content("{\"itemId\":\"" + s.itemA + "\",\"countedQty\":7,\"reasonCode\":\"BROKEN\"}"),
                s.token);

        assertThat(onHand(s, s.itemA)).as("改数按盘点走，实存变成点出来的那个数").isEqualTo(7);
    }

    @Test
    @DisplayName("★★ 单据 / 报表 / 库位三个读口的字段名")
    void docsReportAndLocationFieldsMatchBApp() throws Exception {
        Shop s = shop();

        JsonNode docs = ok(get("/biz/inventory/documents?size=50"), s.token);
        assertThat(docs.isArray()).isTrue();
        assertThat(docs.isEmpty()).as("种子里入过货，至少有一张单").isFalse();
        JsonNode d = docs.get(0);
        for (String f : List.of("kind", "docNo", "status", "subtitle",
                "totalQty", "occurredAt", "operator")) {
            assertThat(d.has(f)).as("StockDocument 读 %s", f).isTrue();
        }

        JsonNode m = ok(get("/biz/inventory/report/monthly?month=2026-08"), s.token);
        for (String f : List.of("month", "opening", "purchased", "sold",
                "lost", "adjusted", "closing", "balanced",
                // 成本两项。**没有毛利** —— 毛利要减收入，而售价不在这个域
                "soldCostMinor", "lostCostMinor")) {
            assertThat(m.has(f)).as("StockMonthly 读 %s —— 界面上要能算得通那条式子", f).isTrue();
        }

        JsonNode rank = ok(get("/biz/inventory/report/ranking?type=slow&size=5"), s.token);
        assertThat(rank.isArray()).isTrue();

        JsonNode locs = ok(get("/biz/inventory/locations"), s.token);
        JsonNode l = locs.get(0);
        for (String f : List.of("locationId", "name", "kind", "sourceLocationId")) {
            assertThat(l.has(f)).as("StockLocation 读 %s", f).isTrue();
        }
    }

    @Test
    @DisplayName("★★ 发货源可以清空 —— 端上传的 null 会被 http 层剪掉，收到的是空 body")
    void clearingShippingSourceWorks() throws Exception {
        Shop s = shop();
        String warehouse = okText(post("/biz/inventory/locations")
                .content("{\"name\":\"清空测试仓-" + SEQ.incrementAndGet() + "\"}"), s.token);

        ok(put("/biz/inventory/locations/" + s.location + "/source")
                .content("{\"sourceLocationId\":\"" + warehouse + "\"}"), s.token);

        /*
         * 端上选「发自己的」传的是 `{sourceLocationId: null}`，而 http 层的
         * pruneUndefined **把 null 一起剪掉**，后端实际收到的是 `{}`。
         * 两者在这个接口上结果相同（记录里都是 null），但那是巧合不是设计 ——
         * 一旦哪天「显式 null」与「不传」要分开，这里会静默走错分支。
         */
        ok(put("/biz/inventory/locations/" + s.location + "/source").content("{}"), s.token);

        JsonNode locs = ok(get("/biz/inventory/locations"), s.token);
        for (JsonNode l : locs) {
            if (s.location.equals(l.get("locationId").asString())) {
                assertThat(l.get("sourceLocationId").isNull()).as("发货源应当被清空").isTrue();
                return;
            }
        }
        assertThat(false).as("门店库位应当还在").isTrue();
    }

    // ── 供应商档案（S2）────────────────────────────────────────────────

    @Test
    @DisplayName("★★★ 同一商家不许有两个同名供应商 —— 这条不成立，整张表就白建了")
    void duplicateSupplierNameIsRejected() throws Exception {
        Shop s = shop();

        String no = ok(post("/biz/inventory/suppliers")
                .content("{\"name\":\"老周粮油\",\"contactName\":\"周老板\"}"), s.token)
                .get("supplierNo").asString();
        assertThat(no).startsWith("SUP");

        /*
         * 建这张表的理由就是「名字会漂」。要是同名能建第二条，
         * 漂移只是从「单据上的名字」换到「档案里的名字」继续长 —— 一样对不上账。
         */
        String dup = mvc().perform(post("/biz/inventory/suppliers")
                        .header("Authorization", "Bearer " + s.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"老周粮油\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(dup).get("code").asInt())
                .as("重名必须被拒（10409 CONFLICT）；放过去这张表就白建了")
                .isEqualTo(10409);

        /*
         * **前后空格也算同一家。** 不 trim 的话「老周粮油 」建得成，
         * 而它在列表里与「老周粮油」长得一模一样 —— 商家分辨不出，报表却分成两行。
         */
        String pad = mvc().perform(post("/biz/inventory/suppliers")
                        .header("Authorization", "Bearer " + s.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  老周粮油  \"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(pad).get("code").asInt())
                .as("带空格的同名也要被拒 —— 否则列表里两行长得一样")
                .isEqualTo(10409);

        // 对照量：确实只建成了一条，而不是「两条都没建成」那种假绿
        assertThat(ok(get("/biz/inventory/suppliers"), s.token).size())
                .as("应当恰好一条")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 停用之后挑不到，但管理页仍看得见 —— 两向都要验")
    void archivedSupplierDisappearsFromPickerButStaysInAdmin() throws Exception {
        Shop s = shop();
        String no = ok(post("/biz/inventory/suppliers")
                .content("{\"name\":\"要停用的那家\"}"), s.token).get("supplierNo").asString();

        ok(post("/biz/inventory/suppliers/" + no + "/active").content("{\"active\":false}"), s.token);

        assertThat(ok(get("/biz/inventory/suppliers?activeOnly=true"), s.token).size())
                .as("挑供应商时不该出现停用的")
                .isZero();
        assertThat(ok(get("/biz/inventory/suppliers?activeOnly=false"), s.token).size())
                .as("管理页要看得见它，否则没法再启用回来")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ 脚手架

    private record Shop(String token, String entityNo, String location, String itemA, String skuA) {
    }

    /**
     * 一家有货的店。**每个用例一家** —— 用例之间不共享种子，
     * 避免「单独跑绿、全量跑红」，以及报错永远不指向真因。
     */
    private Shop shop() throws Exception {
        int seq = SEQ.incrementAndGet();
        String phone = "126002%05d".formatted(seq % 100000);
        String token = merchant(phone, "进销存接口测试店-" + seq);

        JsonNode ctx = ok(get("/biz/context"), token);
        String entityNo = ctx.get("merchantNo").asString();

        /*
         * **与控制器同一套解析**：它用的是当前门店的库位
         *（`locationIdOf(merchantNo, currentStoreNo)`），不是默认库位。
         * 用默认库位的话，种子入的货落在别处，调拨会报「库存不足」，
         * 而那看起来像调拨坏了。
         */
        JsonNode ctxNode = ctx;
        String storeNo = ctxNode.get("currentStoreNo").isNull()
                ? null : ctxNode.get("currentStoreNo").asString();
        /*
         * **再过一次 resolveStockLocation** —— 控制器的 location() 是两步：
         * locationIdOf 之后还要解析发货源。原来这里只做了第一步，两者相同纯属
         * 「那时门店库位没有发货源」；2026-08-27 新建门店库位开始默认指向主体默认仓，
         * 差别就出来了：种子的货入到仓库，而调拨从门店发，报「库存不足」，
         * 看起来像调拨坏了 —— 正是本注释开头警告的那一幕，只是换了个方向。
         */
        String location = locations.resolveStockLocation(
                acl.ownerIdOf(entityNo), acl.locationIdOf(entityNo, storeNo));
        String skuA = "SKU-BIZINV-" + seq;
        String itemA = acl.upsertItem(entityNo, skuA, "东北大米", "5斤装",
                "6901234567892", "LM-05", "袋");
        acl.upsertItem(entityNo, "SKU-BIZINV-B" + seq, "土鸡蛋", "30枚装", null, null, "箱");
        acl.upsertItem(entityNo, "SKU-BIZINV-C" + seq, "陈醋", "500ml", null, null, "瓶");

        // 先入一批货 —— 一家没有货的店，九屏上大部分动作都无从谈起
        String no = okText(post("/biz/inventory/inbounds").content("""
                {"sourceType":"PURCHASE","supplierName":"老周粮油",
                 "occurredAt":"2026-08-01T00:00:00",
                 "lines":[{"itemId":"%s","qty":10,"uom":"袋","unitCostMinor":4200}]}
                """.formatted(itemA)), token);
        ok(post("/biz/inventory/inbounds/" + no + "/post"), token);

        return new Shop(token, entityNo, location, itemA, skuA);
    }

    private int onHand(Shop s, String itemId) throws Exception {
        return ok(get("/biz/inventory/items/" + itemId), s.token).get("onHand").asInt();
    }

    /**
     * 发一个请求，断言业务码为 0，返回 {@code data}。
     *
     * <p><b>响应统一包在 {@code {code,msg,data}} 里，HTTP 永远是 200</b> ——
     * 断言写在 HTTP 状态上会全程绿着。
     */
    private JsonNode ok(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                        String token) throws Exception {
        String body = mvc().perform(req.header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode env = json.readTree(body);
        assertThat(env.get("code").asInt())
                .as("业务码；非 0 时 b-app 会抛，界面显示成「保存失败」。msg=%s", env.get("msg"))
                .isZero();
        return env.get("data");
    }

    /**
     * 新建单据的返回：{@code data} 是 <b>{@code {no}} 而不是裸字符串</b>。
     *
     * <p>裸字符串走的是 {@code StringHttpMessageConverter}，{@code ApiResponseWrapper}
     * 把它**故意排除**在信封之外 —— 于是端上的 http 客户端读 {@code body.code} 读不到，
     * 直接抛「响应格式不符合契约」。<b>服务端把单建好了，端上报错</b>，
     * 商家再点一次就是两张草稿单。这条断言就是钉住那一层信封还在。
     */
    private String okText(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                          String token) throws Exception {
        JsonNode data = ok(req, token);
        assertThat(data.has("no"))
                .as("新建单据要返回 {no}；返回裸字符串的话信封会被跳过，端上一律报「响应格式不符合契约」")
                .isTrue();
        return data.get("no").asString();
    }

    private String merchant(String phone, String name) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(post("/mp/merchant/apply")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();

        String bd = opsLogin();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        // 商家身份是登录时解析进 BizContext 的，旧 token 上还没有
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String opsLogin() throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bd\",\"password\":\"bd123\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
