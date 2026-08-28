package ai.neargo.shop.scenario;

import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.invbridge.InventoryBackfillService;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.StockQueryService;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.spi.product.StockPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双写（{@code stock-authority=DUAL}）：<b>平台仍是真相源，进销存跟着记一笔</b>。
 *
 * <h2>这一档补的是切换计划里缺的那一段</h2>
 * 原本只有两档：{@code PLATFORM}（只写平台）→ {@code INVENTORY}（只写进销存）。
 * 中间没有任何东西让两本账保持一致，于是搬运之后进销存那本账<b>冻结在搬运那一刻</b>，
 * 而对差比的是「一直在动的账」和「停着的账」—— 差异随每一笔订单增长，
 * G3 那道「连续 N 天 clean」的闸门<b>在有交易的平台上永远绿不了</b>。
 *
 * <p>所以这里断言的不是「代码跑得通」，是<b>两本账在交易之后还对得上</b>。
 *
 * <p>带自己的内存库：这是第四个上下文，共享 H2 上每多一个就多跑一遍
 * {@code schema-test.sql}，撞主键并拖垮之后所有上下文。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "shop.inventory.stock-authority=DUAL",
        "spring.datasource.url=jdbc:h2:mem:shop_dual;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        /*
         * **进销存那个库也要自己一份。** 只换平台库是半个隔离：
         * 平台库每开一个上下文就重建一次，而进销存库 DB_CLOSE_DELAY=-1 会一直留着，
         * 于是上一个测试类的存货漏进来，本类的 fixture 搬运被当成「已经搬过」，
         * 症状是「付款之后实存真扣」拿到 10 而不是 8 —— 和支付、和双写都没关系。
         *
         * 原来漏掉它不是疏忽：那时三个 inv bean 被 @Primary 的平台数据源接走了，
         * 第二个库压根没被用过，配不配都一样。
         */
        "shop.inventory.datasource.url=jdbc:h2:mem:inv_dual;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
class InventoryDualWriteTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private StockPort stockPort;
    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private InventoryAclService acl;
    @Autowired
    private StockQueryService query;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private InventoryBackfillService backfill;

    @Test
    @DisplayName("★★★ 装配的是双写口 —— 拿到 StockPortImpl 就说明这一档整个没生效")
    void dualWritePortIsWired() {
        assertThat(stockPort.getClass().getSimpleName())
                .as("stock-authority=DUAL 时交易域该拿到 DualWriteStockPort")
                .isEqualTo("DualWriteStockPort");
    }

    @Test
    @DisplayName("★★★ 下单预留：平台扣了，进销存也跟着记上 —— 这正是原来断掉的那一段")
    void reserveReachesBothBooks() {
        Fixture f = fixture(10);

        stockPort.lock("SO-" + f.seq, List.of(new StockPort.SkuQty(f.skuNo, 3)));

        assertThat(available(f)).as("投递之前进销存还没动 —— 事件在队列里").isEqualTo(10);
        dispatcher.dispatchPending();

        assertThat(onHand(f)).as("预留不动实存 —— 与进销存自己的不变式同一条").isEqualTo(10);
        assertThat(available(f))
                .as("**可用要少 3**。少了这一步，进销存那本账就停在搬运那一刻，"
                        + "而对差会把它与一直在动的平台账相比 —— 那道闸门永远绿不了")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("★★★ 支付：两本账都真扣，且**重投不会扣两遍**")
    void commitIsMirroredAndIdempotent() {
        Fixture f = fixture(10);
        String no = "SO-" + f.seq;

        stockPort.lock(no, List.of(new StockPort.SkuQty(f.skuNo, 2)));
        stockPort.confirm(no);
        dispatcher.dispatchPending();

        assertThat(onHand(f)).as("付款之后实存真扣").isEqualTo(8);

        /*
         * outbox 是 at-least-once：投递器在「消费成功」与「标记已发」之间崩掉，
         * 同一笔就会再来一遍。**再来一遍不该再扣一次。**
         */
        dispatcher.redeliverAllForTest();
        dispatcher.dispatchPending();

        assertThat(onHand(f))
                .as("重投扣了两遍。幂等要靠自然键，不能靠「投递器保证只投一次」")
                .isEqualTo(8);
    }

    @Test
    @DisplayName("★★★ 取消：可用回来，且没有产生流水 —— 没成交的单不进销量")
    void releaseIsMirrored() {
        Fixture f = fixture(10);
        String no = "SO-" + f.seq;
        int ledgerBefore = ledgerSize(f);

        stockPort.lock(no, List.of(new StockPort.SkuQty(f.skuNo, 4)));
        stockPort.release(no);
        dispatcher.dispatchPending();

        assertThat(available(f)).as("释放之后可用回来").isEqualTo(10);
        assertThat(ledgerSize(f))
                .as("释放**不该产生流水** —— 产生了就等于没成交的单进了销量")
                .isEqualTo(ledgerBefore);
    }

    @Test
    @DisplayName("★★ 退货：两本账都加回来")
    void restoreIsMirrored() {
        Fixture f = fixture(10);
        String no = "SO-" + f.seq;

        stockPort.lock(no, List.of(new StockPort.SkuQty(f.skuNo, 5)));
        stockPort.confirm(no);
        dispatcher.dispatchPending();
        assertThat(onHand(f)).isEqualTo(5);

        stockPort.restore("AS-" + f.seq, List.of(new StockPort.SkuQty(f.skuNo, 2)));
        dispatcher.dispatchPending();

        assertThat(onHand(f)).as("退货加回来").isEqualTo(7);
    }

    @Test
    @DisplayName("★★★ 平台侧失败时，进销存**一笔都不该记** —— 事件与业务同生共死")
    void platformFailureLeavesNoMirror() {
        Fixture f = fixture(3);
        int before = available(f);

        // 要 5 件而只有 3 件：平台拒绝，整个事务回滚，事件跟着回滚
        try {
            stockPort.lock("SO-FAIL-" + f.seq, List.of(new StockPort.SkuQty(f.skuNo, 5)));
        } catch (RuntimeException expected) {
            // 库存不足，正是要的
        }
        dispatcher.dispatchPending();

        assertThat(available(f))
                .as("单没成而账记了 —— 那正是双写要防的事")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("★★★ 在途预留没搬时，对差必须拦住 —— 实存一样但切过去会超卖")
    void heldLocksMustBeMigratedBeforeSwitch() {
        Fixture f = fixture(10);

        // 一笔在途：平台占了 3 件（locked_stock=3），进销存那边还没有
        stockPort.lock("SO-HELD-" + f.seq, List.of(new StockPort.SkuQty(f.skuNo, 3)));
        // **不投递** —— 模拟「搬运搬了余额，但在途的锁没搬」那一刻
        assertThat(reserved(f)).as("此刻进销存那边一笔预留都没有").isZero();

        InventoryBackfillService.Report before = backfill.diffOnly(5000);
        boolean caught = before.diffs().stream()
                .anyMatch(d -> f.skuNo.equals(d.skuNo()) && d.platformHeld() != d.inventoryHeld());
        assertThat(caught)
                .as("实存两边都是 10，只有预留差 3 —— **只比实存的话这里会报干净**，"
                        + "而切过去那 3 件就重新可售了")
                .isTrue();

        // 搬在途预留，差异应当消失
        backfill.migrateHeldLocks(100);
        assertThat(reserved(f)).as("搬过来之后进销存也占住这 3 件").isEqualTo(3);

        InventoryBackfillService.Report after = backfill.diffOnly(5000);
        assertThat(after.diffs().stream().anyMatch(d -> f.skuNo.equals(d.skuNo())))
                .as("搬完之后这一条不该再有差异").isFalse();
    }

    @Test
    @DisplayName("★★ 搬在途预留是幂等的 —— 重跑不会把同一笔占两遍")
    void migratingHeldLocksIsIdempotent() {
        Fixture f = fixture(10);
        stockPort.lock("SO-IDEM-" + f.seq, List.of(new StockPort.SkuQty(f.skuNo, 4)));

        backfill.migrateHeldLocks(100);
        int once = reserved(f);
        backfill.migrateHeldLocks(100);

        assertThat(reserved(f))
                .as("幂等靠 external_ref（= lockNo），不靠「只跑一次」")
                .isEqualTo(once);
    }

    @Test
    @DisplayName("★★★ 商品页改库存也落进销存 —— 两个入口，一本账")
    void goodsPageEditReachesBothBooks() {
        Fixture f = fixture(10);

        // 商家在商品页把库存改成 4（原本这条路直接 update prd_sku，进销存毫不知情）
        stockPort.setOnHand(f.skuNo, null, 4, "OTHER");
        dispatcher.dispatchPending();

        assertThat(onHand(f))
                .as("**商品页与库存页是两个入口、一本账**。这里对不上的话，"
                        + "搬运之后同一件货就有两个数、两个改法，而改任一个另一个都不知道")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("★★ 手改是「设成这个数」，重投不会越改越少")
    void goodsPageEditIsIdempotent() {
        Fixture f = fixture(10);

        stockPort.setOnHand(f.skuNo, null, 6, "OTHER");
        dispatcher.dispatchPending();
        assertThat(onHand(f)).isEqualTo(6);

        // 带的是目标值不是差额 —— 同一笔来两遍，第二遍算出来的差异是 0
        dispatcher.redeliverAllForTest();
        dispatcher.dispatchPending();

        assertThat(onHand(f))
                .as("带差额的话重投会再减一次；带目标值天然幂等")
                .isEqualTo(6);
    }

    // ------------------------------------------------------------------ 种子

    private record Fixture(int seq, String skuNo, String owner, String itemId) {
    }

    /**
     * 每个用例一套独立的业主与货。
     *
     * <p><b>两本账都要建</b>：平台仍是真相源，只在进销存这边入货的话，
     * {@code lock} 会被平台以「库存不足」拒掉 —— 而那恰恰说明真相源没搞错。
     */
    private Fixture fixture(int qty) {
        int seq = SEQ.incrementAndGet();
        String entityNo = "E-DUAL-" + seq;
        String skuNo = "SKU-DUAL-" + seq;

        // ① 平台侧：真相源
        PrdSku sku = new PrdSku();
        sku.setSkuNo(skuNo);
        sku.setGoodsNo("G-DUAL-" + seq);
        sku.setEntityNo(entityNo);
        sku.setMarket("CN");
        sku.setStock(qty);
        sku.setLockedStock(0);
        sku.setPrice(4200L);
        skuMapper.insert(sku);

        /*
         * ② 进销存侧：**走真实的搬运**，不自己入一笔货。
         *
         * 自己入货的话这个 SKU 没有 INIT 单，`alreadyMoved` 为假 —— 而 `moveOne`
         * 只在「已搬过」时才记差异（没搬过的当然对不上）。于是对差看不见它，
         * 那条断言就永远绿着，而它本来是要拦「预留没搬」的。
         */
        backfill.run(false, 500, null);

        String owner = acl.ownerOfSku(skuNo);
        String itemId = acl.itemIdOfSku(skuNo);

        return new Fixture(seq, skuNo, owner, itemId);
    }

    private int onHand(Fixture f) {
        return query.itemDetail(f.owner, f.itemId).onHand();
    }

    private int available(Fixture f) {
        return query.itemDetail(f.owner, f.itemId).available();
    }

    private int reserved(Fixture f) {
        return query.itemDetail(f.owner, f.itemId).reserved();
    }

    private int ledgerSize(Fixture f) {
        return query.ledger(f.owner, f.itemId, null, null, null, 100).entries().size();
    }
}
