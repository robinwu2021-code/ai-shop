package ai.neargo.shop.pay.acquiring.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_checkout_session（db/pay V7–V9 · TDD-支付服务-I3支付单退款流水 S1）：收银会话：扫码 H5 / 托管收银台的一次性令牌，绑定一张支付单
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_checkout_session")
public class PayCheckoutSession extends PayBaseEntity {

    /** 令牌哈希；明文只在链接里 */
    private String tokenSha256;

    /** 支付单 pay_order.id */
    private Long payOrderId;

    /** OPEN / PAYING / DONE / EXPIRED / REVOKED */
    private String status;

    /** 过期（≤ 支付单过期） */
    private LocalDateTime expireAt;

    /** 顾客 usr_account.id（登录后回填） */
    private Long userId;
}
