package ai.neargo.shop.platform;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.platform.dto.OpsVOs.AuditLogVO;

/**
 * 运营审计列表（平台域自己的页面）。写入走 {@code AuditLogPort}；审计表只在这两处读写，
 * 统一审计换到基础能力库时只改它们（TDD-基础能力库对齐 1a 守卫）。
 */
public interface AuditLogService {

    /** 按对象、关键字（操作人、动作、对象、说明）、是否高危筛选，新的在前。 */
    PageData<AuditLogVO> page(String target, String keyword, Boolean critical, long page, long size);
}
