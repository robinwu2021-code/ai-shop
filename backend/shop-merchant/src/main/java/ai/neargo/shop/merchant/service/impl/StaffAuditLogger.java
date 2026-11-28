package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 员工与授权的审计（B-11.10.3），以及人员、资源维护的留痕：读写都只在这里。
 *
 * <p>平台基础库换表 I（拆库清单：「各处的变更日志都是审计」）：原 {@code mch_staff_log} 不进新库，
 * 改写基础能力库的统一审计（{@link AuditLogPort}）。审计对象 = {@code MERCHANT_STAFF:<租户>|<主体号>}，
 * 一主体一条流，按主体倒序翻页即原来的「授权记录」页；被操作的人、门店、角色、操作人放在审计快照里。
 *
 * <p><b>写失败不抛</b>，与 {@code AuditLogPort} 同口径：授权已经生效了却因为写日志回滚，
 * 是拿一条记录去换一次真实的业务操作。统一审计本身在事务提交后才写，回滚的操作不留痕。
 */
@Component
public class StaffAuditLogger {

    private static final Logger LOG = LoggerFactory.getLogger(StaffAuditLogger.class);
    /** 一次最多读回多少条（原接口 limit 200） */
    static final int PAGE = 200;

    /** 动作码（原 {@code MchStaffLog} 上的常量；码值不变，B 端按它翻译） */
    public static final String STAFF_ADD = "STAFF_ADD";
    public static final String STAFF_ENABLE = "STAFF_ENABLE";
    public static final String STAFF_DISABLE = "STAFF_DISABLE";
    public static final String STAFF_PHONE_CHANGE = "STAFF_PHONE_CHANGE";
    public static final String ROLE_GRANT = "ROLE_GRANT";
    public static final String ROLE_REVOKE = "ROLE_REVOKE";
    public static final String ROLE_CREATE = "ROLE_CREATE";
    public static final String ROLE_UPDATE = "ROLE_UPDATE";
    public static final String ROLE_DELETE = "ROLE_DELETE";

    /**
     * 读回的一条。
     *
     * @param actor 操作人：写入时的登录身份 {@code userNo}（读侧按账号号与用户号两种键都认）；取不到为空
     */
    public record Entry(String logNo, String actor, String targetAccountNo, String action, String storeNo,
                        String role, String detail, long at) { }

    private final AuditLogPort audit;
    private final ObjectMapper json;

    public StaffAuditLogger(AuditLogPort audit, ObjectMapper json) {
        this.audit = audit;
        this.json = json;
    }

    public static String target(String entityNo) {
        return "MERCHANT_STAFF:MAIN|" + entityNo;
    }

    /** 对某个人做的事：加人、启停、给某店某角色、撤某店某角色 */
    public void staff(String merchantNo, String targetAccountNo, String action,
                      String storeNo, String role, String detail) {
        write(merchantNo, currentActor(), targetAccountNo, action, storeNo, role, detail);
    }

    /**
     * 对**角色定义本身**做的事：新建 / 改权限 / 删除。没有具体的「被操作的人」，影响的是所有持有者。
     */
    public void role(String merchantNo, String action, String roleCode, String detail) {
        write(merchantNo, currentActor(), null, action, null, roleCode, detail);
    }

    /**
     * 维护留痕（人员状态原因、人员与资源的保存证据）：操作人由调用方给出。
     *
     * @return 这条记录的编号（调用方可以把它作为引用写进别的记录）
     */
    public String evidence(String entityNo, String storeNo, String actor, String action, String detail) {
        return write(entityNo, actor, null, action, storeNo, null, detail);
    }

    /** 某主体的记录，新的在前；{@code targetAccountNo} 非空时只要对这个人做的 */
    public List<Entry> read(String entityNo, String targetAccountNo) {
        var out = new ArrayList<Entry>();
        long before = Long.MAX_VALUE;
        while (out.size() < PAGE) {
            var page = audit.byTarget(target(entityNo), before, PAGE);
            for (var row : page) {
                before = Math.min(before, row.id());
                var entry = parse(row);
                if (targetAccountNo == null || targetAccountNo.isBlank()
                        || targetAccountNo.equals(entry.targetAccountNo())) {
                    out.add(entry);
                    if (out.size() == PAGE) {
                        break;
                    }
                }
            }
            if (page.size() < PAGE) {
                break;
            }
        }
        return out;
    }

    private String write(String entityNo, String actor, String targetAccountNo, String action,
                         String storeNo, String role, String detail) {
        String logNo = BizKey.next(BizKey.STAFF_LOG);
        try {
            var snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("logNo", logNo);
            snapshot.put("entityNo", entityNo);
            snapshot.put("actor", actor);
            snapshot.put("targetAccountNo", targetAccountNo);
            snapshot.put("storeNo", storeNo);
            snapshot.put("role", role);
            audit.record(action, target(entityNo), detail, false, null, json.writeValueAsString(snapshot));
        } catch (RuntimeException e) {
            LOG.warn("员工授权日志写入失败 entity={} action={} target={}", entityNo, action, targetAccountNo, e);
        }
        return logNo;
    }

    @SuppressWarnings("unchecked")
    private Entry parse(AuditLogPort.Entry row) {
        Map<String, Object> snapshot = row.afterJson() == null ? Map.of() : json.readValue(row.afterJson(), Map.class);
        return new Entry(str(snapshot.get("logNo")), str(snapshot.get("actor")), str(snapshot.get("targetAccountNo")),
                row.action(), str(snapshot.get("storeNo")), str(snapshot.get("role")), row.detail(), row.at());
    }

    private static String str(Object value) {
        return value == null ? null : Objects.toString(value);
    }

    /*
     * 存的是 **userNo**，而不是 mch_account_no：读侧（MerchantStaffServiceImpl.logs）两种键都认。
     */
    private static String currentActor() {
        return SecurityUtils.currentUser().map(LoginUser::userNo).orElse(null);
    }
}
