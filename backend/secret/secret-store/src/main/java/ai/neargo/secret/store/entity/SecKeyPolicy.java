package ai.neargo.secret.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * sec_key_policy（db/secret V1 · TDD-支付服务-W5密钥模块）：密钥策略：按命名空间、归属类型与环境规定有效期、到期提醒、轮换重叠期、双人控制
 *
 * <p>字段注释取自 DDL，逐列定义以表结构说明 §6.8 为准。
 */
@Getter
@Setter
@TableName("sec_key_policy")
public class SecKeyPolicy extends SecBaseEntity {

    /** 命名空间 */
    private String namespace;

    /** PAY_MERCHANT / CHANNEL_PLATFORM / PARTNER_APP / INTERNAL_APP / TERMINAL / PROVIDER … */
    private String ownerType;

    /** SANDBOX / PRODUCTION */
    private String env;

    /** 版本最长使用天数；0 = 不强制（以证书有效期为准） */
    private Integer maxAgeDays;

    /** 到期前多少天提醒 */
    private Integer warnBeforeDays;

    /** 轮换重叠期：新版本启用后旧版本仍可用的天数 */
    private Integer overlapDays;

    /** 启用 / 吊销是否需要两人 */
    private Boolean dualControl;

    /** 启用前是否必须测试调用通过 */
    private Boolean verifyBeforeEnable;

    /** 平台生成的密钥明文是否只在发放时展示一次 */
    private Boolean revealOnIssueOnly;
}
