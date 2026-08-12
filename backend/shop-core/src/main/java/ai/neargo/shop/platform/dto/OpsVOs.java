package ai.neargo.shop.platform.dto;

import java.util.List;

/** 平台端对外结构。 */
public final class OpsVOs {

    private OpsVOs() {
    }

    /**
     * {@code perms} <b>只用于前端控制展示</b>；真正的拦截在后端 @PreAuthorize。
     *
     * <p>数据域三个字段<b>空 = 不限定（全量）</b>。
     * ⚠️ 它们目前<b>只存不用</b> —— 各域查询还没按它裁剪，UI 上标明了尚未生效。
     */
    /**
     * @param mustChangePassword 首登必须改密。**要透给前端** ——
     *     不透的话新员工拿一次性密码登进来就能一直用，
     *     而那个密码经过了建号人的屏幕与剪贴板。
     */
    public record StaffVO(String staffNo, String username, String realName,
                          List<String> roles, List<String> perms, String status,
                          String merchantNo, String communityNo, String pickupNo,
                          Long lastLoginAt, boolean mustChangePassword) {
    }

    public record LoginResultVO(String token, StaffVO staff) {
    }

    /**
     * @param before 变更前结构化快照（原样透传的 JSON 字符串），没有则 null —— 前端不伪造
     * @param after  变更后结构化快照，同上
     */
    public record AuditLogVO(long logNo, String staffNo, String staffName, String action,
                             String target, String detail, long at,
                             String ip, String clientType, boolean critical,
                             String before, String after) {
    }

    /**
     * 入驻申请。审核页与 C 端「我的申请进度」共用。
     *
     * @param contactName  联系人姓名 —— 审核要打电话找人，只有号码不够
     * @param category     主营类目，决定通过后授予的类目授权范围
     * @param communityNos 覆盖社区。<b>通过时必须非空</b>（ADR-009），否则商家对谁都不可见
     * @param auditedAt    审核完成时间；PENDING/REVIEWING 期间为 0
     */
    public record MerchantApplyVO(String applyNo, String merchantNo, String name, String subject,
                                  String contactName, String contactPhone, String category,
                                  String desc, String serviceScope,
                                  List<String> communityNos, List<String> licenses,
                                  boolean asPickupPoint,
                                  /** 行业。审核页要看到它 —— 它决定这家店能不能开小微 */
                                  String industry,
                                  String status, String rejectReason,
                                  long createdAt, long auditedAt) {
    }
}
