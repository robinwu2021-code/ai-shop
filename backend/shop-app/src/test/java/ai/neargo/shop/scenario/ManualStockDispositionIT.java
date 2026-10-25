package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.shop.ShopApplication;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.invbridge.InvMirrorEvent;
import ai.neargo.shop.invbridge.InventoryMirrorConsumer;
import ai.neargo.shop.invbridge.port.InventoryStockPort;
import ai.neargo.shop.invbridge.port.OriginalStockDispositionPort;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.service.ReservationDispositionService;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.inventory.service.StockCountService;
import ai.neargo.shop.inventory.service.impl.ReservationDispositionServiceImpl;
import ai.neargo.shop.product.port.StockPortImpl;
import ai.neargo.shop.product.service.StockDispositionService;
import ai.neargo.shop.product.service.impl.StockDispositionServiceImpl;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.product.StockDispositionPort.Confirmation;
import ai.neargo.shop.spi.product.StockDispositionPort.Lookup;
import ai.neargo.shop.spi.product.StockDispositionPort.Original;
import ai.neargo.shop.spi.product.StockDispositionPort.Outcome;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ConfirmManualReceipt;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualMethod;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

/**
 * Actual platform/inventory owners and delivery consumer, over distinct schemas on one owned
 * server.
 */
@SpringBootTest(
        classes = {ShopApplication.class, CheckoutFlowFixture.ClockConfig.class},
        properties = {
            "spring.datasource.url=${CHECKOUT_NATIVE_MYSQL_URL}",
            "shop.member-code.enabled=true",
            "shop.catalog.publication.archive-only=true",
            "shop.member.guest.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "shop.inventory.enabled=true",
            "shop.inventory.flyway-enabled=false",
            "shop.inventory.datasource.url=${MANUAL_INVENTORY_MYSQL_URL}",
            "shop.inventory.datasource.username=root",
            "shop.inventory.datasource.password=${MANUAL_SERVICE_TEST_PASSWORD}",
            "shop.inventory.stock-authority=INVENTORY",
            "shop.inventory.lifecycle.version=2"
        })
class ManualStockDispositionIT extends CheckoutFlowFixture {
    @Autowired StockDispositionPort dispositions;
    @Autowired StockPort selectedStock;
    @Autowired StockDispositionService platformDisposition;
    @Autowired ReservationDispositionService inventoryDisposition;
    @Autowired ConfigurableEnvironment environment;
    @Autowired StockPortImpl platform;
    @Autowired InventoryStockPort inventory;
    @Autowired ReservationService reservations;
    @Autowired InventoryMirrorConsumer consumer;
    @Autowired InventoryAclService acl;
    @Autowired LocationService locations;
    @Autowired StockCountService counts;
    @Autowired ManualServiceAdmissionPort manual;

    @Autowired
    @Qualifier("invDataSource")
    DataSource invSource;

    JdbcTemplate inv;
    String owner;
    String item;
    String location;

    @Autowired
    @Qualifier("invSqlSessionFactory")
    SqlSessionFactory invFactory;

    @Autowired
    @Qualifier("sqlSessionFactory")
    SqlSessionFactory platformFactory;

    @BeforeEach
    void inventoryFixture() throws Exception {
        inv = new JdbcTemplate(invSource);
        jdbc.update(
                "INSERT INTO"
                        + " mch_qualification(qual_no,entity_no,qual_type,qual_name,qual_number,status,created_at,"
                        + "updated_at)"
                        + " VALUES (?,?,'BUSINESS_LICENSE','Current test qualification',"
                        + "'TEST-FIXTURE','VALID',NOW(),NOW())",
                "license-" + sku,
                entity);
        assertThat(AopUtils.getTargetClass(selectedStock)).isEqualTo(InventoryStockPort.class);
        assertThat(AopUtils.getTargetClass(dispositions))
                .isEqualTo(OriginalStockDispositionPort.class);
        assertThat(AopUtils.getTargetClass(platformDisposition))
                .isEqualTo(StockDispositionServiceImpl.class);
        assertThat(AopUtils.getTargetClass(inventoryDisposition))
                .isEqualTo(ReservationDispositionServiceImpl.class);
        for (var provider :
                List.of(
                        platform,
                        inventory,
                        consumer,
                        reservations,
                        platformDisposition,
                        inventoryDisposition,
                        dispositions)) {
            var type = AopUtils.getTargetClass(provider);
            String source = type.getProtectionDomain().getCodeSource().getLocation().toString();
            assertThat(source).contains("/beauty-data/ai-shop-codex/").contains("/target/classes/");
            System.out.println("TASK64_CODE_SOURCE " + type.getName() + " " + source);
        }
        try (var connection = invSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        assertThat(invFactory.getConfiguration().getEnvironment().getDataSource())
                .isSameAs(invSource);
        assertThat(platformFactory.getConfiguration().getEnvironment().getDataSource())
                .isSameAs(dataSource);
        for (var statement : invFactory.getConfiguration().getMappedStatementNames()) {
            if (statement.contains("InventoryMappers$Reservation")) {
                System.out.println(
                        "TASK64_MAPPER "
                                + statement
                                + " "
                                + invFactory
                                        .getConfiguration()
                                        .getMappedStatement(statement)
                                        .getResource());
            }
        }
        owner = acl.ownerIdOf(entity);
        item = acl.itemIdOf(entity, sku);
        location = locations.defaultLocation(owner);
        counts.adjustOne(owner, location, item, 100, "OTHER", "SYSTEM");
        assertThat(inv.queryForObject("SELECT DATABASE()", String.class))
                .isNotEqualTo(jdbc.queryForObject("SELECT DATABASE()", String.class));
    }

    @Override
    protected <T> T asMerchant(Supplier<T> work) {
        return ManualServiceAdmissionIT.currentCashier(tokens, actor, work);
    }

    @Override
    protected CheckoutPort.PrepareCheckoutReq request(String coupon) {
        return new CheckoutPort.PrepareCheckoutReq(
                new CheckoutPort.OperationRef("BEAUTY", "manual-" + sku),
                "prepare-" + sku,
                reference,
                1,
                "a".repeat(64),
                List.of(new CheckoutPort.CheckoutLineReq("service-line", sku, 1)),
                "OFFLINE",
                null,
                Map.of(),
                null);
    }

    @Test
    void inventoryV2EnqueueStaysPendingUntilActualAllocation() {
        var q = assertDoesNotThrow(() -> asMerchant(() -> manual.prepare(scope, request(null))));
        var c = asBuyer(() -> confirm(q));
        var o = asBuyer(() -> checkout.place(place(q, c)));
        long revision =
                jdbc.queryForObject(
                        "SELECT version FROM ord_order WHERE order_no=?", Long.class, o.orderNo());
        var received =
                asMerchant(
                        () ->
                                manual.confirm(
                                        scope,
                                        new ConfirmManualReceipt(
                                                q.operation(),
                                                "cash-" + sku,
                                                revision,
                                                ManualMethod.CASH,
                                                101,
                                                "CNY",
                                                testClock
                                                        .at
                                                        .minusMillis(1)
                                                        .truncatedTo(ChronoUnit.MILLIS),
                                                null)));
        assertThat(received.operationState()).isEqualTo("STOCK_PENDING");
        assertThat(received.admissionState())
                .as("queued ALLOCATE with actual HELD rows cannot admit")
                .isNotEqualTo("ADMITTED");
        assertThat(
                        inv.queryForObject(
                                "SELECT status FROM inv_reservation WHERE external_ref=?",
                                String.class,
                                o.orderNo()))
                .isEqualTo("HELD");
        var message =
                jdbc.queryForMap(
                        "SELECT event_no,event_type,payload FROM sys_outbox WHERE event_type=? AND"
                                + " JSON_UNQUOTE(JSON_EXTRACT(payload,'$.ref'))=? ORDER BY id DESC"
                                + " LIMIT 1",
                        InvMirrorEvent.ALLOCATE,
                        o.orderNo());
        var event = new SysOutbox();
        event.setEventNo((String) message.get("event_no"));
        event.setEventType((String) message.get("event_type"));
        event.setPayload((String) message.get("payload"));
        consumer.consume(event);
        assertThat(
                        inv.queryForObject(
                                "SELECT status FROM inv_reservation WHERE external_ref=?",
                                String.class,
                                o.orderNo()))
                .isEqualTo("ALLOCATED");
        var observed =
                asMerchant(
                        () ->
                                manual.query(
                                        scope,
                                        new ManualLookup(
                                                q.operation(),
                                                received.manualDeclarationNo(),
                                                null)));
        assertThat(observed.admissionState()).isEqualTo("ADMITTED");
    }

    @Test
    void inventoryV2FailedOrExpiredAllocationNeverAdmits() {
        // A real paid-manual original with a corrupted protected balance cannot be allocated.
        var q = asMerchant(() -> manual.prepare(scope, request(null)));
        var consent = asBuyer(() -> confirm(q));
        var order = asBuyer(() -> checkout.place(place(q, consent)));
        long revision =
                jdbc.queryForObject(
                        "SELECT version FROM ord_order WHERE order_no=?",
                        Long.class,
                        order.orderNo());
        var received =
                asMerchant(
                        () ->
                                manual.confirm(
                                        scope,
                                        new ConfirmManualReceipt(
                                                q.operation(),
                                                "failed-" + sku,
                                                revision,
                                                ManualMethod.CASH,
                                                101,
                                                "CNY",
                                                testClock
                                                        .at
                                                        .minusMillis(1)
                                                        .truncatedTo(ChronoUnit.MILLIS),
                                                null)));
        inv.update(
                "UPDATE inv_stock_balance SET reserved=0 WHERE owner_id=? AND item_id=? AND"
                        + " location_id=?",
                owner,
                item,
                location);
        var failed = new SysOutbox();
        failed.setEventType(InvMirrorEvent.ALLOCATE);
        failed.setPayload("{\"ref\":\"" + order.orderNo() + "\",\"items\":[]}");
        assertThatThrownBy(() -> consumer.consume(failed)).isInstanceOf(BizException.class);
        assertThat(
                        inv.queryForObject(
                                "SELECT status FROM inv_reservation WHERE external_ref=?",
                                String.class,
                                order.orderNo()))
                .isEqualTo("HELD");
        assertThat(
                        asMerchant(
                                        () ->
                                                manual.query(
                                                        scope,
                                                        new ManualLookup(
                                                                q.operation(),
                                                                received.manualDeclarationNo(),
                                                                null)))
                                .admissionState())
                .isNotEqualTo("ADMITTED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_manual_service_admission WHERE"
                                        + " declaration_no=?",
                                Long.class,
                                received.manualDeclarationNo()))
                .isEqualTo(1);
        inv.update(
                "UPDATE inv_stock_balance SET reserved=1 WHERE owner_id=? AND item_id=? AND"
                        + " location_id=?",
                owner,
                item,
                location);
        String no = "EX-" + UUID.randomUUID();
        var original = reserve(inventory, no, "INVENTORY", 2);
        inv.update(
                "UPDATE inv_reservation SET expires_at=DATE_SUB(NOW(),INTERVAL 1 HOUR) WHERE"
                        + " external_ref=?",
                no);
        reservations.expireOverdue(100);
        inventory.confirm(no, 2);
        var event = new SysOutbox();
        event.setEventType(InvMirrorEvent.ALLOCATE);
        event.setPayload("{\"ref\":\"" + no + "\",\"items\":[]}");
        consumer.consume(event);
        assertThat(observe(original).outcome()).isNotEqualTo(Outcome.APPLIED);
        assertThat(
                        inv.queryForObject(
                                "SELECT status FROM inv_reservation WHERE external_ref=?",
                                String.class,
                                no))
                .isEqualTo("EXPIRED");
    }

    @Test
    void platformNormalReturnWithMissingOrReleasedLocksNeverAdmits() {
        String no = "PL-" + UUID.randomUUID();
        var original = original(no, "PLATFORM", 1);
        platform.confirm(no);
        assertThat(observe(original).outcome()).isNotEqualTo(Outcome.APPLIED);
        platform.lock(no, needs(no, 1));
        platform.release(no);
        platform.confirm(no);
        assertThat(observe(original).outcome()).isNotEqualTo(Outcome.APPLIED);
    }

    @Test
    void alreadyConfirmedOriginalReplaysWithoutSecondDeduction() {
        for (int lifecycle : List.of(1, 2)) {
            for (String authority : List.of("PLATFORM", "INVENTORY")) {
                String no = "RP-" + UUID.randomUUID();
                var original =
                        reserve(
                                "INVENTORY".equals(authority) ? inventory : platform,
                                no,
                                authority,
                                lifecycle);
                var command =
                        new Confirmation(original, StockDispositionContract.operationNo(original));
                var first = dispositions.confirmOriginal(command);
                if ("INVENTORY".equals(authority) && lifecycle == 2) {
                    var event = new SysOutbox();
                    event.setEventType(InvMirrorEvent.ALLOCATE);
                    event.setPayload("{\"ref\":\"" + no + "\",\"items\":[]}");
                    consumer.consume(event);
                    first = observe(original);
                }
                assertThat(first.outcome())
                        .as("the actual original owner must prove its completed transition")
                        .isEqualTo(Outcome.APPLIED);
                var before = balances(no);
                var replay = dispositions.confirmOriginal(command);
                assertThat(replay.outcome()).isEqualTo(Outcome.APPLIED);
                assertThat(replay.lines()).isEqualTo(first.lines());
                assertThat(balances(no))
                        .as("same original replay must not move stock or create another outbound")
                        .isEqualTo(before);
            }
        }
    }

    @Test
    void manifestMismatchOrPartialCoverageFailsClosed() {
        String no = "MM-" + UUID.randomUUID();
        reserve(platform, no, "PLATFORM", 1);
        var changed =
                StockDispositionContract.needs(
                        List.of(new StockPort.SkuQty(sku, 2, store, no + "-child", 1)));
        var original =
                new Original(
                        "MAIN",
                        entity,
                        no,
                        "PLATFORM",
                        1,
                        StockDispositionContract.manifestHash(changed),
                        changed);
        assertThat(
                        dispositions
                                .confirmOriginal(
                                        new Confirmation(
                                                original,
                                                StockDispositionContract.operationNo(original)))
                                .outcome())
                .isNotEqualTo(Outcome.APPLIED);
        String second = sku + "-2";
        jdbc.update(
                "INSERT INTO"
                        + " prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,created_at,updated_at)"
                        + " VALUES (?,?,?,'CN',101,100,0,NOW(),NOW())",
                second,
                goods,
                entity);
        String partial = "PART-" + UUID.randomUUID();
        var complete =
                List.of(
                        new StockPort.SkuQty(sku, 1, store, partial + "-child", 1),
                        new StockPort.SkuQty(second, 1, store, partial + "-child", 1));
        assertThat(platform.lock(partial, complete)).isEmpty();
        var manifest = StockDispositionContract.needs(complete);
        var full =
                new Original(
                        "MAIN",
                        entity,
                        partial,
                        "PLATFORM",
                        1,
                        StockDispositionContract.manifestHash(manifest),
                        manifest);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM prd_stock_lock WHERE lock_no=?",
                                Long.class,
                                partial))
                .isEqualTo(2);
        jdbc.update("DELETE FROM prd_stock_lock WHERE lock_no=? AND sku_no=?", partial, second);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM prd_stock_lock WHERE lock_no=?",
                                Long.class,
                                partial))
                .isEqualTo(1);
        assertThat(
                        dispositions
                                .confirmOriginal(
                                        new Confirmation(
                                                full, StockDispositionContract.operationNo(full)))
                                .outcome())
                .isNotEqualTo(Outcome.APPLIED);
    }

    @Test
    void frozenAuthoritySurvivesConfigurationChange() {
        var properties = environment.getPropertySources();
        properties.addFirst(
                new MapPropertySource(
                        "task64-changed-authority",
                        Map.of("shop.inventory.stock-authority", "PLATFORM")));
        try {
            assertThat(environment.getProperty("shop.inventory.stock-authority"))
                    .isEqualTo("PLATFORM");
            assertThat(AopUtils.getTargetClass(selectedStock)).isEqualTo(InventoryStockPort.class);
            for (String authority : List.of("PLATFORM", "DUAL", "INVENTORY")) {
                String no = "FA-" + UUID.randomUUID();
                var original =
                        reserve(
                                "INVENTORY".equals(authority) ? inventory : platform,
                                no,
                                authority,
                                1);
                assertThat(
                                dispositions
                                        .confirmOriginal(
                                                new Confirmation(
                                                        original,
                                                        StockDispositionContract.operationNo(
                                                                original)))
                                        .outcome())
                        .isEqualTo(Outcome.APPLIED);
                assertThat(observe(original).original().authority()).isEqualTo(authority);
            }
        } finally {
            properties.remove("task64-changed-authority");
        }
    }

    Map<String, Object> balances(String no) {
        return Map.of(
                "platform",
                jdbc.queryForMap(
                        "SELECT stock,locked_stock,sold_count FROM prd_sku WHERE sku_no=?", sku),
                "inventory",
                inv.queryForMap(
                        "SELECT on_hand,reserved FROM inv_stock_balance WHERE owner_id=? AND"
                                + " item_id=? AND location_id=?",
                        owner,
                        item,
                        location),
                "outbounds",
                inv.queryForObject(
                        "SELECT COUNT(*) FROM inv_outbound_order WHERE source_ref=?",
                        Long.class,
                        no));
    }

    Original reserve(StockPort port, String no, String authority, int lifecycle) {
        assertThat(port.lock(no, needs(no, lifecycle))).isEmpty();
        return original(no, authority, lifecycle);
    }

    List<StockPort.SkuQty> needs(String no, int lifecycle) {
        return List.of(new StockPort.SkuQty(sku, 1, store, no + "-child", lifecycle));
    }

    Original original(String no, String authority, int lifecycle) {
        var manifest = StockDispositionContract.needs(needs(no, lifecycle));
        return new Original(
                "MAIN",
                entity,
                no,
                authority,
                lifecycle,
                StockDispositionContract.manifestHash(manifest),
                manifest);
    }

    StockDispositionPort.Disposition observe(Original original) {
        return dispositions.observeOriginal(
                new Lookup(original, StockDispositionContract.operationNo(original)));
    }
}
