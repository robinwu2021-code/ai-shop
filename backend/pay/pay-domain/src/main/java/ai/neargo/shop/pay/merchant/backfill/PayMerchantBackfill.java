package ai.neargo.shop.pay.merchant.backfill;

import java.util.List;

/**
 * 回填 B1 / B2 / B4（执行方案 §4.5；表结构说明 §8.1；TDD-支付服务-I4进件与收款商户 S3）。
 * 支付域读不到 {@code mch_*}：旧行与它解析好的门店身份由 shop-app 的回填任务读好交进来。全部幂等（按收款商户号 / 外部单号）。
 */
public interface PayMerchantBackfill {

    /** B1：平台级收款商户（服务商号、平台账户），按（渠道, 渠道商户号）幂等。返回 id */
    Long platformMerchant(String payChannel, String channelMerchantId, String market, String currency, String accessMode);

    /**
     * B2 + B4：一行旧 {@code mch_payment_merchant} → 进件单（+ v1）与（已开户时）收款商户 + 开通产品。
     *
     * @return 新建或已有的收款商户 id（未开户为空）与这一行的冲突
     */
    Result merchant(LegacyMerchant row);

    /**
     * 双写（S4）：同 {@link #merchant}，但<b>旧表说了算</b> —— 已有的进件单跟上状态与申请单号（换了申请单号 = 重提，
     * 另起一版），已有的收款商户跟上状态、资金路径与资料；状态或资金路径变了各留一条状态日志。
     */
    Result mirror(LegacyMerchant row);

    /**
     * 一行旧收款商户，连同回填任务替它解析好的事实。
     *
     * @param storeId           开户门店 id：{@code store_no} 非空 → 该店；空串 → 主体默认门店
     * @param accessMode        渠道接入模式（按部署配置：微信收付通 / 支付宝 → SERVICE_PROVIDER，微信直连 → MERCHANT_OWN）
     * @param parentPayMerchantId SERVICE_PROVIDER / PLATFORM_SUB 时的平台级父商户（B1）
     * @param payMethods        旧 {@code pay_methods} 的值（JSAPI / H5 …）
     */
    record LegacyMerchant(Long legacyId, String entityName, Long storeId, String payChannel, String payMerchantNo,
                          String applyStatus, String subMchid, String channelApplyNo, String rejectReason,
                          String legalForm, boolean invoiceCapable, String settleAccountType, String settleAccountMasked,
                          String feeBearer, String settleCycle, Boolean splitReversible, long quotaLimitMinor,
                          long quotaUsedMinor, String quotaPeriod, String market, String currency, String fundsPath,
                          String accessMode, Long parentPayMerchantId, String parentChannelMerchantId,
                          List<String> payMethods) {
    }

    record Result(Long payMerchantId, List<String> conflicts) {
    }
}
