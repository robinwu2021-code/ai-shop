package ai.neargo.shop.platform.perm;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.platform.perm.entity.SysRoleMember;
import ai.neargo.shop.platform.perm.mapper.PermMappers.RoleMemberMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营账号有哪些角色：唯一的读写入口，数据在基础能力库的 {@code sys_role_member}（TDD-基础能力库对齐 2a、Q3）。
 *
 * <p>此前角色双写：{@code sys_ops_staff.roles} 的 JSON 判权，{@code sys_role_member} 出菜单，两处靠每个写入点各自同步。
 * 现在判权、菜单、账号列表都读这里，JSON 列停写（存量由 V721 对齐）。
 *
 * <p>角色按授予顺序给出（行的先后）。
 */
@Component
public class OpsStaffRoles {

    private static final String OPS = "OPS";

    private final RoleMemberMapper members;

    public OpsStaffRoles(RoleMemberMapper members) {
        this.members = members;
    }

    /** 一个账号的角色；没有就是空 */
    public List<String> of(String staffNo) {
        if (staffNo == null) {
            return List.of();
        }
        return of(List.of(staffNo)).getOrDefault(staffNo, List.of());
    }

    /** 一批账号的角色：账号 → 角色。没有角色的账号不在结果里 */
    public Map<String, List<String>> of(Collection<String> staffNos) {
        if (staffNos == null || staffNos.isEmpty()) {
            return Map.of();
        }
        return group(DataScopeContext.executeWithoutScope(() -> members.selectList(Wrappers.<SysRoleMember>lambdaQuery()
                .eq(SysRoleMember::getEndCode, OPS)
                .in(SysRoleMember::getSubjectNo, new LinkedHashSet<>(staffNos))
                .orderByAsc(SysRoleMember::getId))));
    }

    /** 全部运营账号的角色（身份快照整批重建用） */
    public Map<String, List<String>> all() {
        return group(DataScopeContext.executeWithoutScope(() -> members.selectList(Wrappers.<SysRoleMember>lambdaQuery()
                .eq(SysRoleMember::getEndCode, OPS)
                .orderByAsc(SysRoleMember::getId))));
    }

    /**
     * 把账号的角色改成给定集合。
     *
     * <p><b>按集合增删，不是清空重插</b>：{@code granted_at} / {@code granted_by} 是审计信息 ——
     * 清空重插会把「这个角色是三个月前谁给的」改成「今天我给的」，而那正是权限审计要查的东西。只动真正变化的那几行。
     */
    @Transactional(transactionManager = ModuleDataSources.COMMON_TX_MANAGER)
    public void set(String staffNo, List<String> roles) {
        List<SysRoleMember> existing = DataScopeContext.executeWithoutScope(() -> members.selectList(
                Wrappers.<SysRoleMember>lambdaQuery()
                        .eq(SysRoleMember::getEndCode, OPS)
                        .eq(SysRoleMember::getSubjectNo, staffNo)));
        Set<String> want = new LinkedHashSet<>(roles);
        Set<String> have = existing.stream().map(SysRoleMember::getRoleCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (SysRoleMember m : existing) {
            if (!want.contains(m.getRoleCode())) {
                DataScopeContext.executeWithoutScope(() -> members.deleteById(m.getId()));
            }
        }
        for (String role : want) {
            if (have.contains(role)) {
                continue;
            }
            SysRoleMember m = new SysRoleMember();
            m.setEndCode(OPS);
            m.setSubjectNo(staffNo);
            m.setRoleCode(role);
            // 后台播种、迁移没有登录人：授予人留空
            m.setGrantedBy(SecurityUtils.currentUser().map(LoginUser::userNo).orElse(null));
            m.setGrantedAt(System.currentTimeMillis());
            DataScopeContext.executeWithoutScope(() -> members.insert(m));
        }
    }

    private static Map<String, List<String>> group(List<SysRoleMember> rows) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (SysRoleMember m : rows) {
            List<String> roles = out.computeIfAbsent(m.getSubjectNo(), k -> new ArrayList<>());
            if (!roles.contains(m.getRoleCode())) {
                roles.add(m.getRoleCode());
            }
        }
        out.replaceAll((k, v) -> List.copyOf(v));
        return out;
    }
}
