package ai.neargo.shop.pay.merchant.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_merchant_binding（db/pay V10 · TDD-支付服务-I4进件与收款商户 S1）：收款路由绑定：门店 × 渠道同一时刻一条 ACTIVE。取代 mch_store.pay_merchant_no 与旧表的 store_no 维度（修 R02/R03/R26）
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_merchant_binding")
public class PayMerchantBinding extends PayBaseEntity {

    /** 门店 mch_store.id（核心系统；关联核心系统一律用门店 id） */
    private Long storeId;

    /** 渠道码 */
    private String payChannel;

    /** 收款商户 pay_merchant.id */
    private Long payMerchantId;

    /** ACTIVE / INACTIVE */
    private String status;

    /** ACTIVE 时 = store_id|pay_channel，否则 NULL；服务同事务维护 */
    private String activeKey;

    /** 绑定人（审计字段） */
    private String boundBy;

    /** 绑定时间 */
    private LocalDateTime boundAt;

    /** 解绑时间 */
    private LocalDateTime unboundAt;
}
