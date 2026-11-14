package ai.neargo.shop.platform;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.platform.dto.OpsVOs.MerchantApplyVO;
import ai.neargo.shop.spi.user.SelfOperatedAdmissionPort;

/** One read list; the application and platform-owned write workflows remain separate. */
public interface AdmissionManagementService {
    record Entry(String key, String type, String name, String ownerUserNo, String status,
                 long createdAt, MerchantApplyVO application,
                 SelfOperatedAdmissionPort.Entry selfOperated) {}

    PageData<Entry> search(String type, String status, String keyword, long page, long size,
                           boolean canAudit, boolean canSubmit, boolean canSelfOperate);
}
