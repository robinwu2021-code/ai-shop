package ai.neargo.shop.platform.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.platform.AuditLogService;
import ai.neargo.shop.platform.dto.OpsVOs.AuditLogVO;
import ai.neargo.shop.platform.entity.SysAuditLog;
import ai.neargo.shop.platform.mapper.PlatformMappers.AuditLogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogMapper auditLogs;

    public AuditLogServiceImpl(AuditLogMapper auditLogs) {
        this.auditLogs = auditLogs;
    }

    @Override
    public PageData<AuditLogVO> page(String target, String keyword, Boolean critical, long page, long size) {
        var w = Wrappers.<SysAuditLog>lambdaQuery();
        if (target != null && !target.isBlank()) {
            w.eq(SysAuditLog::getTarget, target);
        }
        if (critical != null) {
            w.eq(SysAuditLog::getCritical, critical);
        }
        if (keyword != null && !keyword.isBlank()) {
            w.and(x -> x.like(SysAuditLog::getStaffNo, keyword)
                    .or().like(SysAuditLog::getStaffName, keyword)
                    .or().like(SysAuditLog::getOpAction, keyword)
                    .or().like(SysAuditLog::getTarget, keyword)
                    .or().like(SysAuditLog::getDetail, keyword));
        }
        w.orderByDesc(SysAuditLog::getId);
        Page<SysAuditLog> p = DataScopeContext.executeWithoutScope(() -> auditLogs.selectPage(Page.of(page, size), w));
        List<AuditLogVO> records = p.getRecords().stream()
                .map(l -> new AuditLogVO(l.getId(), l.getStaffNo(), l.getStaffName(), l.getOpAction(),
                        l.getTarget(), l.getDetail(), l.getAt() == null ? 0L : l.getAt(),
                        l.getIp(), l.getClientType(), Boolean.TRUE.equals(l.getCritical()),
                        l.getBeforeJson(), l.getAfterJson()))
                .toList();
        return PageData.of(records, p.getTotal(), p.getCurrent(), p.getSize());
    }
}
