package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 不可变的美业政策版本，存在行业配置修订表 {@code ind_config_revision}，配置类型 {@code STORE_POLICY}
 * （TDD-美业域迁移新库 B-2）。美业保留历史版本，按生效时间或按头指针取。读一律经 {@link #beautyQuery()}。
 */
@Getter
@Setter
@TableName("ind_config_revision")
public class BtyStorePolicyVersion extends BaseEntity {
    public static final String CONFIG_KIND = "STORE_POLICY";

    /** 共用表的 {@code tenant_no} 没有库默认值（旧美业表默认 'MAIN'），平台审计也不填它：在这里带上。 */
    public BtyStorePolicyVersion() {
        setTenantNo("MAIN");
    }

    /** 主体数据域；由可信调用范围确定。 */
    private String entityNo;
    /** 版本号。 */
    private String policyVersionNo;
    /** 配置类型：门店经营策略。 */
    private String configKind = CONFIG_KIND;
    /** 政策。 */
    private String policyNo;
    /** 门店。 */
    private String storeNo;
    /** 版本序号。 */
    private Integer revisionIndex;
    /** 预约窗口。 */
    private Integer bookingAheadDays;
    /** 取消规则（JSON）。 */
    private String cancelRule;
    /** 已获准营业模式（JSON 数组）。 */
    private String allowedModes;
    /** 生效。 */
    private LocalDateTime effectiveAt;
    /** 规则结构版本。 */
    private Integer schemaVersion;
    /** 摘要。 */
    private String snapshotHash;

    /** 只看门店经营策略的修订。 */
    public static LambdaQueryWrapper<BtyStorePolicyVersion> beautyQuery() {
        return Wrappers.<BtyStorePolicyVersion>lambdaQuery().eq(BtyStorePolicyVersion::getConfigKind, CONFIG_KIND);
    }
}
