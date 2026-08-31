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

    /**
     * <b>用 {@code setFieldValByName} 而不是 {@code strictUpdateFill}。</b>
     *
     * <p>后者<b>只在字段为 null 时才填</b>（MyBatis-Plus 的 {@code strictFillStrategy}
     * 里就是一个 {@code == null} 判断）。而更新走的都是「先查出实体、改几个字段、
     * 再 updateById」——查出来的实体上 {@code updatedAt} 一定有值，
     * 于是这两列**从插入那一刻起就再也没变过**。
     *
     * <p>症状是沉默的：字段有值、看着正常，只是永远等于 {@code createdAt}。
     * 发现它是在售后页上 —— 那一页每行显示的是「最后一次状态变更时间」，
     * 一张已经同意过的退货单仍然显示申请时间。往库里一查，
     * 507 行数据、version 最高到 5，<b>没有一行的 updated_at 与 created_at 不同</b>。
     *
     * <p>连带失去的还有 {@code updatedBy}：它停在申请人身上，
     * 「谁同意的这笔退款」在数据里查不到 —— 而这正是审计列存在的理由。
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        setFieldValByName("updatedAt", LocalDateTime.now(), metaObject);
        setFieldValByName("updatedBy", currentOperator(), metaObject);
    }

    private String currentOperator() {
        return SecurityUtils.currentUser().map(u -> u.userNo()).orElse(SYSTEM);
    }
}
