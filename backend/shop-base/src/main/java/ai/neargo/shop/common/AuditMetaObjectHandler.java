package ai.neargo.shop.common;

import ai.neargo.shop.auth.SecurityUtils;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充。业务代码不写 {@code setCreatedAt/By}，写了也会被这里覆盖。
 *
 * <p>取不到登录态时填 {@code SYSTEM}（定时任务、回调、启动初始化都属于这一类），
 * 而不是留 null —— 「谁改的」这一列如果允许为空，事故复盘时就永远缺一半线索。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    private static final String SYSTEM = "SYSTEM";
    private static final String TENANT_MAIN = "MAIN";

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        String operator = currentOperator();
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "createdBy", String.class, operator);
        strictInsertFill(metaObject, "updatedBy", String.class, operator);
        strictInsertFill(metaObject, "tenantNo", String.class, TENANT_MAIN);
        strictInsertFill(metaObject, "deleted", Integer.class, 0);
        strictInsertFill(metaObject, "version", Long.class, 0L);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        strictUpdateFill(metaObject, "updatedBy", String.class, currentOperator());
    }

    private String currentOperator() {
        return SecurityUtils.currentUser().map(u -> u.userNo()).orElse(SYSTEM);
    }
}
