package ai.neargo.shop.pay.merchant.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_merchant_product（db/pay V10 · TDD-支付服务-I4进件与收款商户 S1）：收款商户开通的支付产品。只能由渠道回执或运营确认写入，禁止按主体类型推断
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_merchant_product")
public class PayMerchantProduct extends PayBaseEntity {

    /** 收款商户 pay_merchant.id */
    private Long payMerchantId;

    /** 支付产品 pay_channel_product.id */
    private Long payChannelProductId;

    /** OPEN / CLOSED */
    private String status;

    /** CHANNEL_RECEIPT / OPS_CONFIRMED */
    private String source;

    /** 累计收款额度；0 = 未设置（不是额度为零） */
    private Long quotaLimitMinor;

    /** 额度周期标识，如 2026 / 2026-10 */
    private String quotaPeriod;

    /** 本周期已用；累加必须检查乐观锁返回值（修 R24） */
    private Long quotaUsedMinor;

    /** 开通时间 */
    private LocalDateTime openedAt;
}
