package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.SysOutboxMapper;
import ai.neargo.shop.invbridge.InventoryBackfillService;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.inventory.entity.InvOutbox;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboxMapper;
import ai.neargo.shop.inventory.service.InventoryEventSink;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.InventorySnapshotService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.service.OutboundService;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.inventory.service.StockCountService;
import ai.neargo.shop.inventory.service.StockQueryService;
import ai.neargo.shop.inventory.service.TransferService;
import ai.neargo.shop.inventory.support.InvEnums;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 进销存主链路。
 *
 * <p>守的是六条不变式里能在单进程内验的那几条，**每一条都对着一件会真出事的事**：
 * <ol>
 *   <li>过账幂等 —— 重复点「过账」不会把库存加两遍</li>
 *   <li>预留不进销量 —— 没付钱的单不该出现在动销榜上</li>
 *   <li>不允许扣成负数 —— 错误停在录入处，而不是流进报表</li>
 *   <li>盘点用**开单那一刻的账面数**算差异 —— 否则中间卖掉的量会被算成盘亏</li>
 *   <li>调拨全程总量守恒（含在途）—— 不守恒的账发现不了错误</li>
 *   <li>销售出库不接受手工创建 —— 否则商家能凭空造销量</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryFlowTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    SkuMapper skuMapper;
    @Autowired
    ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper goodsMapper;
    @Autowired
    InventoryAclService acl;
    @Autowired
    LocationService locations;
    @Autowired
    InboundService inbound;
    @Autowired
    OutboundService outbound;
    @Autowired
    ReservationService reservations;
    @Autowired
    StockCountService counts;
    @Autowired
    TransferService transfers;
    @Autowired
    StockQueryService query;

    @Autowired
    InventoryBackfillService backfill;

    @Autowired
    InventorySnapshotService snapshots;

    /**
     * **required = false**：一个实现都没有时要让断言说出「没有出口」，
     * 而不是整个测试类因注入失败起不来 —— 那时报的是 UnsatisfiedDependency，
     * 看的人会去查 Spring 装配，而真正的问题是这个 SPI 没人实现。
     */
    @Autowired(required = false)
    java.util.List<InventoryEventSink> sinks;
    @Autowired
    OutboxMapper invOutboxMapper;
    @Autowired
    SysOutboxMapper sysOutboxMapper;

    @Test
    @DisplayName("★★★ 进货 → 下单预留 → 支付出库：预留不动实存，付款才扣")
    void reserveThenCommit() {
        Fixture f = fixture();
        inbound.postDirectly(purchase(f, 100), "老板");
        assertThat(onHand(f)).isEqualTo(100);

        reservations.reserve(f.owner, "SO-" + f.seq, List.of(
                new ReservationService.Line(f.item, f.location, 3)), 900);
        // 预留只动 reserved：实存一件没少，可用少了 3
        assertThat(onHand(f)).isEqualTo(100);
        assertThat(available(f)).isEqualTo(97);

        reservations.commit(f.owner, "SO-" + f.seq, "SYSTEM");
        assertThat(onHand(f)).isEqualTo(97);
        assertThat(available(f)).isEqualTo(97);
    }

    @Test
    @DisplayName("★★★ 重复 commit 不会扣两遍 —— 幂等靠自然键，不靠调用方记得只调一次")
    void commitIsIdempotent() {
        Fixture f = fixture();
        inbound.postDirectly(purchase(f, 10), "老板");
        reservations.reserve(f.owner, "SO-" + f.seq, List.of(
                new ReservationService.Line(f.item, f.location, 2)), 900);

        String first = reservations.commit(f.owner, "SO-" + f.seq, "SYSTEM");
        String again = reservations.commit(f.owner, "SO-" + f.seq, "SYSTEM");

        assertThat(again).isEqualTo(first);
        assertThat(onHand(f)).isEqualTo(8);
    }

    @Test
    @DisplayName("★★★ 释放后可用回来，且没有产生任何流水 —— 没成交的单不进销量")
    void releaseLeavesNoLedger() {
        Fixture f = fixture();
        inbound.postDirectly(purchase(f, 10), "老板");
        int before = query.ledger(f.owner, f.item, null, f.location, null, 50).entries().size();

        reservations.reserve(f.owner, "SO-" + f.seq, List.of(
                new ReservationService.Line(f.item, f.location, 4)), 900);
        reservations.release(f.owner, "SO-" + f.seq);

        assertThat(available(f)).isEqualTo(10);
        assertThat(query.ledger(f.owner, f.item, null, f.location, null, 50).entries()).hasSize(before);
    }

    @Test
    @DisplayName("★★★ 出库不许把实存扣成负数 —— 错误停在录入处，不流进报表")
    void cannotGoNegative() {
        Fixture f = fixture();
        inbound.postDirectly(purchase(f, 2), "老板");

        assertThatThrownBy(() -> outbound.postDirectly(new OutboundService.Draft(
                f.owner, f.location, InvEnums.OutboundPurpose.SCRAP, null, null,
                InvEnums.Reason.BROKEN, LocalDateTime.now(), null,
                List.of(new OutboundService.Line(f.item, 5, null))), "张伟"))
                .isInstanceOf(BizException.class);

        assertThat(onHand(f)).isEqualTo(2);
    }

    @Test
    @DisplayName("★★★ 销售出库不接受手工创建 —— 否则商家能凭空造销量")
    void saleCannotBeHandMade() {
        Fixture f = fixture();
        assertThatThrownBy(() -> outbound.createDraft(new OutboundService.Draft(
                f.owner, f.location, InvEnums.OutboundPurpose.SALE, "SO-fake", null, null,
                LocalDateTime.now(), null, List.of(new OutboundService.Line(f.item, 1, null)))))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★★ 盘点按**开单那一刻**的账面数算差异 —— 中间卖掉的不算盘亏")
    void countUsesSnapshotBookQty() {
        Fixture f = fixture();
        inbound.postDirectly(purchase(f, 10), "老板");

        String countNo = counts.open(f.owner, f.location, List.of(f.item), "张伟");
        // 开单之后又卖掉 2 件（账面变成 8）
        reservations.reserve(f.owner, "SO-" + f.seq, List.of(
                new ReservationService.Line(f.item, f.location, 2)), 900);
        reservations.commit(f.owner, "SO-" + f.seq, "SYSTEM");
        assertThat(onHand(f)).isEqualTo(8);

        // 实盘 9：相对**开单时的 10** 是 −1，不是相对现在的 8 算成 +1
        counts.fill(f.owner, countNo, List.of(
                new StockCountService.Filled(f.item, 9, InvEnums.Reason.BROKEN)));
        counts.post(f.owner, countNo, "张伟");

        // 8 − 1 = 7：盘亏那一件被扣掉，中间卖掉的两件不重复计
        assertThat(onHand(f)).isEqualTo(7);
    }

    @Test
    @DisplayName("★★★ 调拨全程总量守恒 —— 包括货在在途库位上的那一段")
    void transferConservesTotal() {
        Fixture f = fixture();
        String to = locations.createWarehouse(f.owner, "二店-" + f.seq, "老板");
        inbound.postDirectly(purchase(f, 20), "老板");
        int total = totalOf(f.owner, f.item);

        String no = transfers.create(f.owner, f.location, to,
                List.of(new TransferService.Line(f.item, 8)), "老板");
        transfers.ship(f.owner, no, "老板");
        // 发出之后、收到之前：来源少了 8，在途多了 8，**合计一件不差**
        assertThat(totalOf(f.owner, f.item)).isEqualTo(total);

        transfers.receive(f.owner, no, "老板");
        assertThat(totalOf(f.owner, f.item)).isEqualTo(total);
    }

    @Test
    @DisplayName("★★ 作废已过账的入库单写反向流水，不删原行")
    void voidWritesReverseLedger() {
        Fixture f = fixture();
        String no = inbound.postDirectly(purchase(f, 6), "老板");
        int rowsBefore = query.ledger(f.owner, f.item, null, f.location, null, 50).entries().size();

        inbound.voidOrder(f.owner, no, "老板");

        assertThat(onHand(f)).isZero();
        // 原行还在，多出一行反向 —— 不是把那一行改掉或删掉
        assertThat(query.ledger(f.owner, f.item, null, f.location, null, 50).entries())
                .hasSize(rowsBefore + 1);
    }

    @Test
    @DisplayName("★★★ 搬过来的条目要有**商品名** —— 不是货号，那个商家认不出来")
    void migratedItemCarriesGoodsTitle() {
        /*
         * 线上实测到的：库存清单上是一列 `G0001 · 10斤装`、
         * `G202608172140220000026 · 500g`。搬运把 `goodsNo` 当成 name 传了，
         * 而可读的名字在 `prd_goods.title` 上，SKU 上没有。
         *
         * 界面照常渲染、接口照常 200 —— 只是商家看不懂自己的库存。
         */
        int seq = SEQ.incrementAndGet();
        String goodsNo = "G-TITLE-" + seq;
        String skuNo = "SKU-TITLE-" + seq;
        String title = "东北五常大米-" + seq;

        PrdGoods goods = new PrdGoods();
        goods.setGoodsNo(goodsNo);
        goods.setEntityNo("E-TITLE-" + seq);
        goods.setTitle(title);
        goods.setType("STANDARD");
        goodsMapper.insert(goods);

        PrdSku sku = new PrdSku();
        sku.setSkuNo(skuNo);
        sku.setGoodsNo(goodsNo);
        sku.setEntityNo("E-TITLE-" + seq);
        sku.setMarket("CN");
        sku.setStock(12);
        sku.setLockedStock(0);
        sku.setPrice(3900L);
        skuMapper.insert(sku);

        try {
            Long cursor = null;
            do {
                cursor = backfill.run(false, 500, cursor).nextAfterId();
            } while (cursor != null);

            String ownerId = acl.ownerOfSku(skuNo);
            String itemId = acl.itemIdOfSku(skuNo);
            assertThat(itemId).as("前提：这个 SKU 应当已经搬过来了").isNotNull();

            assertThat(query.itemDetail(ownerId, itemId).name())
                    .as("条目名是货号而不是商品名 —— 商家在库存清单上看到的就是这一串")
                    .isEqualTo(title);
        } finally {
            skuMapper.delete(Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getSkuNo, skuNo));
            goodsMapper.delete(Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getGoodsNo, goodsNo));
        }
    }

    @Test
    @DisplayName("★★★ 搬完之后**从门店视角**要看得见货 —— 否则商家点进库存是一片空")
    void storeViewSeesMigratedStock() {
        /*
         * 复现线上那一幕：商家的库存是主体级的（一行门店库存都没有），
         * 搬运把它落到默认仓；而 B 端九屏解析到的是**门店自己那个空库位**，
         * 于是「库存」页一件都没有，货却一件没少地躺在仓库里，且不报错。
         *
         * 平台侧的语义在 StockPortImpl：按 SKU 判定，该 SKU 一行门店库存都没有时
         * 就是主体级，**每个门店都从主体池卖** —— 门店确实是从那个仓取货的。
         */
        int seq = SEQ.incrementAndGet();
        String entityNo = "E-STOREVIEW-" + seq;
        String skuNo = "SKU-STOREVIEW-" + seq;
        String storeNo = "ST-STOREVIEW-" + seq;

        PrdSku sku = new PrdSku();
        sku.setSkuNo(skuNo);
        sku.setGoodsNo("G-STOREVIEW-" + seq);
        sku.setEntityNo(entityNo);
        sku.setMarket("CN");
        sku.setStock(60);          // 主体级库存
        sku.setLockedStock(0);
        sku.setPrice(2500L);
        skuMapper.insert(sku);

        try {
            String ownerId = acl.ownerIdOf(entityNo);

            // ① 新建的门店库位必须自带发货源 —— 不带就会解析到自己那个空库位
            String storeLoc = acl.locationIdOf(entityNo, storeNo);
            assertThat(locations.resolveStockLocation(ownerId, storeLoc))
                    .as("新建门店库位没有发货源 —— 商家第一次点开库存就会看到空的")
                    .isEqualTo(locations.defaultLocation(ownerId));

            // ② 把发货源抹掉，模拟「修复之前就已经建出来的」那些库位
            locations.setSource(ownerId, storeLoc, null, "测试");
            assertThat(locations.resolveStockLocation(ownerId, storeLoc)).isEqualTo(storeLoc);

            // ③ 真搬一遍（扫到末尾，确保这个新 SKU 被扫到）
            Long cursor = null;
            do {
                cursor = backfill.run(false, 500, cursor).nextAfterId();
            } while (cursor != null);

            // ④ 按 B 端的解析方式取数：必须看得见
            String resolved = locations.resolveStockLocation(ownerId, storeLoc);
            assertThat(resolved)
                    .as("搬运没有把空的门店库位指回主体默认仓")
                    .isEqualTo(locations.defaultLocation(ownerId));
            assertThat(query.summary(ownerId, resolved).itemCount())
                    .as("从门店视角一件都看不到 —— 货在默认仓，而九屏看的是门店库位")
                    .isGreaterThan(0);
        } finally {
            // 共享库：造的 SKU 要收走，否则别的用例只跑一批时会被它挤掉 fixture
            skuMapper.delete(Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getSkuNo, skuNo));
        }
    }

    @Test
    @DisplayName("★★★ 只算不写就是一行都不写 —— dry-run 曾经建了 owner/item/ref/location")
    void dryRunWritesNothing() {
        /*
         * 造一个**一定没搬过**的 SKU。
         *
         * 不造的话，库里已经全搬完时 upsertItem 什么都不会插，这条断言就是空的 ——
         * 它会永远绿着，而它本来要拦的正是「排练写了真数据」。
         */
        int seq = SEQ.incrementAndGet();
        String skuNo = "SKU-DRY-" + seq;
        PrdSku sku = new PrdSku();
        sku.setSkuNo(skuNo);
        sku.setGoodsNo("G-DRY-" + seq);
        sku.setEntityNo("E-DRY-" + seq);
        sku.setMarket("CN");
        sku.setStock(7);
        sku.setLockedStock(0);
        sku.setPrice(1000L);
        skuMapper.insert(sku);

        try {
            assertThat(acl.itemIdOfSku(skuNo)).as("前提：它还没搬过").isNull();

            // 扫到末尾 —— 只跑一批的话这个新 SKU 可能压根没被扫到
            Long cursor = null;
            int pending = 0;
            do {
                InventoryBackfillService.Report r = backfill.run(true, 500, cursor);
                cursor = r.nextAfterId();
                pending += r.pending();
            } while (cursor != null);

            assertThat(pending).as("没搬过的必须出现在待搬里").isGreaterThan(0);
            assertThat(acl.itemIdOfSku(skuNo))
                    .as("「只算不写」写了主数据 —— ownerIdOf/upsertItem/locationIdOf 都是"
                            + "「没有就建」，而它们原来在 dryRun 判断之前无条件执行")
                    .isNull();
            assertThat(acl.ownerOfSku(skuNo)).as("业主也不该被建出来").isNull();
        } finally {
            /*
             * **必须把这个 SKU 收走。** 留在库里的话，别的用例里
             * `backfill.run(false, 500, null)` 只跑一批 —— 多出来的 SKU 会把
             * 它自己的 fixture 挤出那一批，于是它的 SKU 没被搬，断言在
             * 「付款之后实存真扣」那一行红掉，而报错与 dry-run 毫无关系。
             * 单独跑绿、合起来跑红，就是这么来的。
             */
            skuMapper.delete(Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getSkuNo, skuNo));
        }
    }

    @Test
    @DisplayName("★★★ 存量搬运幂等 —— 重跑不会把库存搬两遍")
    void backfillIsIdempotent() {
        // 平台侧的存量（DevSeeder 种的 SKU）搬一批。**同一个窗口**跑两次
        InventoryBackfillService.Report first = backfill.run(false, 50, null);
        int movedFirst = first.moved();
        assertThat(movedFirst).as("第一轮应当搬到东西").isGreaterThan(0);

        InventoryBackfillService.Report second = backfill.run(false, 50, null);
        /*
         * 第二轮一条都不该再搬。
         *
         * 幂等靠的是 INIT 单（source_type=INIT + source_ref=平台键），
         * **不是「余额是不是 0」** —— 搬完之后正常出入库会让余额变成任何数，
         * 拿它当判据的话，卖掉几件之后重跑会再搬一遍。
         */
        assertThat(second.moved()).as("重跑不该再搬").isZero();
        assertThat(second.skipped()).as("应当全部跳过").isGreaterThan(0);
    }

    @Test
    @DisplayName("★★★ 搬完之后对差必须干净 —— 这是 G3 闸门的判据")
    void backfillLeavesNoDiff() {
        // **搬到末尾**再对差。只搬一批就对差，等于拿「看过的那些」当「全部」
        Long cursor = null;
        do {
            InventoryBackfillService.Report r = backfill.run(false, 50, cursor);
            cursor = r.nextAfterId();
        } while (cursor != null);

        InventoryBackfillService.Report diff = backfill.diffOnly(5000);

        assertThat(diff.clean())
                .as("搬过的条目两边的数必须一致且没有待搬的，否则不得切真相源；"
                        + "差异：%s，待搬：%d", diff.diffs(), diff.pending())
                .isTrue();
    }

    @Test
    @DisplayName("★★★ 搬运会**前进** —— 没有游标时第二轮扫的是同一批，第 501 个永远搬不到")
    void backfillAdvancesPastTheFirstBatch() {
        // 一次只搬一个，逼出「有没有前进」这件事
        InventoryBackfillService.Report first = backfill.run(false, 1, null);
        assertThat(first.nextAfterId())
                .as("还没扫完就必须给出下一轮的游标 —— 没有它，下一轮又从第一行开始")
                .isNotNull();

        InventoryBackfillService.Report second = backfill.run(false, 1, first.nextAfterId());
        assertThat(second.scannedSkus()).as("第二轮应当扫到东西").isGreaterThan(0);
        assertThat(second.nextAfterId())
                .as("两轮的游标必须不同，相同就说明原地踏步")
                .isNotEqualTo(first.nextAfterId());
    }

    @Test
    @DisplayName("★★★ 还有没搬的时候 clean 必须是 false —— 它是闸门，不是「看过的那些没问题」")
    void pendingKeepsTheGateClosed() {
        // 只搬一个，剩下的都还没搬
        backfill.run(false, 1, null);
        InventoryBackfillService.Report diff = backfill.diffOnly(5000);

        assertThat(diff.pending())
                .as("没搬的必须出现在报告里；原来它既不算 moved 也不算 skipped，一个字都不出现")
                .isGreaterThan(0);
        assertThat(diff.clean())
                .as("还有 %d 个没搬就说「干净」，等于切换那天这些货全都卖不了", diff.pending())
                .isFalse();
    }

    @Test
    @DisplayName("★★★ 零库存 SKU 不算「待搬」—— 否则这道闸门永远红，等于没有闸门")
    void zeroStockSkuDoesNotKeepTheGateRedForever() {
        /*
         * 写路径自己的规则是「0 库存不落单」（几百个从没进过货的 SKU 会产生
         * 几百张「入 0 件」的期初单，对账上一点用都没有）。而只读对差原来一律
         * 把「没搬过」记成 pending —— 同一件事，两条路给了两个答案。
         *
         * 后果不是数字难看，是**恒红**：clean 要求 pending==0，而零库存是常态
         * （每个新建 SKU 在第一次入库前都是 0），于是「对差连续 N 天为零」
         * 这个 D2 判据永远不可能成立。2026-08-29 inv-recon 上线首跑就撞上了：
         * 209 个 SKU、差异 0，却因 3 个零库存 SKU 报 FAILED。
         *
         * held>0 仍然算待搬 —— 那说明有人占着货而进销存侧一无所知，
         * 切过去那部分会重新可售。所以这里额外造一个 qty=0/held=3 的，
         * 确认它没有被一起放行。
         */
        int seq = SEQ.incrementAndGet();
        String zeroSku = "SKU-ZERO-" + seq;
        String heldSku = "SKU-HELD-" + seq;

        PrdSku zero = new PrdSku();
        zero.setSkuNo(zeroSku);
        zero.setGoodsNo("G-ZERO-" + seq);
        zero.setEntityNo("E-ZERO-" + seq);
        zero.setMarket("CN");
        zero.setStock(0);
        zero.setLockedStock(0);
        zero.setPrice(1000L);

        PrdSku held = new PrdSku();
        held.setSkuNo(heldSku);
        held.setGoodsNo("G-HELD-" + seq);
        held.setEntityNo("E-HELD-" + seq);
        held.setMarket("CN");
        held.setStock(0);
        held.setLockedStock(3);     // 占着货，进销存侧却不知道
        held.setPrice(1000L);

        /*
         * **必须先建物料**：pending 有两条来路 —— 一条是「连物料都没有」
         * （那时 continue，不看库存），一条是 moveOne 里「有物料但没搬过」。
         * 生产上那 3 个零库存 SKU 是**有物料**的（209 个 SKU 对 209 条 item_ref），
         * 走的是第二条。测试里直接 insert 一个裸 SKU 会走第一条 —— 那验的是另一件事。
         */
        acl.upsertItem(zero.getEntityNo(), zeroSku, "零库存米", "5斤装", null, null, "BAG");
        acl.upsertItem(held.getEntityNo(), heldSku, "占货米", "5斤装", null, null, "BAG");

        int baseline = backfill.diffOnly(5000).pending();
        skuMapper.insert(zero);

        try {
            /*
             * **判据用「前后差值」，不用「大于零」。**
             * 只断言 pending>0 的话，没有修复时它同样成立（零库存那个也被算进去），
             * 那条断言证明不了任何事 —— 今晚已经在别处栽过一次同样的坑。
             */
            int withZeroOnly = backfill.diffOnly(5000).pending();
            assertThat(withZeroOnly)
                    .as("插入一个 qty=0/held=0 的 SKU 之后 pending 不该增加 —— "
                            + "增加就说明零库存被算成了待搬，闸门会永远红")
                    .isEqualTo(baseline);

            skuMapper.insert(held);
            InventoryBackfillService.Report r = backfill.diffOnly(5000);

            assertThat(r.pending())
                    .as("qty=0/held=3 的必须算待搬 —— 有人占着货而进销存侧不知道，"
                            + "切过去那部分会重新可售")
                    .isEqualTo(withZeroOnly + 1);
            assertThat(r.diffs())
                    .as("零库存不是「差异」——两边都是 0")
                    .noneMatch(d -> zeroSku.equals(d.skuNo()));
        } finally {
            skuMapper.delete(Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getSkuNo, zeroSku));
            skuMapper.delete(Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getSkuNo, heldSku));
        }
    }

    @Test
    @DisplayName("★★ 日快照可重跑 —— 派生数据，删光重算结果逐字相同")
    void snapshotIsRepeatable() {
        Fixture f = fixture();
        inbound.postDirectly(purchase(f, 12), "老板");
        reservations.reserve(f.owner, "SO-" + f.seq, List.of(
                new ReservationService.Line(f.item, f.location, 5)), 900);
        reservations.commit(f.owner, "SO-" + f.seq, "SYSTEM");

        LocalDate today = LocalDate.now();
        int first = snapshots.buildFor(today);
        int second = snapshots.buildFor(today);

        assertThat(first).as("当天有变动就该有快照行").isGreaterThan(0);
        assertThat(second).as("重跑行数相同 —— 先删后插，不是往上叠").isEqualTo(first);
    }

    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★★ 进销存的事件有去处 —— SPI 一个实现都没有时，它们会永远堆着")
    void inventoryEventsReachThePlatformOutbox() {
        assertThat(sinks == null ? java.util.List.<InventoryEventSink>of() : sinks)
                .as("InventoryEventSink 一个实现都没有的话，InvOutboxDispatchJob **不标已发**，"
                        + "事件在 inv_outbox 里越堆越多 —— 那是有意的（比静默丢掉好），但不是去处")
                .isNotEmpty();

        Fixture f = fixture();
        inbound.postDirectly(purchase(f, 5), "老板");

        List<InvOutbox> events = invOutboxMapper.selectList(Wrappers.<InvOutbox>lambdaQuery()
                .eq(InvOutbox::getOwnerId, f.owner));
        assertThat(events).as("过账要落一条出站事件").isNotEmpty();

        // 走一遍 sink：事件该进平台的 sys_outbox，与订单/售后走同一条投递链
        InvOutbox e = events.get(0);
        long before = sysOutboxMapper.selectCount(Wrappers.<SysOutbox>lambdaQuery()
                .eq(SysOutbox::getAggregateId, f.owner));
        for (InventoryEventSink sink : sinks) {
            assertThat(sink.deliver(e.getEventNo(), e.getOwnerId(), e.getEventType(), e.getPayload()))
                    .as("投递失败要返回 false 让它重投，不能吞掉当成功").isTrue();
        }
        long after = sysOutboxMapper.selectCount(Wrappers.<SysOutbox>lambdaQuery()
                .eq(SysOutbox::getAggregateId, f.owner));
        assertThat(after).as("事件要落进平台 outbox").isGreaterThan(before);

        SysOutbox row = sysOutboxMapper.selectList(Wrappers.<SysOutbox>lambdaQuery()
                .eq(SysOutbox::getAggregateId, f.owner)).get(0);
        assertThat(row.getEventType())
                .as("**要带域前缀** —— 平台的 eventType 是全局的，"
                        + "进销存的 POSTED 与订单的 POSTED 撞在一起，消费方分不出是谁的")
                .startsWith("INV_");
    }

    private record Fixture(int seq, String owner, String location, String item) {
    }

    /** 每个用例一套独立的业主/库位/物料 —— 用例之间不共享种子，避免「单独跑绿、全量跑红」。 */
    private Fixture fixture() {
        int seq = SEQ.incrementAndGet();
        String entityNo = "E-INV-" + seq;
        String owner = acl.ownerIdOf(entityNo);
        String location = acl.locationIdOf(entityNo, "S-INV-" + seq);
        String item = acl.upsertItem(entityNo, "SKU-INV-" + seq, "测试米", "5斤装",
                null, null, "BAG");
        return new Fixture(seq, owner, location, item);
    }

    private InboundService.Draft purchase(Fixture f, int qty) {
        return new InboundService.Draft(f.owner, f.location, InvEnums.InboundSource.PURCHASE,
                null, "老周粮油", LocalDateTime.now(), null,
                List.of(new InboundService.Line(f.item, qty, "BAG", 4200L)));
    }

    private int onHand(Fixture f) {
        return query.itemDetail(f.owner, f.item).onHand();
    }

    private int available(Fixture f) {
        return query.itemDetail(f.owner, f.item).available();
    }

    private int totalOf(String owner, String item) {
        return query.itemDetail(owner, item).onHand();
    }
}
