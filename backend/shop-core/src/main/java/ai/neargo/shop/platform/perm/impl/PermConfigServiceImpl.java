package ai.neargo.shop.platform.perm.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.platform.perm.RolePermResolver;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.platform.perm.PermConfigService;
import ai.neargo.shop.platform.perm.entity.SysFunction;
import ai.neargo.shop.platform.perm.entity.SysFunctionPoint;
import ai.neargo.shop.platform.perm.entity.SysRole;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.platform.perm.entity.SysRoleMember;
import ai.neargo.shop.platform.perm.entity.SysRolePoint;
import ai.neargo.shop.platform.perm.mapper.PermMappers.FunctionMapper;
import ai.neargo.shop.platform.perm.mapper.PermMappers.FunctionPointMapper;
import ai.neargo.shop.platform.perm.mapper.PermMappers.RoleMapper;
import ai.neargo.shop.platform.perm.mapper.PermMappers.RoleMemberMapper;
import ai.neargo.shop.platform.perm.mapper.PermMappers.RolePointMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class PermConfigServiceImpl implements PermConfigService {

    private static final String OPS = "OPS";

    private final FunctionMapper functionMapper;
    private final FunctionPointMapper pointMapper;
    private final RoleMapper roleMapper;
    private final RolePointMapper rolePointMapper;
    private final RoleMemberMapper memberMapper;
    private final RolePermResolver resolver;
    private final AuditLogPort auditLogPort;
    private final TokenStore tokenStore;

    public PermConfigServiceImpl(FunctionMapper functionMapper, FunctionPointMapper pointMapper,
                                 RoleMapper roleMapper, RolePointMapper rolePointMapper,
                                 RoleMemberMapper memberMapper, RolePermResolver resolver,
                                 AuditLogPort auditLogPort, TokenStore tokenStore) {
        this.functionMapper = functionMapper;
        this.pointMapper = pointMapper;
        this.roleMapper = roleMapper;
        this.rolePointMapper = rolePointMapper;
        this.memberMapper = memberMapper;
        this.resolver = resolver;
        this.auditLogPort = auditLogPort;
        this.tokenStore = tokenStore;
    }

    @Override
    public List<MenuFunctionVO> menu() {
        String staffNo = SecurityUtils.currentUserNo();
        List<String> roles = memberMapper.selectList(Wrappers.<SysRoleMember>lambdaQuery()
                        .eq(SysRoleMember::getEndCode, OPS)
                        .eq(SysRoleMember::getSubjectNo, staffNo))
                .stream().map(SysRoleMember::getRoleCode).toList();
        /*
         * **零角色 = 空菜单**，不是「默认给点什么」。
         * 认不出身份时给权限，是这类判定最坏的失败方式 ——
         * 与 BizPerms「空角色集 = 零权限」同一条规矩。
         */
        if (roles.isEmpty()) {
            return List.of();
        }
        Set<String> pointCodes = rolePointMapper.selectList(Wrappers.<SysRolePoint>lambdaQuery()
                        .eq(SysRolePoint::getEndCode, OPS)
                        .in(SysRolePoint::getRoleCode, roles))
                .stream().map(SysRolePoint::getPointCode).collect(Collectors.toSet());
        // 菜单只渲染 MENU 类型 —— ACTION 是页面内的按钮级授权，塞进导航会多出几十行看不懂的项
        return build(OPS, p -> pointCodes.contains(p.getPointCode())
                && !"ACTION".equals(p.getPointType()));
    }

    @Override
    public List<MenuFunctionVO> functions(String endCode) {
        return build(endCode == null || endCode.isBlank() ? OPS : endCode, p -> true);
    }

    private List<MenuFunctionVO> build(String endCode, Predicate<SysFunctionPoint> keep) {
        List<SysFunction> fns = functionMapper.selectList(Wrappers.<SysFunction>lambdaQuery()
                .eq(SysFunction::getEndCode, endCode)
                .orderByAsc(SysFunction::getSort));
        Map<String, List<MenuPointVO>> kept = new LinkedHashMap<>();
        Map<String, Integer> total = new LinkedHashMap<>();
        for (SysFunctionPoint p : pointMapper.selectList(Wrappers.<SysFunctionPoint>lambdaQuery()
                .orderByAsc(SysFunctionPoint::getSort))) {
            total.merge(p.getFunctionCode(), 1, Integer::sum);
            if (keep.test(p)) {
                kept.computeIfAbsent(p.getFunctionCode(), k -> new ArrayList<>()).add(toVO(p));
            }
        }
        List<MenuFunctionVO> out = new ArrayList<>();
        for (SysFunction f : fns) {
            List<MenuPointVO> ps = kept.getOrDefault(f.getFunctionCode(), List.of());
            /*
             * 本来就没有叶子的分区（经营看板）要留；
             * 有叶子但一条都没授权的分区不返回 —— 否则点进去是个空页面。
             */
            if (ps.isEmpty() && total.getOrDefault(f.getFunctionCode(), 0) > 0) {
                continue;
            }
            out.add(new MenuFunctionVO(f.getFunctionCode(), f.getName(), f.getIcon(), f.getHref(),
                    f.getSort() == null ? 0 : f.getSort(), ps));
        }
        return out;
    }

    private static MenuPointVO toVO(SysFunctionPoint p) {
        return new MenuPointVO(p.getPointCode(), p.getName(), p.getGroupName(), p.getHref(),
                p.getUiPermCode(), p.getPermCode(), p.getBackendStatus(),
                !Boolean.FALSE.equals(p.getUiReady()), p.getMatrixCode(),
                p.getPointType() == null ? "MENU" : p.getPointType(),
                p.getSort() == null ? 0 : p.getSort());
    }

    @Override
    public List<RoleVO> roles(String endCode) {
        String end = endCode == null || endCode.isBlank() ? OPS : endCode;
        Map<String, Long> counts = rolePointMapper.selectList(Wrappers.<SysRolePoint>lambdaQuery()
                        .eq(SysRolePoint::getEndCode, end))
                .stream().collect(Collectors.groupingBy(SysRolePoint::getRoleCode,
                        Collectors.counting()));
        Map<String, Long> members = memberMapper.selectList(Wrappers.<SysRoleMember>lambdaQuery()
                        .eq(SysRoleMember::getEndCode, end))
                .stream().collect(Collectors.groupingBy(SysRoleMember::getRoleCode,
                        Collectors.counting()));
        return roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getEndCode, end).orderByAsc(SysRole::getSort))
                .stream()
                .map(r -> new RoleVO(r.getRoleCode(), r.getName(), r.getEndCode(),
                        !Boolean.FALSE.equals(r.getBuiltin()),
                        counts.getOrDefault(r.getRoleCode(), 0L).intValue(),
                        members.getOrDefault(r.getRoleCode(), 0L).intValue()))
                .toList();
    }

    // ---------------------------------------------------------------- 写侧

    @Override
    @Transactional
    public RoleVO createRole(String roleCode, String name, String operatorNo) {
        if (roleCode == null || roleCode.isBlank() || name == null || name.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (find(roleCode) != null) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        SysRole r = new SysRole();
        r.setRoleCode(roleCode);
        r.setName(name);
        r.setEndCode(OPS);
        r.setBuiltin(false);
        // **不能建通配角色** —— 否则有 staff:manage 的人都能造一个超管
        r.setWildcard(false);
        r.setSort(900);
        roleMapper.insert(r);
        auditLogPort.record("PERM_ROLE_CREATE", roleCode, name);
        return new RoleVO(roleCode, name, OPS, false, 0, 0);
    }

    @Override
    @Transactional
    public RoleVO setRolePoints(String roleCode, List<String> pointCodes, String operatorNo) {
        SysRole r = requireRole(roleCode);
        /*
         * **预置角色拒绝修改**：它们是 Perms.java 的镜像。
         * 改了库而代码不动，一致性守卫会红 —— 而守卫红不是重点，
         * 重点是**回落路径会与主路径分叉**：库里查不到时用的是代码那份，
         * 什么时候回落不由我们决定。
         */
        if (!Boolean.FALSE.equals(r.getBuiltin())) {
            throw BizException.of(ErrorCode.PERM_BUILTIN_ROLE_READONLY, roleCode);
        }
        List<String> codes = pointCodes == null ? List.of() : pointCodes;
        // 功能点必须存在 —— 写进一个不存在的码，那条授权永远不生效且查不出来
        for (String pc : codes) {
            if (pointMapper.selectCount(Wrappers.<SysFunctionPoint>lambdaQuery()
                    .eq(SysFunctionPoint::getPointCode, pc)) == 0) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
        }
        rolePointMapper.delete(Wrappers.<SysRolePoint>lambdaQuery()
                .eq(SysRolePoint::getRoleCode, roleCode).eq(SysRolePoint::getEndCode, OPS));
        for (String pc : codes) {
            SysRolePoint rp = new SysRolePoint();
            rp.setRoleCode(roleCode);
            rp.setPointCode(pc);
            rp.setEndCode(OPS);
            rolePointMapper.insert(rp);
        }
        // 判权读的是缓存过的整表快照，不清就要等重启
        resolver.invalidate();
        /*
         * **还要踢持有者的会话**。清缓存只让下一次「算权限」拿到新配置，
         * 而 perms 是<b>登录那一刻</b>算好塞进会话的 —— 不踢的话，
         * 已经登录的人要等下次登录才生效，而收紧权限恰恰是最需要立刻生效的场景。
         *
         * 旁边三个改人的写接口（setStaffEnabled/Role/Scope）都调了 revokeUser，
         * 这里上一批漏了。
         */
        int kicked = 0;
        for (SysRoleMember m : memberMapper.selectList(Wrappers.<SysRoleMember>lambdaQuery()
                .eq(SysRoleMember::getEndCode, OPS).eq(SysRoleMember::getRoleCode, roleCode))) {
            kicked += tokenStore.revokeUser(m.getSubjectNo());
        }
        auditLogPort.record("PERM_ROLE_POINTS", roleCode,
                codes.size() + " 个功能点，踢下线 " + kicked + " 个会话");
        return new RoleVO(roleCode, r.getName(), OPS, false, codes.size(),
                memberMapper.selectCount(Wrappers.<SysRoleMember>lambdaQuery()
                        .eq(SysRoleMember::getEndCode, OPS)
                        .eq(SysRoleMember::getRoleCode, roleCode)).intValue());
    }

    @Override
    @Transactional
    public void deleteRole(String roleCode, String operatorNo) {
        SysRole r = requireRole(roleCode);
        if (!Boolean.FALSE.equals(r.getBuiltin())) {
            throw BizException.of(ErrorCode.PERM_BUILTIN_ROLE_READONLY, roleCode);
        }
        /*
         * **还有人在用就不让删**。删了之后那些人的 perms 变成空集 ——
         * 他们能登录、界面一片空白，而看不出是「角色被删了」。
         * 与「不能停用自己」同一类：拦住的成本远低于事后查明。
         */
        long inUse = memberMapper.selectCount(Wrappers.<SysRoleMember>lambdaQuery()
                .eq(SysRoleMember::getRoleCode, roleCode));
        if (inUse > 0) {
            throw BizException.of(ErrorCode.PERM_ROLE_IN_USE, roleCode, inUse);
        }
        rolePointMapper.delete(Wrappers.<SysRolePoint>lambdaQuery()
                .eq(SysRolePoint::getRoleCode, roleCode));
        roleMapper.deleteById(r.getId());
        resolver.invalidate();
        auditLogPort.record("PERM_ROLE_DELETE", roleCode, r.getName());
    }

    private SysRole find(String roleCode) {
        return roleMapper.selectOne(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getEndCode, OPS).eq(SysRole::getRoleCode, roleCode).last("LIMIT 1"));
    }

    private SysRole requireRole(String roleCode) {
        SysRole r = find(roleCode);
        if (r == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return r;
    }

    @Override
    public List<String> rolePoints(String roleCode) {
        return rolePointMapper.selectList(Wrappers.<SysRolePoint>lambdaQuery()
                        .eq(SysRolePoint::getRoleCode, roleCode))
                .stream().map(SysRolePoint::getPointCode).toList();
    }
}
