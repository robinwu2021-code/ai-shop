package ai.neargo.shop.pay.channel.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_channel_product（db/pay V4–V6 · TDD-支付服务-I2渠道统一封装 S1）：支付产品：渠道 × 接入模式 × 支付类型 × 支付方式。运营开关；实际能力以代码实现的接口为准，启动比对
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_channel_product")
public class PayChannelProduct extends PayBaseEntity {

    /** 产品码（种子数据的自然键），如 NEWEBPAY_CREDIT、LINEPAY_ONLINE、PAYBY_POS_CARD */
    private String productCode;

    /** 渠道码（值域，见 sys_pay_channel） */
    private String payChannel;

    /** MERCHANT_OWN / SERVICE_PROVIDER / PLATFORM_SUB：用谁的凭据调渠道 */
    private String accessMode;

    /** ONLINE / OFFLINE / CARD：走哪类能力接口 */
    private String payType;

    /** 统一支付方式码：CARD_ONLINE / APPLE_PAY / ATM / CVS / LINE_PAY / TAIWAN_PAY / WALLET / JSAPI / SCAN_CUSTOMER / STORE_QR / CARD_PRESENT … */
    private String payMethod;

    /** 渠道侧方式码：蓝新 CREDIT / VACC / CVS，微信 JSAPI，PayBy 产品码 */
    private String channelMethod;

    /** 逗号分隔：DIRECT / AGGREGATED；TW 两家只允许 DIRECT */
    private String allowedFundsPaths;

    /** ONLINE 原路退 / OFFLINE 无原路退需线下 / UNSUPPORTED */
    private String refundMode;

    /** 是否支持部分退款 */
    private Boolean partialRefund;

    /** 单笔最多部分退款次数；0 = 未知 / 不限 */
    private Integer maxPartialRefunds;

    /** 两次退款最小间隔 */
    private Integer refundIntervalSeconds;

    /** 可退款天数；0 = 未知 */
    private Integer refundWindowDays;

    /** 是否两段式（ATM / 超商取号后再缴费；LINE Pay 授权后 confirm） */
    private Boolean twoPhase;

    /** ENABLED / DISABLED */
    private String status;

    /** 收银台排序 */
    private Integer sortNo;
}
