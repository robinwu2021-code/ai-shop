package ai.neargo.shop.platform.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.RequestMetaContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.platform.entity.SysAuditLog;
import ai.neargo.shop.platform.mapper.PlatformMappers.AuditLogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * {@link AuditLogPort} 实现：运营审计 {@code sys_audit_log} 的唯一写入方。
 *
 * <p>别的域只认这个接口；运营后台自己的 {@code OpsService#audit} 也转调这里。
 * 审计属于基础能力库，写入集中在这一个类，换库或换成事件流时只改这里。
 *
 * <p><b>业务提交后才写</b>（TDD-基础能力库对齐 1i）：调用方在事务里时，这一行登记到提交之后，
 * 回滚则不写；写失败只记日志，不连累已经提交的业务。基础能力库将来与业务库分开，审计也不会把两个库拉进同一个事务。
 * 操作人、IP 在调用线程上取好再交出去。提交回调里本线程的数据库资源还绑着刚提交的事务，
 * 直接写会并进一个不再提交的事务、静默丢失，所以换到后台线程写（同 {@code AfterCommitNotifications}）。
 * {@code shop.audit.after-commit.wait=true}（测试用）时提交线程等写完再返回。
 */
@Component
public class AuditLogPortImpl implements AuditLogPort, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AuditLogPortImpl.class);

    private final AuditLogMapper auditLogs;
    private final boolean waitForWrite;
    private final ExecutorService writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10_000), r -> {
                Thread t = new Thread(r, "audit-after-commit");
                t.setDaemon(true);
                return t;
            });

    public AuditLogPortImpl(AuditLogMapper auditLogs,
                            @Value("${shop.audit.after-commit.wait:false}") boolean waitForWrite) {
        this.auditLogs = auditLogs;
        this.waitForWrite = waitForWrite;
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
        SysAuditLog row = new SysAuditLog();
        row.setStaffNo(operator == null ? "SYSTEM" : operator.userNo());
        row.setStaffName(operator == null ? "系统" : operator.nickname());
        row.setOpAction(action);
        row.setTarget(target);
        row.setDetail(detail);
        row.setAt(System.currentTimeMillis());
        row.setTenantNo("MAIN");
        row.setCreatedAt(LocalDateTime.now());
        row.setCritical(critical);
        row.setBeforeJson(beforeJson);
        row.setAfterJson(afterJson);
        /*
         * IP/操作端不直接碰 HttpServletRequest —— platform 是领域代码，
         * 不能依赖 web 运行时（ArchitectureTest.domainsMustNotTouchWebRuntime）。
         * 认证过滤器把这两样塞进 RequestMetaContext，这里只读；非请求线程
         * （SYSTEM 触发、后台任务）取不到就是 null，不是 bug。
         */
        RequestMetaContext.Meta meta = RequestMetaContext.current();
        row.setIp(meta == null ? null : meta.ip());
        row.setClientType(meta == null ? null : meta.clientType());
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            write(row);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(row);
            }
        });
    }

    private void submit(SysAuditLog row) {
        try {
            if (waitForWrite) {
                writer.submit(() -> write(row)).get(30, TimeUnit.SECONDS);
            } else {
                writer.execute(() -> write(row));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 队列满或等待超时：业务已经提交，审计丢这一条，留日志可追
            log.error("audit.write_dropped action={} target={}", row.getOpAction(), row.getTarget(), e);
        }
    }

    private void write(SysAuditLog row) {
        try {
            DataScopeContext.executeWithoutScope(() -> auditLogs.insert(row));
        } catch (RuntimeException e) {
            // 审计失败不该让业务失败（AuditLogPort 的约定）；业务此时已提交或本就不在事务里
            log.error("audit.write_failed action={} target={}", row.getOpAction(), row.getTarget(), e);
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        writer.shutdown();
        writer.awaitTermination(5, TimeUnit.SECONDS);
    }

    /** 查询本身留在 platform 域里 —— 别的域只拿到一个数 */
    @Override
    public long countSince(String action, long since) {
        return auditLogs.selectCount(Wrappers.<SysAuditLog>lambdaQuery()
                .eq(SysAuditLog::getOpAction, action)
                .ge(SysAuditLog::getAt, since));
    }
}
