package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.food.dto.DiningOrderBatchVO;
import ai.neargo.shop.food.dto.DiningReturnVO;
import ai.neargo.shop.food.dto.DiningSettlementVO;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbReturn;
import ai.neargo.shop.food.entity.FnbSettlement;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SettlementMapper;
import ai.neargo.shop.food.service.DiningCheckoutService;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.food.service.DiningReturnService;
import ai.neargo.shop.food.service.impl.DiningTransactionRecoveryServiceImpl;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real claim transactions fence external owner queries; owner fixtures never authorize a new payment. */
class DiningTransactionRecoveryServiceTest extends FoodWriteGuardFixture {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private record State(Fixture f, DiningTransactionRecoveryServiceImpl service, DiningOrderService orders,
            DiningCheckoutService checkout, DiningReturnService returns) { }
    private void run(Consumer<State> action) {
        runner().run(context -> {
            Fixture f = prepare(context);
            var sql = context.getBean(SqlSessionTemplate.class);
            for (Class<?> mapper : List.of(OrderBatchMapper.class, SettlementMapper.class, ReturnMapper.class)) {
                if (!sql.getConfiguration().hasMapper(mapper)) sql.getConfiguration().addMapper(mapper);
            }
            for (Class<?> entity : List.of(FnbOrderBatch.class, FnbSettlement.class, FnbReturn.class)) create(f, entity);
            var orders = mock(DiningOrderService.class);
            var checkout = mock(DiningCheckoutService.class);
            var returns = mock(DiningReturnService.class);
            var service = new DiningTransactionRecoveryServiceImpl(sql.getMapper(OrderBatchMapper.class),
                    sql.getMapper(SettlementMapper.class), sql.getMapper(ReturnMapper.class), orders, checkout,
                    returns, CLOCK, context.getBean(PlatformTransactionManager.class));
            action.accept(new State(f, service, orders, checkout, returns));
        });
    }
    private static void create(Fixture f, Class<?> entity) {
        var table = TableInfoHelper.getTableInfo(entity);
        String columns = table.getFieldList().stream().map(field -> {
            Class<?> type = field.getPropertyType(); String name = field.getColumn();
            String sqlType = type == Long.class ? "BIGINT" : type == Integer.class ? "INT"
                    : type == BigDecimal.class ? "DECIMAL(18,6)" : type == LocalDateTime.class ? "DATETIME(3)"
                    : type == LocalDate.class ? "DATE" : "TEXT";
            if (Set.of("version", "deleted").contains(name)) sqlType += " DEFAULT 0";
            if (name.equals("tenant_no")) sqlType = "VARCHAR(32) DEFAULT 'MAIN'";
            return name + " " + sqlType;
        }).collect(Collectors.joining(","));
        f.sql().execute("CREATE TABLE " + table.getTableName() + " (id BIGINT AUTO_INCREMENT PRIMARY KEY," + columns + ")");
    }
    private static void order(State s, String number) {
        s.f.sql().update("INSERT INTO fnb_order_batch(entity_no,store_no,batch_no,status,frozen_request,created_at) "
                + "VALUES('E','A',?,'UNKNOWN','original','2026-10-23 09:00:00')", number);
    }
    private static void outside() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(DataScopeContext.isSkipped()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    private static DiningOrderBatchVO bound(String number) {
        return new DiningOrderBatchVO(number,"A",null,"C",0,"Q","BOUND","PREPAY",null,
                "O","SUB","WAIT_PAY","CNY",100L,2);
    }
    @Test void poisonBackoffAllowsOtherQueuesAndQueriesOwnersAfterClaimCommit() {
        run(s -> {
            order(s,"POISON");
            s.f.sql().update("INSERT INTO fnb_return(entity_no,store_no,return_no,status,request_snapshot,created_at) "
                    + "VALUES('E','A','RETURN','UNKNOWN','original','2026-10-23 10:00:00')");
            when(s.orders.recoverOriginal("E","A","POISON")).thenAnswer(call -> {
                outside();
                assertThat(s.f.sql().queryForObject("SELECT version FROM fnb_order_batch", Long.class)).isEqualTo(1L);
                throw new IllegalStateException("owner unavailable");
            });
            when(s.returns.recoverOriginal("E","A","RETURN")).thenAnswer(call -> {
                outside(); return new DiningReturnVO("RETURN","B","A","REFUNDED","REFUNDED","PENDING",
                        "CASE","reason",List.of(),null);
            });
            assertThat(s.service.recoverDue(1).failed()).isEqualTo(1);
            assertThat(s.service.recoverDue(1).complete()).isEqualTo(1);
            assertThat(s.service.recoverDue(200).claimed()).isZero();
            verify(s.orders).recoverOriginal("E","A","POISON");
            verify(s.returns).recoverOriginal("E","A","RETURN");
            verifyNoMoreInteractions(s.orders,s.returns);
            verifyNoInteractions(s.checkout);
            assertThat(s.f.sql().queryForObject("SELECT status FROM fnb_order_batch",String.class)).isEqualTo("UNKNOWN");
        });
    }
    @Test void settledCashWithoutKitchenAcceptanceRemainsPendingAndCannotRewriteCashHistory() {
        run(s -> {
            s.f.sql().update("INSERT INTO fnb_settlement(entity_no,store_no,settlement_no,status,request_snapshot,created_at) "
                    + "VALUES('E','A','PAY','SETTLED','original','2026-10-23 10:00:00')");
            when(s.checkout.recoverOriginal("E","A","PAY")).thenAnswer(call -> {
                outside(); return new DiningSettlementVO("PAY","A","B",null,"O","SUB","SETTLED","CNY",100,
                        "scope","digest",2,"BOUND","PARTIAL_REFUND",null,false);
            });
            var result = s.service.recoverDue(5);
            assertThat(result.pending()).isEqualTo(1); assertThat(result.complete()).isZero();
            assertThat(s.f.sql().queryForObject("SELECT status FROM fnb_settlement",String.class)).isEqualTo("SETTLED");
            verify(s.checkout).recoverOriginal("E","A","PAY");
            verifyNoMoreInteractions(s.checkout); verifyNoInteractions(s.orders,s.returns);
        });
    }
    @Test void boundsAndCallerTransactionRejectBeforeOwnerAccess() {
        run(s -> {
            assertThatThrownBy(() -> s.service.recoverDue(201)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> s.f.tx().execute(tx -> s.service.recoverDue(1)))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(s.orders,s.checkout,s.returns);
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE",matches="true")
    void concurrentScannerCannotReclaimAnOwnerQueryInFlight() {
        run(s -> {
            order(s,"B"); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            when(s.orders.recoverOriginal(anyString(),anyString(),anyString())).thenAnswer(call -> {
                outside(); entered.countDown();
                assertThat(release.await(15,TimeUnit.SECONDS)).isTrue(); return bound("B");
            });
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> s.service.recoverDue(1));
                assertThat(entered.await(15,TimeUnit.SECONDS)).isTrue();
                try { assertThat(pool.submit(() -> s.service.recoverDue(1)).get(10,TimeUnit.SECONDS).claimed()).isZero(); }
                finally { release.countDown(); }
                assertThat(first.get(15,TimeUnit.SECONDS).complete()).isEqualTo(1);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            verify(s.orders).recoverOriginal("E","A","B");
            verifyNoMoreInteractions(s.orders); verifyNoInteractions(s.checkout,s.returns);
        });
    }
}
