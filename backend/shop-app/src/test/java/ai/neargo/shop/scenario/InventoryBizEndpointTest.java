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

    // ------------------------------------------------------------------ 脚手架

    private record Shop(String token, String entityNo, String location, String itemA) {
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
        String itemA = acl.upsertItem(entityNo, "SKU-BIZINV-" + seq, "东北大米", "5斤装",
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

        return new Shop(token, entityNo, location, itemA);
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
