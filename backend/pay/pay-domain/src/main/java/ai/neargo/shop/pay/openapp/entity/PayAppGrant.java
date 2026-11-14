package ai.neargo.shop.pay.openapp.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_app_grant（db/pay V11 · TDD-支付服务-I5接入层 S1）：门店授权某接入方代本店收款或退款。
 *
 * <p>字段注释取自表结构说明，逐列定义以它为准。
 */
@Getter
@Setter
@TableName("pay_app_grant")
public class PayAppGrant extends PayBaseEntity {

    /** 接入方 pay_app.id */
    private Long payAppId;

    /** 门店 mch_store.id（核心系统；关联核心系统一律用门店 id） */
    private Long storeId;

    /** 逗号分隔：COLLECT,REFUND,READ */
    private String actions;

    /** ACTIVE / REVOKED */
    private String status;

    /** 授权人（商家，审计字段） */
    private String grantedBy;

    /** 授权时间 */
    private LocalDateTime grantedAt;

    /** 撤销时间 */
    private LocalDateTime revokedAt;
}
