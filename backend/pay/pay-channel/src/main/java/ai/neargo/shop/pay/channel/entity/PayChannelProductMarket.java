package ai.neargo.shop.pay.channel.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_channel_product_market（db/pay V4–V6 · TDD-支付服务-I2渠道统一封装 S1）：支付产品在哪些国家地区可用，及该地区的币种与金额范围。无行 = 该产品在该地区不可用
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_channel_product_market")
public class PayChannelProductMarket extends PayBaseEntity {

    /** 支付产品 pay_channel_product.id */
    private Long payChannelProductId;

    /** 市场码（值域，见 sys_market） */
    private String market;

    /** 该地区收款币种 */
    private String currency;

    /** 单笔下限（最小单位） */
    private Long singleMinMinor;

    /** 单笔上限；0 = 不限 */
    private Long singleMaxMinor;

    /** 出通道金额精度：TWD 在蓝新 / LINE Pay 为 0（整数元），AED 为 2；有余数即拒 */
    private Integer amountScale;

    /** ENABLED / DISABLED */
    private String status;
}
