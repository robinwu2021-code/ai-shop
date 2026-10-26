package ai.neargo.shop.pay.access;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.policy.impl.MatrixCallerPolicy;
import ai.neargo.shop.pay.channel.api.CallerType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 能力矩阵逐格（含全部禁止格；TDD-支付服务-I3支付单退款流水 AC2）。
 *
 * <p>期望值<b>在这里另抄一份</b>，逐格对照设计 v4 §2.8.2 —— 与实现共用一张表的测试，改错了表照样绿。
 */
class CallerPolicyMatrixTest {

    private static final long STORE = 7L;
    private final MatrixCallerPolicy policy = new MatrixCallerPolicy();

    /** 设计 v4 §2.8.2：✅ 的格 */
    private static final Map<CallerType, Set<PayAction>> ALLOWED = Map.of(
            CallerType.PARTNER, Set.of(PayAction.QUERY_METHODS, PayAction.CREATE_ONLINE, PayAction.START_PAYMENT,
                    PayAction.QUERY, PayAction.CLOSE, PayAction.REQUEST_REFUND, PayAction.READ_LEDGER),
            CallerType.INTERNAL, Set.of(PayAction.QUERY_METHODS, PayAction.CREATE_ONLINE, PayAction.CREATE_IN_STORE,
                    PayAction.START_PAYMENT, PayAction.QUERY, PayAction.CLOSE, PayAction.READ_LEDGER),
            CallerType.TERMINAL, Set.of(PayAction.QUERY_METHODS, PayAction.CREATE_IN_STORE, PayAction.START_PAYMENT,
                    PayAction.QUERY, PayAction.CLOSE, PayAction.VOID, PayAction.REQUEST_REFUND, PayAction.READ_LEDGER,
                    PayAction.TERMINAL_BATCH),
            CallerType.BIZ, Set.of(PayAction.QUERY_METHODS, PayAction.CREATE_ONLINE, PayAction.QUERY, PayAction.CLOSE,
                    PayAction.VOID, PayAction.REQUEST_REFUND, PayAction.READ_LEDGER, PayAction.TERMINAL_BATCH),
            CallerType.OPS, Set.of(PayAction.QUERY_METHODS, PayAction.QUERY, PayAction.CLOSE, PayAction.REVIEW_REFUND,
                    PayAction.READ_LEDGER, PayAction.TERMINAL_BATCH),
            CallerType.CHECKOUT, Set.of(PayAction.QUERY_METHODS, PayAction.CREATE_ONLINE, PayAction.START_PAYMENT,
                    PayAction.QUERY));

    static Stream<Arguments> everyCell() {
        List<Arguments> cells = new ArrayList<>();
        for (CallerType t : CallerType.values()) {
            for (PayAction a : PayAction.values()) {
                cells.add(Arguments.of(t, a, ALLOWED.get(t).contains(a)));
            }
        }
        return cells.stream();
    }

    private static PayCaller caller(CallerType type) {
        return new PayCaller(type, 1L, Set.of(STORE), "OP-1", null, null);
    }

    @ParameterizedTest(name = "{0} × {1} → {2}")
    @MethodSource("everyCell")
    @DisplayName("★★★ 矩阵逐格：✅ 放行，其余一律 PAY_FORBIDDEN")
    void cell(CallerType type, PayAction action, boolean allowed) {
        if (allowed) {
            assertThatCode(() -> policy.check(caller(type), action, STORE)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> policy.check(caller(type), action, STORE)).isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.PAY_FORBIDDEN);
        }
    }

    @Test
    @DisplayName("★★★ 内部系统不能以系统身份退款 —— 有操作员也不行，要换成商家操作员身份（BIZ）")
    void internalSystemCannotRefund() {
        assertThatThrownBy(() -> policy.check(caller(CallerType.INTERNAL), PayAction.REQUEST_REFUND, STORE))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.PAY_FORBIDDEN);
    }

    @Test
    @DisplayName("★★★ 退款没有操作员 → PAY_REFUND_OPERATOR_REQUIRED（无人可追责的退款一笔不放）")
    void refundWithoutOperatorRejected() {
        var anonymous = new PayCaller(CallerType.BIZ, 1L, Set.of(STORE), " ", null, null);
        assertThatThrownBy(() -> policy.check(anonymous, PayAction.REQUEST_REFUND, STORE))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.PAY_REFUND_OPERATOR_REQUIRED);
    }

    @Test
    @DisplayName("★★★ 门店范围外一律拒（内部系统也受范围约束，防的是代码 bug）；运营端是平台身份不按门店圈")
    void storeScope() {
        assertThatThrownBy(() -> policy.check(caller(CallerType.INTERNAL), PayAction.QUERY, 8L))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.PAY_FORBIDDEN);
        assertThatCode(() -> policy.check(caller(CallerType.OPS), PayAction.QUERY, 8L)).doesNotThrowAnyException();
    }
}
