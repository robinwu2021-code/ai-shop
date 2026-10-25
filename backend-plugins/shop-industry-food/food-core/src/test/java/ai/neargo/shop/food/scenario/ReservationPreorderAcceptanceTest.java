package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.*;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.food.service.impl.ReservationPreorderServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;

/** Real preorder service + JDBC; strict cart/order boundary fakes. This is not CORE end-to-end acceptance. */
public class ReservationPreorderAcceptanceTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final OrderLineInput LINE = line(BigDecimal.ONE);
    private JdbcTemplate db;
    private Connection schemaConnection;
    private final ObjectMapper json = new ObjectMapper();
    private ReservationPreorderServiceImpl service;
    private CartBoundary carts;
    private OrderBoundary orders;
    private String preorder;

    public static Timestamp utc(Integer precision) { return Timestamp.valueOf("2026-10-24 00:00:00"); }

    @AfterEach void closeSchemaConnection() throws Exception {
        if (schemaConnection != null) schemaConnection.close();
    }

    @BeforeEach void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:preorder_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(source);
        // H2 CHECK expressions retain their DDL session; keep it alive until this test finishes.
        // This matches FoodBusinessOperationsPersistenceTest and preserves every production CHECK.
        schemaConnection = source.getConnection();
        var ddl = new JdbcTemplate(new SingleConnectionDataSource(schemaConnection, true));
        ddl.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'ai.neargo.shop.food.scenario.ReservationPreorderAcceptanceTest.utc'");
        ddl.execute("CREATE TABLE fnb_reservation(entity_no VARCHAR(32),store_no VARCHAR(32),reservation_no VARCHAR(32),"
                + "status VARCHAR(24),version BIGINT,deleted INT DEFAULT 0,start_at TIMESTAMP,check_no VARCHAR(32))");
        db.update("INSERT INTO fnb_reservation(entity_no,store_no,reservation_no,status,version,start_at) VALUES('E','S','RES','CONFIRMED',0,?)",
                LocalDateTime.of(2026, 10, 24, 2, 0));
        String migration = Files.readString(Path.of("src/main/resources/db/industry/food/V17__food_service_collaboration_operations.sql"));
        for (String table : List.of("fnb_reservation_preorder", "fnb_business_command")) {
            int start = migration.indexOf("CREATE TABLE " + table + " (");
            ddl.execute(migration.substring(start, migration.indexOf(';', start))
                    .replace("CHARACTER SET utf8mb4 COLLATE utf8mb4_bin", "")
                    .replace("ENGINE=InnoDB", "").replace(" JSON ", " VARCHAR(100000) "));
        }
        carts = new CartBoundary(); orders = new OrderBoundary();
        service = new ReservationPreorderServiceImpl(new FoodBusinessOperationsMapper(source),
                new DataSourceTransactionManager(source), mock(IdempotencyService.class), carts.port, orders.port,
                json, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static OrderLineInput line(BigDecimal qty) {
        return new OrderLineInput("L1", "MENU", "ITEM", "REV", "GOODS", "SKU", qty, null, null, List.of(), List.of());
    }
    private String status() { return db.queryForObject("SELECT status FROM fnb_reservation_preorder", String.class); }
    private Map<String, Object> row() { return db.queryForMap("SELECT * FROM fnb_reservation_preorder"); }
    private void save() {
        preorder = service.save("E", "S", "actor", "RES", new ReservationPreorderContracts.SaveReq(
                0, NOW.plusSeconds(3600), List.of(LINE)), "save").preorderNo();
    }
    private void arrive() { db.update("UPDATE fnb_reservation SET status='ARRIVED',check_no='CHECK',version=1"); }
    private void ready() { save(); arrive(); service.prepareCart("E", "S", "actor", "RES", preorder, 0, "prepare"); }
    private CreateStaffDiningOrderReq order() {
        return new CreateStaffDiningOrderReq("BUYER", "CART", 1, "QUOTE", "a".repeat(64), "POSTPAY");
    }
    private ReservationPreorderContracts.ConvertReq conversion() {
        return new ReservationPreorderContracts.ConvertReq(service.get("E", "S", "RES", preorder).version(), order());
    }
    private DiningOrderBatchVO convert(ReservationPreorderContracts.ConvertReq request, String key) {
        return service.convert("E", "S", "actor", "RES", preorder, request, key);
    }
    private void conflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test void savedSelectionDoesNotCreateCartOrOrderUntilArrivedAndPreparationReplaysDeterministicKeys() {
        save();
        var saved = service.get("E", "S", "RES", preorder);
        assertThat(saved.status()).isEqualTo("DRAFT"); assertThat(saved.lines()).containsExactly(LINE);
        assertThat(saved.cartNo()).isNull(); assertThat(saved.batchNo()).isNull();
        assertThat(carts.current).isNull(); assertThat(orders.placed).isEmpty();
        conflict(() -> service.prepareCart("E", "S", "actor", "RES", preorder, 0, "before-arrival"));
        assertThat(status()).isEqualTo("DRAFT"); assertThat(carts.createKeys).isEmpty();
        arrive();
        var cart = service.prepareCart("E", "S", "actor", "RES", preorder, 0, "prepare");
        assertThat(cart.cart().checkNo()).isEqualTo("CHECK"); assertThat(cart.cart().lines()).containsExactly(LINE);
        assertThat(cart.cart().status()).isEqualTo("DRAFT");
        assertThat(status()).isEqualTo("READY");
        assertThat(service.get("E", "S", "RES", preorder).cartNo()).isEqualTo("CART");
        assertThat(orders.placed).isEmpty();
        assertThat(service.prepareCart("E", "S", "actor", "RES", preorder, 0, "retry")).isEqualTo(cart);
        assertThat(carts.createKeys).containsExactly("RSP_CART_" + preorder, "RSP_CART_" + preorder);
        assertThat(carts.lineKeys).containsExactly("RSP_LINE_" + preorder + "_L1");
        assertThat(carts.createdCount).isOne();
    }

    @Test void conversionRejectsChangedSelectionCartVersionCheckAndPreorderVersionBeforeOrderOwner() {
        ready(); var original = carts.current; var request = conversion(); var before = row();
        carts.current = carts.cart("CHECK", 1L, List.of(line(new BigDecimal("2"))));
        conflict(() -> convert(request, "selection-changed"));
        carts.current = carts.cart("CHECK", 2L, List.of(LINE));
        conflict(() -> convert(request, "cart-version-changed"));
        carts.current = carts.cart("OTHER-CHECK", 1L, List.of(LINE));
        conflict(() -> convert(request, "wrong-check"));
        carts.current = original;
        conflict(() -> convert(new ReservationPreorderContracts.ConvertReq(request.version() - 1, order()), "stale-preorder"));
        assertThat(row()).isEqualTo(before); assertThat(orders.placeKeys).isEmpty(); assertThat(orders.placed).isEmpty();
    }

    @Test void conversionFreezesOriginalOrderAndAssociatesAcceptedBatchThenReplayRecoversWithoutFreshPlacement() {
        ready(); var request = conversion();
        var batch = convert(request, "convert");
        assertThat(batch.batchNo()).isEqualTo("BATCH"); assertThat(batch.status()).isEqualTo("ACCEPTED");
        var saved = service.get("E", "S", "RES", preorder);
        assertThat(saved.status()).isEqualTo("CONVERTED"); assertThat(saved.batchNo()).isEqualTo("BATCH");
        assertThat(saved.cartNo()).isEqualTo("CART");
        assertThat(db.queryForObject("SELECT active_reservation_no FROM fnb_reservation_preorder", String.class)).isNull();
        assertThat(json.readValue(db.queryForObject("SELECT conversion_request FROM fnb_reservation_preorder", String.class),
                CreateStaffDiningOrderReq.class)).isEqualTo(order());
        assertThat(convert(request, "retry-with-another-http-key")).isEqualTo(batch);
        assertThat(orders.placeKeys).containsExactly("RSVP_" + preorder);
        assertThat(orders.recoveries).containsExactly("BATCH"); assertThat(orders.placed).hasSize(1);
        var altered = new CreateStaffDiningOrderReq("BUYER", "CART", 1, "OTHER-QUOTE", "a".repeat(64), "POSTPAY");
        conflict(() -> convert(new ReservationPreorderContracts.ConvertReq(request.version(), altered), "changed-order"));
        assertThat(orders.placeKeys).hasSize(1); assertThat(orders.recoveries).hasSize(1);
    }

    @Test void unknownOrderReceiptKeepsOriginalBatchUntilOwnerRecoveryActuallyAccepts() {
        ready(); var request = conversion(); orders.outcome = "UNKNOWN";
        assertThat(convert(request, "convert").status()).isEqualTo("UNKNOWN");
        assertThat(status()).isEqualTo("CONVERTING");
        assertThat(service.get("E", "S", "RES", preorder).batchNo()).isEqualTo("BATCH");
        assertThat(db.queryForObject("SELECT active_reservation_no FROM fnb_reservation_preorder", String.class)).isEqualTo("RES");
        orders.outcome = "ACCEPTED";
        assertThat(convert(request, "recover").status()).isEqualTo("ACCEPTED"); assertThat(status()).isEqualTo("CONVERTED");
        assertThat(orders.placeKeys).containsExactly("RSVP_" + preorder);
        assertThat(orders.recoveries).containsExactly("BATCH"); assertThat(orders.placed).hasSize(1);
    }

    @Test void lostPlacementResponseRetriesSameOriginalKeyWithoutCreatingAnotherOrder() {
        ready(); var request = conversion(); orders.loseFirstResponse = true;
        assertThatThrownBy(() -> convert(request, "convert")).isInstanceOf(IllegalStateException.class).hasMessage("lost owner response");
        assertThat(status()).isEqualTo("CONVERTING");
        assertThat(service.get("E", "S", "RES", preorder).batchNo()).isNull();
        assertThat(orders.placed).hasSize(1);
        var recovered = convert(request, "retry");
        assertThat(recovered.batchNo()).isEqualTo("BATCH"); assertThat(status()).isEqualTo("CONVERTED");
        assertThat(orders.placeKeys).containsExactly("RSVP_" + preorder, "RSVP_" + preorder);
        assertThat(orders.placed).hasSize(1);
    }

    @Test void quoteIdentityAndDigestAreFrozenAndDelegatedToOrderOwnerWithoutInventingAcceptance() {
        ready(); var request = conversion();
        var unconfirmed = new CreateStaffDiningOrderReq("BUYER", "CART", 1, "OTHER-QUOTE", "b".repeat(64), "POSTPAY");
        conflict(() -> convert(new ReservationPreorderContracts.ConvertReq(request.version(), unconfirmed), "wrong-quote"));
        assertThat(status()).isEqualTo("CONVERTING"); assertThat(orders.placed).isEmpty();
        assertThat(service.get("E", "S", "RES", preorder).batchNo()).isNull();
        assertThat(json.readValue(db.queryForObject("SELECT conversion_request FROM fnb_reservation_preorder", String.class),
                CreateStaffDiningOrderReq.class)).isEqualTo(unconfirmed);
        // Local preorder cannot silently replace a frozen quote under a retry; actual quote validation belongs to the order owner.
        conflict(() -> convert(request, "replace-quote"));
        assertThat(orders.placeKeys).containsExactly("RSVP_" + preorder);
    }

    private void scope(Object[] args, boolean actor) {
        assertThat(args[0]).isEqualTo("E"); assertThat(args[1]).isEqualTo("S");
        if (actor) assertThat(args[2]).isEqualTo("actor");
    }
    private class CartBoundary {
        CartVO current; int createdCount;
        final List<String> createKeys = new ArrayList<>(), lineKeys = new ArrayList<>();
        CartVO cart(String check, Long version, List<OrderLineInput> lines) {
            return new CartVO("CART", "S", check, null, null, "DRAFT", "WAITER", "DINE_IN", null, version, lines);
        }
        final CheckCartService port = mock(CheckCartService.class, call -> {
            Object[] args = call.getArguments();
            return switch (call.getMethod().getName()) {
                case "createCart" -> {
                    scope(args, true); assertThat(status()).isIn("PREPARING", "READY");
                    assertThat(args[3]).isEqualTo(new CreateCartReq("CHECK", null, null, "WAITER", "DINE_IN", null));
                    assertThat(args[4]).isEqualTo("RSP_CART_" + preorder); createKeys.add((String) args[4]);
                    if (current == null) { current = cart("CHECK", 0L, List.of()); createdCount++; }
                    yield new CartResultVO(current);
                }
                case "getCart" -> {
                    scope(args, false); assertThat(args[2]).isEqualTo("CART"); assertThat(current).isNotNull(); yield new CartResultVO(current);
                }
                case "replaceLine" -> {
                    scope(args, false); assertThat(args[2]).isEqualTo("CART"); assertThat(args[3]).isEqualTo("L1");
                    assertThat(args[4]).isEqualTo("actor"); assertThat(args[6]).isEqualTo("RSP_LINE_" + preorder + "_L1");
                    var request = (ReplaceCartLineReq) args[5]; assertThat(request.line()).isEqualTo(LINE);
                    assertThat(request.version()).isEqualTo(current.version());
                    assertThat(status()).isEqualTo("PREPARING"); lineKeys.add((String) args[6]);
                    current = cart("CHECK", current.version() + 1, List.of(request.line())); yield new CartVersionVO("CART", current.version());
                }
                default -> throw new AssertionError("Unexpected cart owner operation: " + call.getMethod().getName());
            };
        });
    }
    private class OrderBoundary {
        String outcome = "ACCEPTED"; boolean loseFirstResponse;
        final List<String> placeKeys = new ArrayList<>(), recoveries = new ArrayList<>();
        final Map<String, CreateStaffDiningOrderReq> placed = new LinkedHashMap<>();
        DiningOrderBatchVO batch() {
            return new DiningOrderBatchVO("BATCH", "S", "CHECK", "CART", 1, "QUOTE", outcome, "POSTPAY",
                    LocalDate.of(2026, 10, 24), "ORDER", "SUBORDER", "ACCEPTED".equals(outcome) ? "CREATED" : "UNKNOWN", "CNY", 1200L, 0);
        }
        final DiningOrderService port = mock(DiningOrderService.class, call -> {
            Object[] args = call.getArguments();
            return switch (call.getMethod().getName()) {
                case "staffPlace" -> {
                    scope(args, true); assertThat(status()).isEqualTo("CONVERTING");
                    assertThat(args[4]).isEqualTo("RSVP_" + preorder); placeKeys.add((String) args[4]);
                    var request = (CreateStaffDiningOrderReq) args[3];
                    assertThat(json.readValue(db.queryForObject("SELECT conversion_request FROM fnb_reservation_preorder", String.class),
                            CreateStaffDiningOrderReq.class)).isEqualTo(request);
                    // Deliberately strict external contract, not a claim that this fake is the real quote/CORE owner.
                    if (!request.equals(order())) throw BizException.of(ErrorCode.CONFLICT);
                    var previous = placed.putIfAbsent((String) args[4], request);
                    if (previous != null) assertThat(previous).isEqualTo(request);
                    if (loseFirstResponse) { loseFirstResponse = false; throw new IllegalStateException("lost owner response"); }
                    yield batch();
                }
                case "recoverOriginal" -> {
                    scope(args, false); assertThat(args[2]).isEqualTo("BATCH"); assertThat(placed).hasSize(1);
                    recoveries.add((String) args[2]); yield batch();
                }
                default -> throw new AssertionError("Unexpected order owner operation: " + call.getMethod().getName());
            };
        });
    }
}
