package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.CatalogItemVO;
import ai.neargo.shop.food.dto.CatalogMenuVO;
import ai.neargo.shop.food.dto.CatalogSkuVO;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.dto.CreateDiningOrderReq;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.entity.FnbCheckCart;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbSalesQuotaClaim;
import ai.neargo.shop.food.entity.FnbSalesRestriction;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SalesQuotaClaimMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SalesRestrictionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.policy.DiningPriceSourcePolicy;
import ai.neargo.shop.food.policy.DiningPricingSourcePolicy;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.food.service.DiningPricingFactsService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.impl.DiningOrderServiceImpl;
import ai.neargo.shop.food.service.impl.SalesQuotaClaimServiceImpl;
import ai.neargo.shop.spi.product.DishOfferQueryPort.Offer;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Real FOOD transactions/quota/source allocations surround a durable external owner-document fixture.
 * The fixture models the original core RPC boundary; core order/tax implementation is tested by its own module.
 */
class DiningOrderServiceTest extends FoodWriteGuardFixture {
    private static final CartAccess BUYER = new CartAccess(null, null, "BUYER");
    private static final String COMPONENT = PricingSnapshotHash.of(List.of("MAIN", "SKU"));
    private static final String DIGEST = "d".repeat(64);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private record Context(Fixture fixture, DiningOrderService service, Supplier<DiningOrderService> restart,
            DiningPricingFactsService facts, PricingCheckoutPort pricing, AtomicBoolean failBind,
            AtomicBoolean timeout, AtomicInteger coreCalls, AtomicReference<IndustryOrderPort.Result> receipt) { }

    private void run(Consumer<Context> test) {
        runner().run(context -> {
            Fixture f = prepare(context);
            SqlSessionTemplate sql = context.getBean(SqlSessionTemplate.class);
            List<Class<?>> mapperTypes = List.of(CheckCartMapper.class, StorePolicyMapper.class,
                    OrderBatchMapper.class, CoreOrderBindingMapper.class, OrderLineMapper.class,
                    CoreLineAllocationMapper.class, SalesQuotaClaimMapper.class, SalesRestrictionMapper.class);
            for (Class<?> type : mapperTypes) {
                if (!sql.getConfiguration().hasMapper(type)) sql.getConfiguration().addMapper(type);
            }
            for (Class<?> type : List.of(FnbCheckCart.class,
                    FnbStorePolicy.class, FnbOrderBatch.class,
                    FnbCoreOrderBinding.class, FnbOrderLine.class,
                    FnbCoreLineAllocation.class,
                    FnbSalesQuotaClaim.class,
                    FnbSalesRestriction.class)) create(f, type);
            f.sql().execute("CREATE UNIQUE INDEX ux_cart ON fnb_check_cart(cart_no)");
            f.sql().execute("CREATE UNIQUE INDEX ux_batch_key ON fnb_order_batch(entity_no,store_no,request_key)");
            f.sql().execute("CREATE UNIQUE INDEX ux_claim ON fnb_sales_quota_claim(restriction_no,batch_no)");
            f.sql().execute("CREATE UNIQUE INDEX ux_binding ON fnb_core_order_binding(store_order_no)");
            f.sql().execute("CREATE UNIQUE INDEX ux_source_line ON fnb_order_line(batch_no,source_line_ref)");
            f.sql().execute("CREATE TABLE core_owner_document_fixture (source_batch VARCHAR(32) PRIMARY KEY,"
                    + "order_no VARCHAR(32) UNIQUE,receipt TEXT)");
            f.sql().update("INSERT INTO fnb_check_cart(entity_no,cart_no,store_no,buyer_no,status,order_source,"
                    + "fulfillment,session_no) VALUES('E','C','A','BUYER','OPEN','H5','PICKUP','SESSION')");
            f.sql().update("INSERT INTO fnb_store_policy(entity_no,policy_no,store_no,timezone,cutover_minute,"
                    + "effective_from,pay_policy) VALUES('E','P','A','UTC',240,'2026-01-01 00:00:00','PREPAY')");
            f.sql().update("INSERT INTO fnb_sales_restriction(entity_no,restriction_no,store_no,sku_no,mode,quota_qty,"
                    + "reserved_qty,consumed_qty,starts_at,status,reason) "
                    + "VALUES('E','R','A','SKU','QUOTA',10,0,0,'2026-01-01 00:00:00','ACTIVE','test')");
            ObjectMapper json = context.getBean(ObjectMapper.class);
            DiningPriceFacts evidence = facts(json);
            var source = new PricingSourceRef("FOOD", "CART", "C", 0, null, null);
            var projection = DiningPricingSourcePolicy.project(evidence, null, source, "SESSION");
            var quote = new PricingCheckoutPort.QuoteVO("Q", "CONFIRMED", "2026-10-23T13:00:00Z", 0,
                    projection.prices().sourceSnapshotHash(), "CNY", List.of(new PricingCheckoutPort.LineVO(
                            "SRC", COMPONENT, "SRC", "MAIN", "G", "SKU", BigDecimal.ZERO,
                            new BigDecimal("2"), 1010, 918, 92, 1010)),
                    new PricingCheckoutPort.TotalsVO(1010, 0, 918, 92, 1010, 1010), DIGEST);
            CheckCartService carts = mock(CheckCartService.class);
            CartVO publicCart = new CartVO("C", "A", null, "SESSION", null, "OPEN", "H5", "PICKUP", null, 0L,
                    evidence.payload().cart().lines());
            when(carts.customerGetCart("C", BUYER)).thenReturn(new CartResultVO(publicCart));
            DiningPricingFactsService facts = mock(DiningPricingFactsService.class);
            when(facts.customer("C", BUYER, 0)).thenAnswer(call -> {
                outside(); return evidence;
            });
            PricingCheckoutPort pricing = mock(PricingCheckoutPort.class);
            when(pricing.revalidateCustomer(any(), any(), anyString(), anyString())).thenAnswer(call -> {
                outside(); return quote;
            });
            StoreOperationService policies = mock(StoreOperationService.class);
            when(policies.getPolicy(anyString(), anyString(), any())).thenAnswer(call -> {
                outside(); return new StoreOperationPolicyResultVO("P", "UTC", 240, "PREPAY", List.of(), 0);
            });
            var quotas = new SalesQuotaClaimServiceImpl(sql.getMapper(SalesRestrictionMapper.class),
                    sql.getMapper(StorePolicyMapper.class), sql.getMapper(SalesQuotaClaimMapper.class), CLOCK);
            AtomicBoolean failBind = new AtomicBoolean(); AtomicBoolean timeout = new AtomicBoolean();
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<IndustryOrderPort.Result> receipt = new AtomicReference<>(receipt(2, 1010));
            IndustryOrderPort core = mock(IndustryOrderPort.class);
            when(core.place(any(), anyString())).thenAnswer(call -> {
                outside(); calls.incrementAndGet();
                IndustryOrderPort.Command command = call.getArgument(0); String batch = call.getArgument(1);
                assertThat(command.identity()).isEqualTo(new IndustryOrderPort.Identity("MAIN", "E", "A", "BUYER", "BUYER"));
                assertThat(command.source()).isEqualTo(source);
                assertThat(f.sql().queryForObject("SELECT batch_no FROM fnb_order_batch", String.class)).isEqualTo(batch);
                assertThat(f.sql().queryForObject("SELECT frozen_request FROM fnb_order_batch", String.class)).isNotBlank();
                assertThat(f.sql().queryForObject("SELECT reserved_qty FROM fnb_sales_restriction", BigDecimal.class))
                        .isEqualByComparingTo("2");
                assertThat(f.sql().queryForObject("SELECT consumed_qty FROM fnb_sales_restriction", BigDecimal.class))
                        .isEqualByComparingTo("0");
                if (f.sql().queryForObject("SELECT COUNT(*) FROM core_owner_document_fixture WHERE source_batch=?",
                        Integer.class, batch) == 0) {
                    f.sql().update("INSERT INTO core_owner_document_fixture VALUES(?,?,?)", batch,
                            receipt.get().orderNo(), json.writeValueAsString(receipt.get()));
                }
                if (timeout.get()) throw new IllegalStateException("original-core-response-lost");
                return json.readValue(f.sql().queryForObject("SELECT receipt FROM core_owner_document_fixture "
                        + "WHERE source_batch=?", String.class, batch), IndustryOrderPort.Result.class);
            });
            when(core.query(any(), anyString())).thenAnswer(call -> {
                outside(); String batch = call.getArgument(1);
                return json.readValue(f.sql().queryForObject("SELECT receipt FROM core_owner_document_fixture "
                        + "WHERE source_batch=?", String.class, batch), IndustryOrderPort.Result.class);
            });
            when(core.get(any(), anyString())).thenAnswer(call -> {
                outside(); String order = call.getArgument(1);
                return json.readValue(f.sql().queryForObject("SELECT receipt FROM core_owner_document_fixture "
                        + "WHERE order_no=?", String.class, order), IndustryOrderPort.Result.class);
            });
            CoreLineAllocationMapper original = sql.getMapper(CoreLineAllocationMapper.class);
            CoreLineAllocationMapper allocationRows = (CoreLineAllocationMapper) Proxy.newProxyInstance(
                    CoreLineAllocationMapper.class.getClassLoader(), new Class<?>[]{CoreLineAllocationMapper.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("insert") && failBind.getAndSet(false)) {
                            throw new IllegalStateException("food-bind-failed-after-core-created");
                        }
                        try { return method.invoke(original, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
            Supplier<DiningOrderService> restart = () -> new DiningOrderServiceImpl(carts, facts, policies, quotas,
                    sql.getMapper(CheckCartMapper.class), sql.getMapper(StorePolicyMapper.class),
                    sql.getMapper(OrderBatchMapper.class), sql.getMapper(CoreOrderBindingMapper.class),
                    sql.getMapper(OrderLineMapper.class), allocationRows, () -> core, () -> pricing, json, CLOCK,
                    context.getBean(PlatformTransactionManager.class));
            test.accept(new Context(f, restart.get(), restart, facts, pricing, failBind, timeout, calls, receipt));
        });
    }

    private static DiningPriceFacts facts(ObjectMapper json) {
        OrderLineInput line = new OrderLineInput("SRC", "MENU", "ITEM", "REV", "G", "SKU",
                new BigDecimal("2"), null, null, List.of(), List.of());
        CartVO cart = new CartVO("C", "A", null, "SESSION", null, "OPEN", "H5", "PICKUP", null, 0L, List.of(line));
        CatalogMenuVO menu = new CatalogMenuVO("MENU", "REV", 1L, "Menu", 0, null, List.of());
        CatalogItemVO item = new CatalogItemVO("MENU", "ITEM", "CAT", "Category", "G", "Dish", null,
                new DishVO("G", "SINGLE", 0, null, new MeasurementPolicy("EA", "COUNT", 0, null), 0, 0),
                List.of(), List.of(new CatalogSkuVO("SKU", null, null, 505L, "CNY", "KNOWN", 10,
                        "KNOWN", true, false, null, true, false, null)));
        CatalogVO catalog = new CatalogVO("A", "H5", "PICKUP", null, List.of(menu), List.of(item));
        Offer offer = new Offer("SKU", "G", "E", "A", 505L, "CNY", "KNOWN", 10, "KNOWN", true);
        var payload = new DiningPriceFacts.Payload("E", "SESSION", cart, List.of(new DiningPriceFacts.MenuRef(
                "MENU", "REV", 1, "a".repeat(64), List.of())), List.of(new SkuIdentityFact("SKU", "G", "E", "A", 0)),
                List.of(offer), List.of(new DiningPriceFacts.Restriction("SKU", false, new BigDecimal("10"), true, List.of())),
                DiningPriceSourcePolicy.compose(cart, catalog, Map.of("SKU", offer), json),
                new DiningPriceFacts.Composition(List.of(menu), List.of(item)), "CNY", 1010L);
        return new DiningPriceFacts(1, payload, PricingSnapshotHash.of(payload));
    }
    private static IndustryOrderPort.Result receipt(int qty, long amount) {
        return new IndustryOrderPort.Result("ORDER", "SUB", "Q", "WAIT_PAY", "CNY", amount, 0, 2,
                List.of(new IndustryOrderPort.Allocation("SRC", COMPONENT, 42, qty, amount, "CNY", "b".repeat(64))));
    }
    private static CreateDiningOrderReq request() { return new CreateDiningOrderReq("C", 0, "Q", DIGEST, "PREPAY"); }
    private static void outside() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(DataScopeContext.isSkipped()).isFalse();
    }
    private static void create(Fixture f, Class<?> type) {
        var info = TableInfoHelper.getTableInfo(type);
        String columns = info.getFieldList().stream().map(field -> {
            Class<?> java = field.getPropertyType(); String column = field.getColumn();
            String sqlType = java == Long.class ? "BIGINT" : java == Integer.class ? "INT"
                    : java == BigDecimal.class ? "DECIMAL(18,6)" : java == LocalDateTime.class ? "DATETIME(3)"
                    : java == LocalDate.class ? "DATE" : "VARCHAR(255)";
            if (Set.of("frozen_request", "quota_claim_refs", "display_snapshot", "config_revision_nos").contains(column)) {
                sqlType = "TEXT";
            }
            if (Set.of("version", "deleted").contains(column)) sqlType += " DEFAULT 0";
            if (column.equals("tenant_no")) sqlType += " DEFAULT 'MAIN'";
            return column + " " + sqlType;
        }).collect(Collectors.joining(","));
        f.sql().execute("CREATE TABLE " + info.getTableName() + " (id BIGINT AUTO_INCREMENT PRIMARY KEY," + columns + ")");
    }

    @Test void stagesDurablyBeforeRpcAndBindsOriginalWholeQuantityTaxInclusiveAmountAndComponent() {
        run(c -> {
            var scope = DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("E")); DataScopeContext.set(scope);
            try {
                var result = c.service.customerPlace("A", BUYER, request(), "KEY");
                assertThat(result.orderNo()).isEqualTo("ORDER");
                assertThat(result.amountMinor()).isEqualTo(1010L);
                assertThat(DataScopeContext.current()).isSameAs(scope); assertThat(DataScopeContext.isSkipped()).isFalse();
            } finally { DataScopeContext.clear(); }
            assertThat(c.fixture.sql().queryForObject("SELECT status FROM fnb_check_cart", String.class)).isEqualTo("CLOSED");
            assertThat(c.fixture.sql().queryForObject("SELECT version FROM fnb_check_cart", Long.class)).isEqualTo(1L);
            assertThat(c.fixture.sql().queryForObject("SELECT qty FROM fnb_core_line_allocation", BigDecimal.class))
                    .isEqualByComparingTo("2");
            assertThat(c.fixture.sql().queryForObject("SELECT core_amount_minor FROM fnb_core_line_allocation", Long.class))
                    .isEqualTo(1010L);
            assertThat(c.fixture.sql().queryForObject("SELECT source_component_ref FROM fnb_core_line_allocation", String.class))
                    .isEqualTo(COMPONENT);
            var frozen = new ObjectMapper().readTree(c.fixture.sql().queryForObject("SELECT frozen_request FROM fnb_order_batch", String.class));
            assertThat(frozen.path("quote").path("totals").path("taxMinor").asLong()).isEqualTo(92L);
            assertThat(c.coreCalls).hasValue(1);
        });
    }
    @Test void coreDocumentSurvivesFoodBindRollbackAndRestartReusesSameBatchAndOriginalOrder() {
        run(c -> {
            c.failBind.set(true);
            assertThatThrownBy(() -> c.service.customerPlace("A", BUYER, request(), "KEY"))
                    .hasMessage("food-bind-failed-after-core-created");
            String batch = c.fixture.sql().queryForObject("SELECT batch_no FROM fnb_order_batch", String.class);
            assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM core_owner_document_fixture", Integer.class)).isOne();
            for (String table : List.of("fnb_order_line", "fnb_core_line_allocation", "fnb_core_order_binding")) {
                assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
            }
            assertThat(c.fixture.sql().queryForObject("SELECT status FROM fnb_check_cart", String.class)).isEqualTo("OPEN");
            var replay = c.restart.get().customerPlace("A", BUYER, request(), "KEY");
            assertThat(replay.batchNo()).isEqualTo(batch); assertThat(replay.orderNo()).isEqualTo("ORDER");
            assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM core_owner_document_fixture", Integer.class)).isOne();
            assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM fnb_order_batch", Integer.class)).isOne();
            assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM fnb_core_order_binding", Integer.class)).isOne();
            verify(c.facts, times(1)).customer("C", BUYER, 0);
        });
    }
    @Test void changedExternalKeyRequestAndIncorrectQuantityOrMoneyCannotBindAsSuccessfulFoodOrder() {
        run(c -> {
            c.service.customerPlace("A", BUYER, request(), "KEY");
            var changed = new CreateDiningOrderReq("C", 0, "OTHER_QUOTE", DIGEST, "PREPAY");
            assertThatThrownBy(() -> c.restart.get().customerPlace("A", BUYER, changed, "KEY"))
                    .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(c.coreCalls).hasValue(1);
        });
        for (IndustryOrderPort.Result corrupt : List.of(receipt(1, 1010), receipt(2, 1011))) {
            run(c -> {
                c.receipt.set(corrupt);
                assertThatThrownBy(() -> c.service.customerPlace("A", BUYER, request(), "KEY"))
                        .isInstanceOf(BizException.class);
                assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM fnb_core_order_binding", Integer.class)).isZero();
                assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM fnb_core_line_allocation", Integer.class)).isZero();
                assertThat(c.fixture.sql().queryForObject("SELECT status FROM fnb_check_cart", String.class)).isEqualTo("OPEN");
            });
        }
    }
    @Test void timeoutKeepsUnknownOriginalIntentAndQuotaUntilOriginalDocumentRecovery() {
        run(c -> {
            c.timeout.set(true);
            assertThatThrownBy(() -> c.service.customerPlace("A", BUYER, request(), "KEY"))
                    .hasMessage("original-core-response-lost");
            String batch = c.fixture.sql().queryForObject("SELECT batch_no FROM fnb_order_batch", String.class);
            assertThat(c.fixture.sql().queryForObject("SELECT status FROM fnb_order_batch", String.class)).isEqualTo("UNKNOWN");
            assertThat(c.fixture.sql().queryForObject("SELECT reserved_qty FROM fnb_sales_restriction", BigDecimal.class))
                    .isEqualByComparingTo("2");
            assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM fnb_sales_quota_claim WHERE status='RESERVED'", Integer.class)).isOne();
            c.timeout.set(false);
            var recovered = c.restart.get().customerGet(BUYER, batch);
            assertThat(recovered.status()).isEqualTo("BOUND"); assertThat(recovered.orderNo()).isEqualTo("ORDER");
            assertThat(c.coreCalls).hasValue(1);
        });
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "FOOD_TEST_DISPOSABLE", matches = "true")
    void nativeCartEditWinsRowLockAndStalePlacementCannotStageQuotaOrCallCore() {
        run(c -> {
            AtomicReference<Thread> thread = new AtomicReference<>();
            try (var workers = Executors.newSingleThreadExecutor(); var connection = c.fixture.source().getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("SELECT id FROM fnb_check_cart WHERE cart_no='C' FOR UPDATE");
                        var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
                var pending = workers.submit(() -> {
                    thread.set(Thread.currentThread());
                    try { c.service.customerPlace("A", BUYER, request(), "KEY"); return true; }
                    catch (BizException conflict) {
                        assertThat(conflict.errorCode()).isEqualTo(ErrorCode.CONFLICT); return false;
                    }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); long observed = 0; boolean blocked = false;
                while (System.nanoTime() < deadline) {
                    Thread current = thread.get();
                    StackTraceElement[] stack = current == null ? new StackTraceElement[0] : current.getStackTrace();
                    boolean waiting = Arrays.stream(stack).anyMatch(frame -> frame.getClassName()
                            .endsWith(".DiningOrderServiceImpl") && frame.getMethodName().equals("cart"))
                            && Arrays.stream(stack).anyMatch(frame -> frame.getClassName().startsWith("com.mysql.")
                            || frame.getClassName().startsWith("org.mariadb."));
                    if (waiting) {
                        if (observed == 0) observed = System.nanoTime();
                        if (System.nanoTime() - observed >= TimeUnit.MILLISECONDS.toNanos(100)) { blocked = true; break; }
                    } else observed = 0;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                assertThat(blocked).isTrue();
                try (var statement = connection.prepareStatement("UPDATE fnb_check_cart SET version=version+1 WHERE cart_no='C'")) {
                    statement.executeUpdate();
                }
                connection.commit();
                assertThat(pending.get(20, TimeUnit.SECONDS)).isFalse();
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM fnb_order_batch", Integer.class)).isZero();
            assertThat(c.fixture.sql().queryForObject("SELECT reserved_qty FROM fnb_sales_restriction", BigDecimal.class))
                    .isEqualByComparingTo("0");
            assertThat(c.coreCalls).hasValue(0);
        });
    }
}
