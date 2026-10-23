package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.DiningReturnApplyReq;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningReturnService;
import ai.neargo.shop.food.service.impl.DiningReturnServiceImpl;
import ai.neargo.shop.spi.trade.IndustryAfterSalePort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Real persisted FOOD intent/source allocations survive an ambiguous original core apply and process restart. */
class DiningReturnServiceTest extends FoodWriteGuardFixture {
    private static final CartAccess BUYER = new CartAccess(null, null, "BUYER");
    private record Context(Fixture fixture, DiningReturnService service, java.util.function.Supplier<DiningReturnService> restart,
                           IndustryAfterSalePort authority, AtomicBoolean found, AtomicBoolean timeout,
                           AtomicReference<IndustryAfterSalePort.Result> result,
                           AtomicReference<IndustryAfterSalePort.Apply> applied) { }
    private void run(Consumer<Context> test) {
        runner().run(context -> {
            Fixture f = prepare(context);
            String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),"
                    + "created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),updated_by VARCHAR(32),"
                    + "version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
            f.sql().execute("CREATE TABLE fnb_order_batch (" + common + "batch_no VARCHAR(32),store_no VARCHAR(32),"
                    + "check_no VARCHAR(32),request_key VARCHAR(64),request_hash VARCHAR(64),source VARCHAR(32),buyer_no VARCHAR(32),"
                    + "operator_no VARCHAR(32),status VARCHAR(24),frozen_request TEXT,accepted_at DATETIME(3),cart_no VARCHAR(32),"
                    + "cart_version BIGINT,quote_ref VARCHAR(32),quote_hash VARCHAR(64),business_date DATE,policy_no VARCHAR(32),"
                    + "fulfillment VARCHAR(32),pay_mode VARCHAR(16),scheduled_at DATETIME(3),actor_realm VARCHAR(32),"
                    + "payer_ref VARCHAR(32),quota_claim_refs TEXT,next_recovery_at DATETIME(3))");
            f.sql().execute("CREATE TABLE fnb_order_line (" + common + "food_line_no VARCHAR(32),batch_no VARCHAR(32),"
                    + "goods_no VARCHAR(32),sku_no VARCHAR(32),qty DECIMAL(18,6),seat_no VARCHAR(32),course_no VARCHAR(32),"
                    + "selection_version BIGINT,display_snapshot TEXT,source_line_ref VARCHAR(32),config_revision_nos TEXT,unit VARCHAR(16))");
            f.sql().execute("CREATE TABLE fnb_core_order_binding (" + common + "binding_no VARCHAR(32),batch_no VARCHAR(32),"
                    + "payment_root_no VARCHAR(32),store_order_no VARCHAR(32),status VARCHAR(24))");
            f.sql().execute("CREATE TABLE fnb_core_line_allocation (" + common + "allocation_no VARCHAR(32),"
                    + "food_line_no VARCHAR(32),order_item_id BIGINT,qty DECIMAL(18,6),core_snapshot_hash VARCHAR(64),"
                    + "core_amount_minor BIGINT,currency VARCHAR(8),unit VARCHAR(16),source_component_ref VARCHAR(64))");
            f.sql().execute("CREATE TABLE fnb_return (" + common + "return_no VARCHAR(32) UNIQUE,store_no VARCHAR(32),"
                    + "check_no VARCHAR(32),reason VARCHAR(255),approval_ref VARCHAR(64),status VARCHAR(24),"
                    + "financial_status VARCHAR(24),core_case_ref VARCHAR(64),kitchen_status VARCHAR(24),batch_no VARCHAR(32),"
                    + "buyer_no VARCHAR(32),request_key VARCHAR(64),request_hash VARCHAR(64),request_snapshot TEXT,"
                    + "next_recovery_at DATETIME(3),"
                    + "UNIQUE(entity_no,store_no,request_key))");
            f.sql().execute("CREATE TABLE fnb_return_item (" + common + "return_item_no VARCHAR(32) UNIQUE,"
                    + "return_no VARCHAR(32),food_line_no VARCHAR(32),component_no VARCHAR(32),qty DECIMAL(18,6),"
                    + "kitchen_disposition VARCHAR(16),core_line_allocation_ref VARCHAR(64))");
            f.sql().update("INSERT INTO fnb_order_batch(entity_no,batch_no,store_no,buyer_no,status,cart_no,cart_version,"
                    + "actor_realm,source,fulfillment,pay_mode) VALUES('E','B','A','BUYER','BOUND','C',7,'CONSUMER','H5','PICKUP','PREPAY')");
            f.sql().update("INSERT INTO fnb_order_line(entity_no,food_line_no,batch_no,goods_no,sku_no,qty,source_line_ref,unit) "
                    + "VALUES('E','FL','B','G','SKU',2,'SRC','EA')");
            f.sql().update("INSERT INTO fnb_core_order_binding(entity_no,binding_no,batch_no,payment_root_no,store_order_no,status) "
                    + "VALUES('E','BIND','B','ORDER','SUB','BOUND')");
            f.sql().update("INSERT INTO fnb_core_line_allocation(entity_no,allocation_no,food_line_no,order_item_id,qty,"
                    + "core_snapshot_hash,core_amount_minor,currency,unit,source_component_ref) "
                    + "VALUES('E','ALLOC','FL',42,2,?,1000,'CNY','EA',?)",
                    "a".repeat(64), PricingSnapshotHash.of(List.of("MAIN", "SKU")));
            SqlSessionTemplate sql = context.getBean(SqlSessionTemplate.class);
            for (Class<?> type : List.of(OrderBatchMapper.class, CoreOrderBindingMapper.class, OrderLineMapper.class,
                    CoreLineAllocationMapper.class, ReturnMapper.class, ReturnItemMapper.class)) {
                if (!sql.getConfiguration().hasMapper(type)) sql.getConfiguration().addMapper(type);
            }
            CheckCartService carts = mock(CheckCartService.class);
            when(carts.customerGetCart("C", BUYER)).thenReturn(new CartResultVO(new CartVO("C", "A", null,
                    null, null, "CLOSED", "H5", "PICKUP", null, 8L, List.of())));
            IndustryAfterSalePort authority = mock(IndustryAfterSalePort.class);
            var found = new AtomicBoolean(); var timeout = new AtomicBoolean();
            var result = new AtomicReference<>(coreResult("APPLIED"));
            var applied = new AtomicReference<IndustryAfterSalePort.Apply>();
            when(authority.queryApply(any())).thenAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_return", Integer.class)).isOne();
                IndustryAfterSalePort.QueryApply query = invocation.getArgument(0);
                assertThat(query.requestKey()).isEqualTo(f.sql().queryForObject("SELECT return_no FROM fnb_return", String.class));
                assertThat(query.binding().orderNo()).isEqualTo("ORDER");
                assertThat(query.binding().source().sourceNo()).isEqualTo("C");
                assertThat(query.binding().source().sourceVersion()).isEqualTo(7);
                return new IndustryAfterSalePort.Lookup(found.get(), found.get() ? result.get() : null);
            });
            when(authority.apply(any())).thenAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                IndustryAfterSalePort.Apply command = invocation.getArgument(0); applied.set(command);
                assertThat(command.identity()).isEqualTo(new IndustryAfterSalePort.Identity("MAIN", "E", "A", "BUYER", "BUYER"));
                assertThat(command.binding().orderNo()).isEqualTo("ORDER");
                assertThat(command.binding().source().sourceNo()).isEqualTo("C");
                assertThat(command.binding().source().sourceVersion()).isEqualTo(7);
                assertThat(command.lines()).hasSize(1);
                assertThat(command.lines().getFirst().sourceLineNo()).isEqualTo("SRC");
                assertThat(command.lines().getFirst().componentRef()).isEqualTo(PricingSnapshotHash.of(List.of("MAIN", "SKU")));
                assertThat(command.lines().getFirst().qty()).isBetween(1, 2);
                var receipt = result.get();
                result.set(new IndustryAfterSalePort.Result(receipt.afterSaleNo(), receipt.orderNo(), receipt.subOrderNo(),
                        receipt.status(), command.lines().getFirst().qty() * 500L, receipt.currency(),
                        receipt.progress(), receipt.returnInventoryStatus()));
                assertThat(f.sql().queryForObject("SELECT return_no FROM fnb_return", String.class)).isEqualTo(command.requestKey());
                found.set(true);
                if (timeout.get()) throw new IllegalStateException("original-apply-response-lost");
                return result.get();
            });
            when(authority.get(any())).thenAnswer(invocation -> result.get());
            when(authority.cancel(any())).thenAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                result.set(coreResult("CLOSED")); return result.get();
            });
            java.util.function.Supplier<DiningReturnService> restart = () -> new DiningReturnServiceImpl(carts,
                    sql.getMapper(OrderBatchMapper.class), sql.getMapper(CoreOrderBindingMapper.class),
                    sql.getMapper(OrderLineMapper.class), sql.getMapper(CoreLineAllocationMapper.class),
                    sql.getMapper(ReturnMapper.class), sql.getMapper(ReturnItemMapper.class), () -> authority,
                    context.getBean(ObjectMapper.class), Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC),
                    context.getBean(PlatformTransactionManager.class));
            test.accept(new Context(f, restart.get(), restart, authority, found, timeout, result, applied));
        });
    }
    private static IndustryAfterSalePort.Result coreResult(String status) {
        return new IndustryAfterSalePort.Result("AFTERSALE", "ORDER", "SUB", status, 1000, "CNY", null, "PENDING");
    }
    private DiningReturnApplyReq request(String quantity, String reason) {
        return new DiningReturnApplyReq("B", "REFUND_ONLY", reason, null, List.of(),
                List.of(new DiningReturnApplyReq.Line("SRC", new BigDecimal(quantity))));
    }

    @Test void fullOriginalCaseRecoversAfterRestartWithoutInferringKitchenHandlingFromRefundCompletion() {
        run(c -> {
            c.timeout.set(true); c.result.set(coreResult("REFUNDING"));
            var uncertain = c.service.customerApply("A", BUYER, request("2", "reason"), "KEY");
            assertThat(uncertain.status()).isEqualTo("UNKNOWN");
            assertThat(uncertain.coreCaseRef()).isNull();
            assertThat(uncertain.kitchenStatus()).isEqualTo("PENDING");
            var recovered = c.restart.get().customerGet(BUYER, uncertain.returnNo());
            assertThat(recovered.coreCaseRef()).isEqualTo("AFTERSALE");
            assertThat(recovered.status()).isEqualTo("REFUNDING");
            assertThat(recovered.financialStatus()).isEqualTo("PENDING");
            c.result.set(coreResult("REFUNDED"));
            var completed = c.restart.get().customerGet(BUYER, uncertain.returnNo());
            assertThat(completed.financialStatus()).isEqualTo("COMPLETED");
            assertThat(completed.kitchenStatus()).isEqualTo("PENDING");
            assertThat(completed.lines()).extracting(line -> line.kitchenDisposition()).containsExactly("KEEP");
            var staff = c.restart.get().staffGet("E", "A", "STAFF", uncertain.returnNo());
            assertThat(staff.financialCase()).isNull();
            assertThatThrownBy(() -> c.restart.get().staffGet("E", "OTHER", "STAFF", uncertain.returnNo()))
                    .isInstanceOf(BizException.class);
            c.result.set(coreResult("APPLIED"));
            var delayed = c.restart.get().customerGet(BUYER, uncertain.returnNo());
            assertThat(delayed.status()).isEqualTo("REFUNDED");
            assertThat(delayed.financialStatus()).isEqualTo("COMPLETED");
            c.result.set(coreResult("REFUNDED"));
            assertThat(c.restart.get().customerApply("A", BUYER, request("2.000", "reason"), "KEY").returnNo())
                    .isEqualTo(uncertain.returnNo());
            assertThatThrownBy(() -> c.restart.get().customerApply("A", BUYER, request("2", "changed"), "KEY"))
                    .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM fnb_return", Integer.class)).isOne();
        });
    }
    @Test void fractionalForeignBuyerAndForgedAmountsNeverCreateLocalCaseOrCallCore() {
        run(c -> {
            assertThatThrownBy(() -> c.service.customerApply("A", BUYER, request("1.5", "reason"), "FRACTION"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> c.service.customerApply("A", new CartAccess(null, null, "OTHER"),
                    request("2", "reason"), "FOREIGN")).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> new ObjectMapper().readValue("""
                    {"batchNo":"B","type":"REFUND_ONLY","reason":"reason","refundMinor":1,
                     "lines":[{"sourceLineRef":"SRC","quantity":2}]}
                    """, DiningReturnApplyReq.class)).isInstanceOf(RuntimeException.class);
            assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM fnb_return", Integer.class)).isZero();
            verifyNoInteractions(c.authority);
        });
    }
    @Test void selectedMainQuantityUsesOriginalCoreAllocationAndCoreComputedMoney() {
        run(c -> {
            assertThatThrownBy(() -> c.service.customerApply("A", BUYER, request("3", "too much"), "OVER"))
                    .isInstanceOf(BizException.class);
            var foreign = new DiningReturnApplyReq("B", "REFUND_ONLY", "reason", null, List.of(),
                    List.of(new DiningReturnApplyReq.Line("UNKNOWN", BigDecimal.ONE)));
            assertThatThrownBy(() -> c.service.customerApply("A", BUYER, foreign, "UNKNOWN"))
                    .isInstanceOf(BizException.class);
            assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM fnb_return", Integer.class)).isZero();
            var partial = c.service.customerApply("A", BUYER, request("1", "partial"), "PARTIAL");
            assertThat(c.applied.get().lines().getFirst().qty()).isEqualTo(1);
            assertThat(partial.financialCase().refundMinor()).isEqualTo(500L);
            assertThat(partial.lines().getFirst().quantity()).isEqualByComparingTo("1");
            assertThat(partial.kitchenStatus()).isEqualTo("PENDING");
            assertThat(c.fixture.sql().queryForObject("SELECT qty FROM fnb_core_line_allocation", BigDecimal.class))
                    .isEqualByComparingTo("2");
        });
    }

    @Test void unknownCancellationWaitsForOriginalStableCaseAndNeverTreatsAbsentLookupAsCancellationProof() {
        run(c -> {
            c.timeout.set(true);
            var unknown = c.service.customerApply("A", BUYER, request("2", "reason"), "KEY");
            c.found.set(false);
            var pending = c.restart.get().customerCancel(BUYER, unknown.returnNo());
            assertThat(pending.status()).isEqualTo("CANCEL_PENDING");
            assertThat(pending.coreCaseRef()).isNull();
            assertThat(pending.kitchenStatus()).isEqualTo("PENDING");
            c.found.set(true);
            var cancelled = c.restart.get().customerGet(BUYER, unknown.returnNo());
            assertThat(cancelled.status()).isEqualTo("CLOSED");
            assertThat(cancelled.coreCaseRef()).isEqualTo("AFTERSALE");
            assertThat(cancelled.financialStatus()).isEqualTo("NONE");
            assertThat(cancelled.kitchenStatus()).isEqualTo("PENDING");
            assertThat(c.restart.get().customerCancel(BUYER, unknown.returnNo()).status()).isEqualTo("CLOSED");
        });
    }
}
