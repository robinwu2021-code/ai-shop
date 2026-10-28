package ai.neargo.shop.pay.terminal.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_manager_code（db/pay V16 · pay-guard 开发计划 M6.1）：店长授权码：终端撤销 / 退款的授权，由商家端设置，只存哈希。
 *
 * <p>字段注释取自表结构真源 pay-service-database.json。
 */
@Getter
@Setter
@TableName("pay_manager_code")
public class PayManagerCode extends PayBaseEntity {

    /** 门店 mch_store.id（核心系统；关联核心系统一律用门店 id） */
    private Long storeId;

    /** 店长账号 mch_account.id（核心系统） */
    private Long managerAccountId;

    /** 授权码哈希（加盐慢哈希） */
    private String codeHash;

    /** ACTIVE / REVOKED */
    private String status;

    /** 连续失败次数（超限锁定） */
    private Integer failedAttempts;

    /** 锁定至 */
    private LocalDateTime lockedUntil;
}
