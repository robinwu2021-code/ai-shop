package ai.neargo.shop.platform.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.RequestMetaContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.platform.entity.SysAuditLog;
import ai.neargo.shop.platform.mapper.PlatformMappers.AuditLogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * {@link AuditLogPort} 实现：运营审计 {@code sys_audit_log} 的唯一写入方。
 *
 * <p>别的域只认这个接口；运营后台自己的 {@code OpsService#audit} 也转调这里。
 * 审计属于基础能力库，写入集中在这一个类，换库或换成事件流时只改这里。
 */
@Component
public class AuditLogPortImpl implements AuditLogPort {

    private final AuditLogMapper auditLogs;

    public AuditLogPortImpl(AuditLogMapper auditLogs) {
        this.auditLogs = auditLogs;
    }

    @Override
    public void record(String action, String target, String detail) {
        record(action, target, detail, false, null, null);
    }

    @Override
    public void record(String action, String target, String detail, boolean critical) {
        record(action, target, detail, critical, null, null);
    }

    @Override
    public void record(String action, String target, String detail, boolean critical,
                        String beforeJson, String afterJson) {
        LoginUser operator = SecurityUtils.currentUser().orElse(null);
        SysAuditLog log = new SysAuditLog();
        log.setStaffNo(operator == null ? "SYSTEM" : operator.userNo());
        log.setStaffName(operator == null ? "系统" : operator.nickname());
        log.setOpAction(action);
        log.setTarget(target);
        log.setDetail(detail);
        log.setAt(System.currentTimeMillis());
        log.setTenantNo("MAIN");
        log.setCreatedAt(LocalDateTime.now());
        log.setCritical(critical);
        log.setBeforeJson(beforeJson);
        log.setAfterJson(afterJson);
        /*
         * IP/操作端不直接碰 HttpServletRequest —— platform 是领域代码，
         * 不能依赖 web 运行时（ArchitectureTest.domainsMustNotTouchWebRuntime）。
         * 认证过滤器把这两样塞进 RequestMetaContext，这里只读；非请求线程
         * （SYSTEM 触发、后台任务）取不到就是 null，不是 bug。
         */
        RequestMetaContext.Meta meta = RequestMetaContext.current();
        log.setIp(meta == null ? null : meta.ip());
        log.setClientType(meta == null ? null : meta.clientType());
        DataScopeContext.executeWithoutScope(() -> auditLogs.insert(log));
    }

    /** 查询本身留在 platform 域里 —— 别的域只拿到一个数 */
    @Override
    public long countSince(String action, long since) {
        return auditLogs.selectCount(Wrappers.<SysAuditLog>lambdaQuery()
                .eq(SysAuditLog::getOpAction, action)
                .ge(SysAuditLog::getAt, since));
    }
}
