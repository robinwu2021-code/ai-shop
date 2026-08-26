package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.InventoryAclService;
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
        int before = query.ledger(f.owner, f.item, f.location, null, 50).entries().size();

        reservations.reserve(f.owner, "SO-" + f.seq, List.of(
                new ReservationService.Line(f.item, f.location, 4)), 900);
        reservations.release(f.owner, "SO-" + f.seq);

        assertThat(available(f)).isEqualTo(10);
        assertThat(query.ledger(f.owner, f.item, f.location, null, 50).entries()).hasSize(before);
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
        int rowsBefore = query.ledger(f.owner, f.item, f.location, null, 50).entries().size();

        inbound.voidOrder(f.owner, no, "老板");

        assertThat(onHand(f)).isZero();
        // 原行还在，多出一行反向 —— 不是把那一行改掉或删掉
        assertThat(query.ledger(f.owner, f.item, f.location, null, 50).entries())
                .hasSize(rowsBefore + 1);
    }

    // ────────────────────────────────────────────────────────────────────

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
