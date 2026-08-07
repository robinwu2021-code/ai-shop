package ai.neargo.shop.platform.dto;

import java.util.List;

/** 平台端对外结构。 */
public final class OpsVOs {

    private OpsVOs() {
    }

    /** {@code perms} **只用于前端控制展示**；真正的拦截在后端 @PreAuthorize。 */
    public record StaffVO(String staffNo, String username, String realName,
                          List<String> roles, List<String> perms, String status) {
    }

    public record LoginResultVO(String token, StaffVO staff) {
    }

    public record AuditLogVO(String staffNo, String staffName, String action,
                             String target, String detail, long at) {
    }

    public record MerchantApplyVO(String applyNo, String merchantNo, String name, String type,
                                  String contactPhone, List<String> qualifications,
                                  String status, String rejectReason, long createdAt) {
    }
}
