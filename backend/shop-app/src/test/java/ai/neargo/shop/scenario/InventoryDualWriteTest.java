package ai.neargo.shop.scenario;

import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.StockQueryService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.spi.product.StockPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
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
    private InboundService inbound;
    @Autowired
    private StockQueryService query;
    @Autowired
    private SkuMapper skuMapper;

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

        // ② 进销存侧：搬运之后该有的样子
        String owner = acl.ownerIdOf(entityNo);
        String location = acl.locationIdOf(entityNo, null);
        String itemId = acl.upsertItem(entityNo, skuNo, "东北大米", "5斤装", null, null, "袋");
        inbound.postDirectly(new InboundService.Draft(owner, location,
                InvEnums.InboundSource.PURCHASE, null, "老周粮油", LocalDateTime.now(), null,
                List.of(new InboundService.Line(itemId, qty, "袋", 4200L))), "老板");

        return new Fixture(seq, skuNo, owner, itemId);
    }

    private int onHand(Fixture f) {
        return query.itemDetail(f.owner, f.itemId).onHand();
    }

    private int available(Fixture f) {
        return query.itemDetail(f.owner, f.itemId).available();
    }

    private int ledgerSize(Fixture f) {
        return query.ledger(f.owner, f.itemId, null, null, 100).entries().size();
    }
}
