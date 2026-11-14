package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.MappingContracts.VerificationBatch;

/** 可信后台任务入口；仅查询证明，不接受外部JSON核验结果。 */
public interface ResourceMappingVerificationService {
    VerificationBatch verifyDue(int limit);
}
