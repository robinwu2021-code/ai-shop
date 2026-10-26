package ai.neargo.shop.pay.channel.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * pay_channel_lifecycle_log（db/pay V4–V6 · TDD-支付服务-I2渠道统一封装 S1）：渠道适配器生命周期日志：每个「渠道 × 收款商户」运行时实例的一次状态变化
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_channel_lifecycle_log")
public class PayChannelLifecycleLog extends PayBaseEntity {

    /** 渠道码 */
    private String payChannel;

    /** 收款商户 pay_merchant.id；渠道级状态（整个渠道停用）为空 */
    private Long payMerchantId;

    /** 原状态：REGISTERED / INITIALIZED / ACTIVE / DEGRADED / SUSPENDED / DRAINING / STOPPED */
    private String fromState;

    /** 新状态 */
    private String toState;

    /** 原因：熔断打开、健康检查失败、运营停用、凭据吊销、应用关闭… */
    private String reason;

    /** 操作人（系统触发为 SYSTEM） */
    private String operatorNo;

    /** 发生时间 */
    private LocalDateTime occurredAt;
}
