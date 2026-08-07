package ai.neargo.shop.platform;

import ai.neargo.shop.platform.dto.OpsVOs.AuditLogVO;
import ai.neargo.shop.platform.dto.OpsVOs.LoginResultVO;
import ai.neargo.shop.platform.dto.OpsVOs.MerchantApplyVO;
import ai.neargo.shop.platform.dto.OpsVOs.StaffVO;

import java.util.List;

/** 平台端（[API 清单 §4.1 / §4.11]）。 */
public interface OpsService {

    LoginResultVO login(String username, String password);

    StaffVO me();

    List<StaffVO> staffList();

    List<MerchantApplyVO> applyQueue();

    /** 审核入驻。**驳回必须写理由**；通过才创建商家主体。 */
    void auditApply(String applyNo, boolean approved, String reason);

    /** C 端提交入驻申请（由 /mp/merchant/apply 调用）。 */
    String createApply(String userNo, String name, String type,
                       String contactPhone, List<String> qualifications);

    /** 写审计。高危操作必须调用。 */
    void audit(String action, String target, String detail);

    List<AuditLogVO> auditLogs(String target);
}
