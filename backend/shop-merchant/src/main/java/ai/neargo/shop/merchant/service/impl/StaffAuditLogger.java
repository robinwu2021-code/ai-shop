package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.merchant.entity.MchStaffLog;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStaffLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 员工与授权的审计写入（B-11.10.3）。
 *
 * <p>从 {@code MerchantStaffServiceImpl} 里抽出来，因为**角色定义的变更也要留痕**
 * （V71）：改一个角色的权限码，所有持有者同时变，比给某个人授权影响更大。
 * 两个 Service 各写一份的话，「什么算一条审计」这件事就有了两种答案。
 *
 * <p><b>写失败不抛</b>，与 {@code AuditLogPort} 同口径：
 * 授权已经生效了却因为写日志回滚，是拿一条记录去换一次真实的业务操作；
 * 反过来「日志写失败」也不该让老板看到「系统开小差」——他做的事成了。
 */
@Component
public class StaffAuditLogger {

    private static final Logger LOG = LoggerFactory.getLogger(StaffAuditLogger.class);

    private final MchStaffLogMapper mapper;

    public StaffAuditLogger(MchStaffLogMapper mapper) {
        this.mapper = mapper;
    }

    /** 对某个人做的事：加人、启停、给某店某角色、撤某店某角色 */
    public void staff(String merchantNo, String targetAccountNo, String action,
                      String storeNo, String role, String detail) {
        write(merchantNo, targetAccountNo, action, storeNo, role, detail);
    }

    /**
     * 对**角色定义本身**做的事：新建 / 改权限 / 删除。
     *
     * <p>{@code targetAccountNo} 为空 —— 这类记录没有具体的「被操作的人」，
     * 而它影响的是**所有持有者**。V71 把那一列从 NOT NULL 放开正是为了它。
     */
    public void role(String merchantNo, String action, String roleCode, String detail) {
        write(merchantNo, null, action, null, roleCode, detail);
    }

    private void write(String merchantNo, String targetAccountNo, String action,
                       String storeNo, String role, String detail) {
        try {
            MchStaffLog row = new MchStaffLog();
            row.setLogNo(BizKey.next(BizKey.STAFF_LOG));
            row.setEntityNo(merchantNo);
            /*
             * 这里存的是 **userNo**，而不是 `mch_account_no` —— 列名叫 actor_account_no
             * 容易让人以为是后者。读侧（`MerchantStaffServiceImpl.logs`）两种键都认，
             * 改这里之前先去看那一段：只改一处的话，历史 17 条会再次查不出操作人。
             */
            row.setActorAccountNo(SecurityUtils.currentUser().map(LoginUser::userNo).orElse(null));
            row.setTargetAccountNo(targetAccountNo);
            row.setAction(action);
            row.setStoreNo(storeNo);
            row.setRole(role);
            row.setDetail(detail);
            DataScopeContext.executeWithoutScope(() -> mapper.insert(row));
        } catch (RuntimeException e) {
            LOG.warn("员工授权日志写入失败 entity={} action={} target={}",
                    merchantNo, action, targetAccountNo, e);
        }
    }
}
