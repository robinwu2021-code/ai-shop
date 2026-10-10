package ai.neargo.shop.spi.platform;

import java.util.Optional;

/**
 * merchant → platform：查这家店的入驻申请里填了什么。
 *
 * <p>为什么需要它：联系人、是否愿意承接自提点这些字段落在**申请单**上
 * （{@code mch_entity_apply}），而不是主体上 —— 它们是「这次申请填的资料」，
 * 不是「这家店的属性」。商家治理页要展示它们，但 merchant 域不能直连 platform 域的表。
 *
 * <p>只暴露展示需要的三个字段，不返回整张申请单：Port 一旦返回实体，
 * 调用方会顺手用上审核状态，而那正是这一轮刚拆开的两件事
 * （审核状态在申请单，经营状态在主体）。
 */
public interface MerchantApplyQueryPort {

    /**
     * @param entityNo 主体业务键
     * @return 该主体最近一份申请里的联系资料；从没申请过时为空
     */
    Optional<ApplyContact> latestOf(String entityNo);

    /**
     * @param contactPhone 已脱敏。完整号码属于越权边界（矩阵 §2.3 / M11）
     * @param asPickupPoint 申请人是否愿意承接自提点。**只是意愿，通过审核不会自动建点**
     */
    record ApplyContact(String contactName, String contactPhone, boolean asPickupPoint,
                        String rejectReason) {
    }
}
