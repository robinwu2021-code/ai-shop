package ai.neargo.shop.pay.access.policy;

import static ai.neargo.shop.pay.access.PayAction.CLOSE;
import static ai.neargo.shop.pay.access.PayAction.CREATE_IN_STORE;
import static ai.neargo.shop.pay.access.PayAction.CREATE_ONLINE;
import static ai.neargo.shop.pay.access.PayAction.QUERY;
import static ai.neargo.shop.pay.access.PayAction.QUERY_METHODS;
import static ai.neargo.shop.pay.access.PayAction.READ_LEDGER;
import static ai.neargo.shop.pay.access.PayAction.REQUEST_REFUND;
import static ai.neargo.shop.pay.access.PayAction.REVIEW_REFUND;
import static ai.neargo.shop.pay.access.PayAction.START_PAYMENT;
import static ai.neargo.shop.pay.access.PayAction.TERMINAL_BATCH;
import static ai.neargo.shop.pay.access.PayAction.VOID;

import ai.neargo.shop.pay.access.PayAction;
import ai.neargo.shop.pay.channel.api.CallerType;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 能力矩阵本体：六类接入端各自能做什么。<b>唯一定义处</b>（设计 v4 §2.8.2），逐格由 {@code CallerPolicyMatrixTest} 钉住。
 *
 * <p>读矩阵时最容易漏的两格：
 * <ul>
 *   <li><b>退款的发起方只有商户侧</b>（商户端、本店刷卡机、商户授权的第三方）；运营端只复核不发起 ——
 *       发起与复核分属两方，平台内部人员无法单方面把商户的钱退出去；</li>
 *   <li><b>内部系统不能以系统身份退款</b>：商城售后、餐饮 POS 的退款按钮背后是商户操作员身份（{@link CallerType#BIZ}），
 *       否则一个代码 bug 就能批量退款。</li>
 * </ul>
 */
public final class CapabilityMatrix {

    private static final Map<CallerType, Set<PayAction>> MATRIX = new EnumMap<>(CallerType.class);

    static {
        MATRIX.put(CallerType.PARTNER, EnumSet.of(QUERY_METHODS, CREATE_ONLINE, START_PAYMENT, QUERY, CLOSE,
                REQUEST_REFUND, READ_LEDGER));
        MATRIX.put(CallerType.INTERNAL, EnumSet.of(QUERY_METHODS, CREATE_ONLINE, CREATE_IN_STORE, START_PAYMENT, QUERY,
                CLOSE, READ_LEDGER));
        MATRIX.put(CallerType.TERMINAL, EnumSet.of(QUERY_METHODS, CREATE_IN_STORE, START_PAYMENT, QUERY, CLOSE, VOID,
                REQUEST_REFUND, READ_LEDGER, TERMINAL_BATCH));
        MATRIX.put(CallerType.BIZ, EnumSet.of(QUERY_METHODS, CREATE_ONLINE, QUERY, CLOSE, VOID, REQUEST_REFUND,
                READ_LEDGER, TERMINAL_BATCH));
        MATRIX.put(CallerType.OPS, EnumSet.of(QUERY_METHODS, QUERY, CLOSE, REVIEW_REFUND, READ_LEDGER, TERMINAL_BATCH));
        MATRIX.put(CallerType.CHECKOUT, EnumSet.of(QUERY_METHODS, CREATE_ONLINE, START_PAYMENT, QUERY));
    }

    private CapabilityMatrix() {
    }

    public static boolean allows(CallerType type, PayAction action) {
        return MATRIX.getOrDefault(type, Set.of()).contains(action);
    }

    public static Set<PayAction> actionsOf(CallerType type) {
        return Set.copyOf(MATRIX.getOrDefault(type, Set.of()));
    }
}
