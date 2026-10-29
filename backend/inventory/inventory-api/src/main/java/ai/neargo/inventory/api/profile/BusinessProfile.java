package ai.neargo.inventory.api.profile;

import java.util.List;
import java.util.Set;

/**
 * 业态档案：业主级、带版本的配置（TDD §2.5）。业态之间的差异全部在这里，内核不出现任何业态名。
 *
 * <p>同一个 JSON 结构服务电商、餐饮、共享充电宝、充电桩；四份示例见
 * {@code inventory-domain} 测试资源 {@code profiles/}。
 *
 * @param schemaVersion   档案结构版本，当前为 1
 * @param capabilities    开通的能力包
 * @param targetTypes     外部目标类型（收货方、接收人…）及上级关系
 * @param inboundPurposes 启用的入库用途
 * @param outboundPurposes 启用的出库用途及收货方规则
 * @param gateRefTypes    放行凭证类型（QC、ACCEPTANCE…）
 * @param countReview     盘点复核规则
 */
public record BusinessProfile(
        int schemaVersion,
        Set<Capability> capabilities,
        List<TargetType> targetTypes,
        List<InboundPurpose> inboundPurposes,
        List<OutboundPurpose> outboundPurposes,
        List<String> gateRefTypes,
        CountReview countReview) {

    public static final int CURRENT_SCHEMA = 1;

    /**
     * 外部目标类型。
     *
     * @param code       类型码，如 {@code MERCHANT}
     * @param nameKey    展示名的文案键
     * @param parentType 上级类型码；空 = 顶层。如站点的上级是商家
     */
    public record TargetType(String code, String nameKey, String parentType) {
    }

    /** 入库用途：是否必须带依据 / 附件（如期初登记）。 */
    public record InboundPurpose(String code, boolean basisRequired, boolean attachmentRequired) {
    }

    /**
     * 出库用途的收货方规则。
     *
     * @param requiredTargets 必须指定的目标类型
     * @param optionalTargets 可以指定的目标类型（须挂在某个必填目标之下，按 parentType 校验）
     * @param handover        {@code POOL} = 过账生成出库池记录；{@code NONE} = 不生成
     */
    public record OutboundPurpose(String code, List<String> requiredTargets, List<String> optionalTargets,
                                  Handover handover) {
    }

    public enum Handover { POOL, NONE }

    /** 盘点是否必须复核、复核人是否必须不同于录入人。 */
    public record CountReview(boolean required, boolean reviewerMustDiffer) {
    }
}
