package ai.neargo.shop.pay.checkout.service;

import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.service.PaymentService.StartPaymentResult;
import java.util.List;

/**
 * 收银台（TDD-支付服务-I5接入层 S7；安全规范 §2.5）：顾客完全不受信 —— 金额、收款方、可用方式全部来自服务端，
 * 顾客只能看本单、选方式、看结果；静态码只有金额由顾客输入，受单笔与单码日累计上限约束。
 *
 * <p>令牌不存在一律 {@code NOT_FOUND}；过期、已作废、单已关 {@code PAY_CHECKOUT_EXPIRED}（410）—— 除此之外不区分原因。
 */
public interface CheckoutService {

    /** 给一张支付单签发收银令牌（托管收银台 / 动态码）：随机 32 字节，库里只存哈希，有效期不超过支付单 */
    Issued issue(PayOrder order);

    SessionView view(String token);

    StartPaymentResult pay(String token, String payMethod, String payerOpenId, String payerAppId);

    /** 只给「SUCCEEDED / PROCESSING / FAILED」，不入账（入账只认渠道回调与主动查单） */
    default String result(String token) {
        return resultView(token).result();
    }

    /** 同 {@link #result}，成功时多给渠道交易号后 6 位（成功页给顾客对账；TDD-门店收银台与付款链接 L6） */
    ResultView resultView(String token);

    CodeView code(String qrCode);

    /** 静态码：顾客输金额，服务端以码的门店建单并签发令牌 */
    Issued orderByCode(String qrCode, long amountMinor);

    /**
     * 商家代客收款（F42）：商家定金额，以本店建单并签发令牌（动态码）。单记在收银台接入方名下，操作员是商家账号。
     *
     * @param caller 商家侧调用方（{@code InternalCallers#merchantOperator}），门店范围只有本店
     */
    Issued collect(ai.neargo.shop.pay.access.PayCaller caller, Long storeId, long amountMinor, String subject,
                   Integer expireMinutes);

    /**
     * 付款链接再次分享（TDD-门店收银台与付款链接 L4）：令牌原文不落库，所以只能给同一张支付单换发新令牌、旧令牌作废。
     * 有效期从现在起按原时长（不超过上限）。只允许待付款的本店付款链接；其余按「没有」或「已失效」拒。
     */
    Issued reissue(ai.neargo.shop.pay.access.PayCaller caller, Long storeId, String payOrderNo);

    /**
     * 作废付款链接（L5）：先查单再关单（{@code PaymentService#close}）。关前查到已付就不关，原样返回已付的单；
     * 关成了把这张单的令牌记成 {@code VOIDED}，操作人记在会话的 {@code updated_by}。
     */
    PayOrder voidCollect(ai.neargo.shop.pay.access.PayCaller caller, Long storeId, String payOrderNo, String reason);

    record Issued(String payOrderNo, String token, long expireAt) {
    }

    /** @param tradeNoSuffix 只有 SUCCEEDED 才有：渠道交易号后 6 位 */
    record ResultView(String result, String tradeNoSuffix) {
    }

    /** 字段白名单（F34）：收款方与门店名、金额、方式；没有任何个人信息与内部 id */
    /**
     * @param currencyScale 币种小数位（金额是最小单位，前端按它排版）；市场没配为 {@code null} —— 前端此时不显示金额、不让付
     * @param market        市场（前端按它选默认语言）
     * @param subject       收款说明（付款链接上商家填的那句；没填是店名）
     */
    record SessionView(String payOrderNo, String payeeName, String storeName, long amountMinor, String currency,
                       Integer currencyScale, String market, String status, List<String> payMethods, long expireAt,
                       String subject) {
    }

    record CodeView(String payeeName, String storeName, String kind, String tableRef, String currency,
                    Integer currencyScale, String market, long singleLimitMinor) {
    }
}
