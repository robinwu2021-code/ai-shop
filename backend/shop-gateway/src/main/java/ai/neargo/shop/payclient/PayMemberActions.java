package ai.neargo.shop.payclient;

import ai.neargo.shop.auth.BizPerms;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * B 端码 → near-pay 商户端动作（TDD-支付三端经网关透传与权限归属 §13.3，用户 2026-11-22 确认）。
 *
 * <p>B 端支付请求经网关透传后，near-pay 拿 {@code /internal/auth/v1/merchant-staff} 给的码自己判权；它把码换成自己的动作
 * （{@code MerchantRole} 里那 14 个，加建商户前的 {@code merchant.link}）时用的就是这张表的反向。表在两边代码里各一份
 * （用户定：放代码、不放库），ai-shop 这份导出成 {@code docs/api/pay-member-actions.json}，near-pay 的对账测试读它，
 * 漂了 near-pay 构建红（{@code PayMemberActionsTest} 保证导出物与这里一致）。
 *
 * <p>与 {@link PayDownstreamActions} 不是一张表：那张是「码 → 支付内部入口判的动作」（{@code CREATE_ONLINE} 一套，
 * ai-shop 调 near-pay 内部接口时签进上下文）；这张是「码 → near-pay 公开 B 端接口判的动作」（{@code payment.create} 一套）。
 *
 * <p>只给店主的动作挂在 {@link BizPerms#STORE_ADMIN} 上：这个码不在可勾选的码里，只有店主（{@code *}）持有。
 * 退款复核与退款申请分开（ai-shop 只有一个退款码，分不开申请人与复核人），设备激活先不给店员。
 */
public final class PayMemberActions {

    public static final String PAYMENT_CREATE = "payment.create";
    public static final String PAYMENT_QUERY = "payment.query";
    public static final String PAYMENT_CLOSE = "payment.close";
    public static final String REFUND_APPLY = "refund.apply";
    public static final String REFUND_REVIEW = "refund.review";
    public static final String DEVICE_ACTIVATE = "device.activate";
    public static final String QRCODE_MANAGE = "qrcode.manage";
    public static final String STORE_LEDGER_READ = "store.ledger.read";
    public static final String STATEMENT_READ = "statement.read";
    public static final String ONBOARDING_APPLY = "onboarding.apply";
    public static final String ONBOARDING_QUERY = "onboarding.query";
    public static final String MEMBER_MANAGE = "member.manage";
    public static final String STORE_BIND = "store.bind";
    public static final String APP_GRANT_QUERY = "app.grant.query";
    public static final String APP_GRANT = "app.grant";
    public static final String MERCHANT_LINK = "merchant.link";

    /** 码 → 动作。顺序即导出顺序 */
    private static final Map<String, List<String>> TABLE = build();

    private PayMemberActions() {
    }

    private static Map<String, List<String>> build() {
        Map<String, List<String>> t = new LinkedHashMap<>();
        t.put(BizPerms.PAY_COLLECT, List.of(PAYMENT_CREATE, PAYMENT_CLOSE));
        t.put(BizPerms.PAY_ORDER_VIEW, List.of(PAYMENT_QUERY));
        t.put(BizPerms.PAY_REFUND, List.of(REFUND_APPLY));
        t.put(BizPerms.PAY_QR, List.of(QRCODE_MANAGE));
        t.put(BizPerms.PAY_STATEMENT, List.of(STORE_LEDGER_READ, STATEMENT_READ));
        t.put(BizPerms.PAY_APP_GRANT, List.of(APP_GRANT_QUERY, APP_GRANT));
        t.put(BizPerms.FINANCE, List.of(ONBOARDING_QUERY, ONBOARDING_APPLY));
        t.put(BizPerms.STORE_ADMIN, List.of(REFUND_REVIEW, DEVICE_ACTIVATE, STORE_BIND, MEMBER_MANAGE, MERCHANT_LINK));
        return Collections.unmodifiableMap(t);
    }

    /** 整张表（只读，按登记顺序） */
    public static Map<String, List<String>> table() {
        return TABLE;
    }

    /** 全部动作：店主（{@code *}）得到的就是这些 */
    public static List<String> all() {
        return List.copyOf(new TreeSet<>(TABLE.values().stream().flatMap(List::stream).toList()));
    }

    /** 一组码换成动作，去重排序；含 {@code *} 即全部 */
    public static List<String> actionsOf(Collection<String> codes) {
        if (codes.contains("*")) {
            return all();
        }
        return List.copyOf(new TreeSet<>(codes.stream().flatMap(c -> TABLE.getOrDefault(c, List.of()).stream()).toList()));
    }
}
