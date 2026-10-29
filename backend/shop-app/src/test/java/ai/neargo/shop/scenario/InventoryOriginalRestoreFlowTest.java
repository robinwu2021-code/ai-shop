package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ai.neargo.inventory.domain.entity.InvStockBalance;
import ai.neargo.inventory.domain.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.inventory.domain.service.InboundService;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.ReservationService;
import ai.neargo.inventory.domain.service.OriginalReservationRestoreService;
import ai.neargo.inventory.domain.service.ReturnReceiptService;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.support.DatabaseThreadBarrier;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;

/** The real independent inventory transaction restores original posted quantities, not today's store mapping. */
@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.refund.inv.test.url:jdbc:h2:mem:phase1-refund-inv-primary;"
                + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "shop.inventory.datasource.url=${order.phase1.refund.inv.db.url:jdbc:h2:mem:phase1-refund-inv;"
                + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "shop.inventory.stock-authority=INVENTORY"
})
@ActiveProfiles("test")
class InventoryOriginalRestoreFlowTest {
    @Autowired private InventoryAclService acl;
    @Autowired private LocationService locations;
    @Autowired private InboundService inbound;
    @Autowired private ReservationService reservations;
    @Autowired private BalanceMapper balances;
    @Autowired private StockPort stock;
    @Autowired private OriginalReservationRestoreService restores;
    @Autowired private ReturnReceiptService receipts;
    @Autowired @Qualifier("invDataSource") private DataSource inventory;

    @Test
    void concurrentSamePurposePostsTheOriginalQuantityOnce() throws Exception {
        Fixture f = fixture(3);
        List<Boolean> results = concurrent(f, "A-" + f.ref(), "A-" + f.ref());
        assertThat(results).containsExactly(true, true);
        assertThat(onHand(f, f.location())).isEqualTo(9);
        assertThat(new JdbcTemplate(inventory).queryForObject(
                "select sum(qty) from inv_refund_restore where purpose_no=?", Long.class, "A-" + f.ref()))
                .isEqualTo(2L);
    }

    @Test
    void concurrentDifferentPurposesCannotBothConsumeTheRemainingOriginalQuantity() throws Exception {
        Fixture f = fixture(3);
        List<Boolean> results = concurrent(f, "A-" + f.ref(), "B-" + f.ref());
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(onHand(f, f.location())).isEqualTo(9);
        assertThat(new JdbcTemplate(inventory).queryForObject(
                "select sum(qty) from inv_refund_restore where purpose_no in (?,?)", Long.class,
                "A-" + f.ref(), "B-" + f.ref())).isEqualTo(2L);
    }

    @Test
    void concurrentReceiptAndRefundRestoreCannotBothConsumeOriginalCapacity() throws Exception {
        Fixture f = fixture(3, 2);
        AtomicReference<Thread> receiptThread = new AtomicReference<>();
        AtomicReference<Thread> restoreThread = new AtomicReference<>();
        CompletableFuture<Boolean> receipt;
        CompletableFuture<Boolean> restore;
        try (var connection = inventory.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select id from inv_stock_balance"
                    + " where owner_id=? and item_id=? and location_id=? for update")) {
                statement.setString(1, f.owner());
                statement.setString(2, f.item());
                statement.setString(3, f.location());
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
            }
            receipt = CompletableFuture.supplyAsync(() -> {
                receiptThread.set(Thread.currentThread());
                try {
                    receipts.receive("RR-" + f.ref(), "AS-" + f.ref(), f.ref(), "SUB-" + f.ref(),
                            List.of(new ReturnReceiptService.Line(f.item(), 2,
                                    ReturnReceiptService.Quality.SALEABLE)), "TEST");
                    return true;
                } catch (IllegalStateException conflict) {
                    assertThat(conflict).hasMessage("RETURN_RECEIPT_QUANTITY_OR_IDEMPOTENCY_CONFLICT");
                    return false;
                }
            });
            restore = CompletableFuture.supplyAsync(() -> {
                restoreThread.set(Thread.currentThread());
                try {
                    restores.restore("RF-" + f.ref(), f.ref(),
                            List.of(new OriginalReservationRestoreService.ItemQty(f.item(), 2)));
                    return true;
                } catch (IllegalStateException conflict) {
                    assertThat(conflict).hasMessage("STOCK_RESTORE_PURPOSE_OR_QUANTITY_CONFLICT");
                    return false;
                }
            });
            try {
                await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(10)).until(() ->
                        DatabaseThreadBarrier.originalRestore(receiptThread.get())
                                && DatabaseThreadBarrier.originalRestore(restoreThread.get())
                                && (DatabaseThreadBarrier.posting(receiptThread.get())
                                || DatabaseThreadBarrier.posting(restoreThread.get())));
                connection.commit();
            } finally {
                connection.rollback();
            }
        }
        assertThat(List.of(receipt.get(15, TimeUnit.SECONDS), restore.get(15, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        assertThat(onHand(f, f.location())).isEqualTo(9);
        JdbcTemplate jdbc = new JdbcTemplate(inventory);
        Long restored = jdbc.queryForObject("select coalesce(sum(qty), 0) from inv_refund_restore"
                + " where purpose_no=?", Long.class, "RF-" + f.ref());
        Long received = jdbc.queryForObject("select coalesce(sum(qty), 0) from inv_return_receipt"
                + " where receipt_no=?", Long.class, "RR-" + f.ref());
        assertThat(restored + received).isEqualTo(2L);
    }

    private List<Boolean> concurrent(Fixture f, String first, String second) throws Exception {
        AtomicReference<Thread> a = new AtomicReference<>();
        AtomicReference<Thread> b = new AtomicReference<>();
        CompletableFuture<Boolean> one;
        CompletableFuture<Boolean> two;
        try (var connection = inventory.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select id from inv_stock_balance"
                    + " where owner_id=? and item_id=? and location_id=? for update")) {
                statement.setString(1, f.owner());
                statement.setString(2, f.item());
                statement.setString(3, f.location());
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
            }
            one = restoreAsync(f, first, a);
            two = restoreAsync(f, second, b);
            try {
                await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(10)).until(() ->
                        DatabaseThreadBarrier.originalRestore(a.get()) && DatabaseThreadBarrier.originalRestore(b.get())
                                && (DatabaseThreadBarrier.posting(a.get()) || DatabaseThreadBarrier.posting(b.get())));
                connection.commit();
            } finally {
                connection.rollback();
            }
        }
        return List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
    }

    private CompletableFuture<Boolean> restoreAsync(Fixture f, String purpose, AtomicReference<Thread> thread) {
        return CompletableFuture.supplyAsync(() -> {
            thread.set(Thread.currentThread());
            try {
                return stock.restoreOriginal(purpose, f.ref(), needs(f, 2)) == StockPort.RestoreResult.COMPLETED;
            } catch (IllegalStateException conflict) {
                assertThat(conflict).hasMessage("STOCK_RESTORE_PURPOSE_OR_QUANTITY_CONFLICT");
                return false;
            }
        });
    }

    @Test
    void restoresPostedSourceAfterStoreChangesItsSourceWarehouse() {
        Fixture f = fixture(3);
        String next = locations.createWarehouse(f.owner(), "new warehouse", "TEST");
        locations.setSource(f.owner(), f.location(), next, "TEST");
        assertThatCode(() -> stock.restoreOriginal("RF-" + f.ref(), f.ref(), needs(f, 2)))
                .doesNotThrowAnyException();
        assertThat(onHand(f, f.location())).isEqualTo(9);
        assertThat(onHand(f, next)).isZero();
        assertThat(stock.restoreOriginal("RF-" + f.ref(), f.ref(), needs(f, 2)))
                .isEqualTo(StockPort.RestoreResult.COMPLETED);
        assertThat(onHand(f, f.location())).isEqualTo(9);
    }

    @Test
    void twoChildPurposesCannotRestoreMoreThanOriginalPostedQuantity() {
        Fixture f = fixture(3);
        assertThatCode(() -> stock.restoreOriginal("A-" + f.ref(), f.ref(), needs(f, 2)))
                .doesNotThrowAnyException();
        assertThat(onHand(f, f.location())).isEqualTo(9);
        assertThatThrownBy(() -> stock.restoreOriginal("B-" + f.ref(), f.ref(), needs(f, 2)))
                .isInstanceOf(RuntimeException.class);
        assertThat(onHand(f, f.location())).isEqualTo(9);
        stock.restoreOriginal("B-" + f.ref(), f.ref(), needs(f, 1));
        assertThat(onHand(f, f.location())).isEqualTo(10);
    }

    @Test
    void samePurposeCannotChangeItsQuantity() {
        Fixture f = fixture(3);
        assertThatCode(() -> stock.restoreOriginal("RF-" + f.ref(), f.ref(), needs(f, 1)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> stock.restoreOriginal("RF-" + f.ref(), f.ref(), needs(f, 2)))
                .isInstanceOf(RuntimeException.class);
        assertThat(onHand(f, f.location())).isEqualTo(8);
    }

    private Fixture fixture(int committed) {
        return fixture(committed, 1);
    }

    private Fixture fixture(int committed, int lifecycleVersion) {
        String ref = UUID.randomUUID().toString();
        String entity = "E-" + ref;
        String sku = "SKU-" + ref;
        String owner = acl.ownerIdOf(entity);
        String location = acl.locationIdOf(entity, "STORE-" + ref);
        String item = acl.upsertItem(entity, sku, "original stock", "unit", null, null, "BAG");
        inbound.postDirectly(new InboundService.Draft(owner, location, InvEnums.InboundSource.PURCHASE,
                null, null, "supplier", LocalDateTime.now(), null,
                List.of(new InboundService.Line(item, 10, "BAG", 4200L))), "TEST");
        reservations.reserve(owner, ref,
                List.of(new ReservationService.Line(item, location, committed, "SUB-" + ref)), 900, lifecycleVersion);
        if (lifecycleVersion == 2) {
            reservations.allocatePaidByRef(ref);
            reservations.issueByFulfillmentRef("SUB-" + ref, "TEST");
        } else {
            reservations.commit(owner, ref, "TEST");
        }
        Fixture f = new Fixture(ref, sku, owner, item, location);
        assertThat(onHand(f, location)).isEqualTo(10 - committed);
        return f;
    }

    private List<StockPort.SkuQty> needs(Fixture f, int qty) {
        return List.of(new StockPort.SkuQty(f.sku(), qty, "STORE-" + f.ref()));
    }

    private int onHand(Fixture f, String location) {
        InvStockBalance row = balances.selectOne(Wrappers.<InvStockBalance>lambdaQuery()
                .eq(InvStockBalance::getOwnerId, f.owner()).eq(InvStockBalance::getItemId, f.item())
                .eq(InvStockBalance::getLocationId, location));
        return row == null ? 0 : row.getOnHand();
    }

    private record Fixture(String ref, String sku, String owner, String item, String location) { }
}
