package ai.neargo.shop.pay.acquiring.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_order（db/pay V7–V9 · TDD-支付服务-I3支付单退款流水 S1）：支付订单：调用方要收的一笔钱，独立于业务订单。一张支付单可有多次通道尝试（stl_payment）
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_order")
public class PayOrder extends PayBaseEntity {

    /** 支付单号（对外：接口响应、收银台、对账） */
    private String payOrderNo;

    /** 调用方 pay_app.id（内部系统也注册 app，如 MALL / FOOD） */
    private Long payAppId;

    /** 调用方订单号；与 pay_app_id 联合唯一（幂等） */
    private String outOrderNo;

    /** MALL_ORDER / FOOD_CHECK / STORED_VALUE / PARTNER / COLLECT … */
    private String bizType;

    /** 调用方业务单号（字符串，由调用方给出，不作关联） */
    private String bizNo;

    /** 门店 mch_store.id（核心系统；关联核心系统一律用门店 id） */
    private Long storeId;

    /** 成交时主体名 */
    private String entityNameSnapshot;

    /** 成交时门店名 */
    private String storeNameSnapshot;

    /** 市场码 */
    private String market;

    /** 币种 */
    private String currency;

    /** 应付金额（最小单位），建单后不改 */
    private Long amountMinor;

    /** 通道实收 */
    private Long paidMinor;

    /** 已退 */
    private Long refundedMinor;

    /** 退款复核 / 在途占用 */
    private Long reservedRefundMinor;

    /** 商品标题（展示在通道收银台） */
    private String subject;

    /** ONLINE / IN_STORE */
    private String scene;

    /** 选定方式后写入：ONLINE / OFFLINE / CARD */
    private String payType;

    /** 选定的统一支付方式码 */
    private String payMethod;

    /** 选定的支付产品 pay_channel_product.id */
    private Long payChannelProductId;

    /** 路由快照：收款商户 pay_merchant.id（选定方式时冻结，此后不改） */
    private Long payMerchantId;

    /** 路由快照：渠道码 */
    private String payChannel;

    /** 路由快照：接入模式 */
    private String accessMode;

    /** 路由快照：资金路径 */
    private String fundsPath;

    /** 路由快照：渠道子商户号（渠道侧字符串） */
    private String subMerchantId;

    /** 路由快照：密钥版本号 */
    private Integer secKeyVersion;

    /** CREATED / AWAITING_PAYMENT / AUTHORIZED / AWAITING_CONFIRM / SUCCEEDED / PARTIALLY_REFUNDED / REFUNDED / CLOSED / FAILED */
    private String status;

    /** 第三方 webhook 覆盖地址（白名单内） */
    private String notifyUrl;

    /** 付款后跳回（白名单内） */
    private String returnUrl;

    /** PARTNER / INTERNAL / TERMINAL / BIZ / OPS / CHECKOUT */
    private String sourceType;

    /** 来源 id：PARTNER / INTERNAL → pay_app.id；TERMINAL → pay_terminal.id；CHECKOUT → pay_qr_code.id 或 pay_checkout_session.id；BIZ / OPS → 账号 id */
    private Long sourceId;

    /** 收银员 / 商家操作员（审计字段） */
    private String operatorNo;

    /** 顾客 usr_account.id（已登录时） */
    private Long userId;

    /** 建单请求规范化摘要：同幂等键不同内容 → 拒绝 */
    private String requestSha256;

    /** 支付截止 */
    private LocalDateTime expireAt;

    /** 成功时间 */
    private LocalDateTime succeededAt;

    /** 关单时间 */
    private LocalDateTime closedAt;
    /** 付款人敏感信息的封存 sec_secret_blob.id（V15；开放 API 建单传入时） */
    private Long payerBlobId;
    /** 付款人敏感信息的掩码 JSON（V15）；查询只回它 */
    private String payerMasked;
}
