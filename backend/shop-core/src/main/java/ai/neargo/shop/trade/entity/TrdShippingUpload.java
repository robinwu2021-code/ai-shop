package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 微信发货信息录入的上报台账（V323）。
 *
 * <p><b>它存在是因为上报会失败，而失败的代价是这笔钱结不出来</b> ——
 * 用户端毫无感知，商家几天后才发现。没有这张表的话，那次失败不留任何痕迹，
 * 也没有任何东西会再提起它。
 *
 * <p>幂等靠<b>台账 + 「10060002 订单已发货当成功」</b>两者合起来：
 * 只有台账没有那个码的处理，重启后照样会重复调；
 * 只有那个码没有台账，就没法回答「这笔到底报过没有」。
 */
@Getter
@Setter
@TableName("trd_shipping_upload")
public class TrdShippingUpload extends BaseEntity {

    /** 待上报。**新建即此态** —— 先落库再调用，顺序不能反 */
    public static final String PENDING = "PENDING";
    public static final String SUCCESS = "SUCCESS";
    /** 不可重试的失败（参数错、未开通）。**要人看**，不该继续占着重试队列 */
    public static final String FAILED = "FAILED";

    private String orderNo;

    /** 支付单的商户单号 —— 上报按它定位微信那笔单 */
    private String outTradeNo;

    /** 微信四类。映射只在 {@code WxLogisticsTypes} 一处 */
    private Integer logisticsType;

    private String status;

    private Integer attempts;

    /** 微信的错误码与原话，<b>原样存</b> —— 排查时要的正是那句话 */
    private Integer errCode;
    private String errMsg;

    private java.time.LocalDateTime uploadedAt;
}
