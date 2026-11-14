package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.trade.entity.OrdCheckoutOperation;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;
import ai.neargo.shop.trade.service.impl.CheckoutServiceImpl;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real authority loss must precede permanent placement claims and the final order writer. */
@EnabledIfSystemProperty(named = "checkout.test.native", matches = "true")
class CheckoutPlaceAuthorityFlowTest extends CheckoutFlowFixture {
    @Autowired private SqlSessionFactory sessions;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private StoreSalesCatalogPort catalog;

    @AfterEach
    void clearAuthority() {
        reset(catalog);
        jdbc.update("UPDATE usr_account SET status='NORMAL' WHERE user_no=?", buyer);
        SecurityContextHolder.clearContext();
        DataScopeContext.clear();
    }

    @Test
    void revokedDuringPlacePreflightCannotClaimOrCreateOrder() {
        assertPreflightLoss(false);
    }

    @Test
    void disabledDuringPlacePreflightCannotClaimOrCreateOrder() {
        assertPreflightLoss(true);
    }

    private void assertPreflightLoss(boolean disable) {
        String coupon = promoCoupon("ALL", 0, 1);
        var quote = asMerchant(() -> checkout.prepare(scope, request(coupon)));
        var confirmation = asBuyer(() -> confirm(quote));
        var request = place(quote, confirmation);
        var beforeOperation = operation();
        var beforeQuote = quote(quote.quoteNo());
        var beforeConfirmation = confirmation(confirmation.confirmationNo());
        var beforeEconomics = economics(coupon);
        var reached = new AtomicBoolean();
        doAnswer(
                        call -> {
                            Object actual = call.callRealMethod();
                            if (entity.equals(call.getArgument(0))
                                    && store.equals(call.getArgument(1))
                                    && reached.compareAndSet(false, true)) {
                                assertThat(
                                                TransactionSynchronizationManager
                                                        .isActualTransactionActive())
                                        .as(
                                                "real catalog preflight must finish outside"
                                                        + " checkout locks")
                                        .isFalse();
                                String token = SecurityUtils.currentToken().orElseThrow();
                                assertThat(
                                                tokens.of(Realm.CONSUMER)
                                                        .get(token)
                                                        .orElseThrow()
                                                        .user()
                                                        .userNo())
                                        .isEqualTo(buyer);
                                if (disable) {
                                    assertThat(
                                                    jdbc.update(
                                                            "UPDATE usr_account SET"
                                                                    + " status='DISABLED' WHERE"
                                                                    + " user_no=?",
                                                            buyer))
                                            .isEqualTo(1);
                                } else {
                                    tokens.of(Realm.CONSUMER).revoke(token);
                                    assertThat(tokens.of(Realm.CONSUMER).get(token)).isEmpty();
                                }
                            }
                            return actual;
                        })
                .when(catalog)
                .read(anyString(), anyString(), any());

        Throwable failure = catchThrowable(() -> asBuyer(() -> checkout.place(request)));
        assertThat(reached).as("genuine entry authorization reached actual preflight").isTrue();
        unauthorized(failure);
        assertThat(operation())
                .as("preflight loss must not claim a permanent place key")
                .isEqualTo(beforeOperation);
        assertThat(quote(quote.quoteNo())).isEqualTo(beforeQuote);
        assertThat(confirmation(confirmation.confirmationNo())).isEqualTo(beforeConfirmation);
        assertThat(economics(coupon))
                .as("no writer, stock or coupon effect")
                .isEqualTo(beforeEconomics);
        assertThat(operation())
                .containsEntry("phase", "CONFIRMED")
                .containsEntry("place_key_hash", null)
                .containsEntry("place_payload_hash", null);
        assertThat(beforeQuote).containsEntry("status", "CONFIRMED");
        assertThat(beforeConfirmation).containsEntry("status", "AUTHORIZED");
    }

    @Test
    void revokedAfterPlaceClaimCannotReachWriterAndOriginalKeyRecovers() {
        String coupon = promoCoupon("ALL", 0, 1);
        var quote = asMerchant(() -> checkout.prepare(scope, request(coupon)));
        var confirmation = asBuyer(() -> confirm(quote));
        var request = place(quote, confirmation);
        var beforeOperation = operation();
        var beforeQuote = quote(quote.quoteNo());
        var beforeConfirmation = confirmation(confirmation.confirmationNo());
        var beforeEconomics = economics(coupon);
        var gate = new ClaimCommitRevocation();
        sessions.getConfiguration().addInterceptor(gate);
        try {
            Throwable failure = catchThrowable(() -> asBuyer(() -> checkout.place(request)));
            assertThat(gate.updated).as("actual owned metadata update completed").isTrue();
            assertThat(gate.committed)
                    .as("real metadata transaction committed before revocation")
                    .isTrue();
            unauthorized(failure);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var claimed = operation();
            assertThat(claimed.get("place_key_hash"))
                    .isEqualTo(
                            CheckoutDigests.hash(
                                    json, List.of("MAIN", buyer, "PLACE", request.requestKey())));
            assertThat(claimed.get("place_payload_hash"))
                    .isEqualTo(
                            CheckoutDigests.hash(
                                    json, List.of(request.operation(), request.confirmationNo())));
            assertThat(((Number) claimed.get("version")).longValue())
                    .isEqualTo(((Number) beforeOperation.get("version")).longValue() + 1);
            var originalFacts = new LinkedHashMap<>(claimed);
            for (String column :
                    List.of(
                            "place_key_hash",
                            "place_payload_hash",
                            "version",
                            "updated_at",
                            "updated_by")) {
                originalFacts.put(column, beforeOperation.get(column));
            }
            assertThat(originalFacts)
                    .as("only original placement metadata may survive")
                    .isEqualTo(beforeOperation);
            assertThat(quote(quote.quoteNo())).isEqualTo(beforeQuote);
            assertThat(confirmation(confirmation.confirmationNo())).isEqualTo(beforeConfirmation);
            assertThat(economics(coupon)).isEqualTo(beforeEconomics);

            // A new genuine session recovers the original confirmation and permanent request key.
            var created = asBuyer(() -> checkout.place(request));
            assertThat(created.phase()).isEqualTo("CREATED");
            var replay = asBuyer(() -> checkout.place(request));
            assertThat(replay.orderNo()).isEqualTo(created.orderNo());
            assertThat(replay.subOrderNo()).isEqualTo(created.subOrderNo());
            assertThatThrownBy(
                            () ->
                                    asBuyer(
                                            () ->
                                                    checkout.place(
                                                            new CheckoutPort.PlaceCheckoutReq(
                                                                    request.operation(),
                                                                    request.confirmationNo(),
                                                                    "changed-"
                                                                            + request
                                                                                    .requestKey()))))
                    .isInstanceOfSatisfying(
                            BizException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(operation())
                    .containsEntry("place_key_hash", claimed.get("place_key_hash"))
                    .containsEntry("place_payload_hash", claimed.get("place_payload_hash"))
                    .containsEntry("phase", "CREATED")
                    .containsEntry("order_no", created.orderNo())
                    .containsEntry("sub_order_no", created.subOrderNo());
            assertThat(economicCounts())
                    .containsOnlyKeys("intents", "orders", "suborders", "items", "sourceLines")
                    .allSatisfy((name, count) -> assertThat(count).as(name).isEqualTo(1L));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT locked_stock FROM prd_sku WHERE sku_no=?",
                                    Integer.class,
                                    sku))
                    .isEqualTo(3);
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT status,times_used FROM pmt_user_coupon WHERE"
                                            + " user_coupon_no=?",
                                    coupon))
                    .containsEntry("status", "USED")
                    .containsEntry("times_used", 1);
            assertThat(quote(quote.quoteNo())).containsEntry("status", "CONSUMED");
            assertThat(confirmation(confirmation.confirmationNo()))
                    .containsEntry("status", "CONSUMED")
                    .containsEntry("order_no", created.orderNo());
        } finally {
            gate.armed.set(false);
            Object chain =
                    ReflectionTestUtils.getField(sessions.getConfiguration(), "interceptorChain");
            var interceptors = (List<?>) ReflectionTestUtils.getField(chain, "interceptors");
            assertThat(interceptors.remove(gate))
                    .as("remove only this test's interceptor")
                    .isTrue();
        }
    }

    private static void unauthorized(Throwable failure) {
        assertThat(failure)
                .as("current authority loss must deny actual place")
                .isInstanceOfSatisfying(
                        BizException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    private Map<String, Object> operation() {
        return jdbc.queryForMap(
                "SELECT * FROM ord_checkout_operation WHERE operation_no=?", "op-" + sku);
    }

    private Map<String, Object> quote(String quoteNo) {
        return jdbc.queryForMap("SELECT * FROM ord_checkout_quote WHERE quote_no=?", quoteNo);
    }

    private Map<String, Object> confirmation(String confirmationNo) {
        return jdbc.queryForMap(
                "SELECT * FROM ord_checkout_confirmation WHERE confirmation_no=?", confirmationNo);
    }

    private Map<String, Long> economicCounts() {
        return Map.of(
                "intents", ownCount("ord_create_intent", "user_no", buyer),
                "orders", ownCount("ord_order", "user_no", buyer),
                "suborders", ownCount("ord_sub_order", "user_no", buyer),
                "items", ownCount("ord_item", "sku_no", sku),
                "sourceLines", ownCount("ord_checkout_line", "operation_no", "op-" + sku));
    }

    private long ownCount(String table, String column, String value) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Long.class, value);
    }

    private EconomicSnapshot economics(String coupon) {
        var counts = economicCounts();
        assertThat(counts).allSatisfy((name, count) -> assertThat(count).as(name).isZero());
        return new EconomicSnapshot(
                counts,
                jdbc.queryForMap("SELECT * FROM prd_sku WHERE sku_no=?", sku),
                jdbc.queryForMap("SELECT * FROM pmt_user_coupon WHERE user_coupon_no=?", coupon));
    }

    private record EconomicSnapshot(
            Map<String, Long> counts, Map<String, Object> stock, Map<String, Object> coupon) {}

    @Intercepts(
            @Signature(
                    type = Executor.class,
                    method = "update",
                    args = {MappedStatement.class, Object.class}))
    private class ClaimCommitRevocation implements Interceptor {
        final AtomicBoolean armed = new AtomicBoolean(true);
        boolean updated;
        boolean committed;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (!statement.endsWith("CheckoutMappers$OperationMapper.updateById")) {
                return invocation.proceed();
            }
            Object parameter = invocation.getArgs()[1];
            Object row = parameter instanceof Map<?, ?> map ? map.get("et") : parameter;
            boolean ownClaim =
                    statement.endsWith("CheckoutMappers$OperationMapper.updateById")
                            && row instanceof OrdCheckoutOperation op
                            && ("op-" + sku).equals(op.getOperationNo())
                            && buyer.equals(op.getBuyerNo())
                            && op.getPlaceKeyHash() != null
                            && "CONFIRMED".equals(op.getPhase())
                            && Arrays.stream(Thread.currentThread().getStackTrace())
                                    .anyMatch(
                                            frame ->
                                                    frame.getClassName()
                                                                    .equals(
                                                                            CheckoutServiceImpl
                                                                                    .class
                                                                                    .getName())
                                                            && frame.getMethodName()
                                                                    .equals("place"));
            Object actual = invocation.proceed();
            if (ownClaim && armed.compareAndSet(true, false)) {
                assertThat(actual).isEqualTo(1);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                String token = SecurityUtils.currentToken().orElseThrow();
                assertThat(tokens.of(Realm.CONSUMER).get(token).orElseThrow().user().userNo())
                        .isEqualTo(buyer);
                updated = true;
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                committed = true;
                                tokens.of(Realm.CONSUMER).revoke(token);
                                assertThat(tokens.of(Realm.CONSUMER).get(token)).isEmpty();
                            }
                        });
            }
            return actual;
        }
    }
}
