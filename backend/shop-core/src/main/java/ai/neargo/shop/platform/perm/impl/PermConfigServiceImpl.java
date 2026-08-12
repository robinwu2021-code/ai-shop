package ai.neargo.shop.platform.perm.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.platform.perm.PermConfigService;
import ai.neargo.shop.platform.perm.entity.SysFunction;
import ai.neargo.shop.platform.perm.entity.SysFunctionPoint;
import ai.neargo.shop.platform.perm.entity.SysRole;
import ai.neargo.shop.platform.perm.entity.SysRoleMember;
import ai.neargo.shop.platform.perm.entity.SysRolePoint;
import ai.neargo.shop.platform.perm.mapper.PermMappers.FunctionMapper;
import ai.neargo.shop.platform.perm.mapper.PermMappers.FunctionPointMapper;
import ai.neargo.shop.platform.perm.mapper.PermMappers.RoleMapper;
import ai.neargo.shop.platform.perm.mapper.PermMappers.RoleMemberMapper;
import ai.neargo.shop.platform.perm.mapper.PermMappers.RolePointMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

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

    public PermConfigServiceImpl(FunctionMapper functionMapper, FunctionPointMapper pointMapper,
                                 RoleMapper roleMapper, RolePointMapper rolePointMapper,
                                 RoleMemberMapper memberMapper) {
        this.functionMapper = functionMapper;
        this.pointMapper = pointMapper;
        this.roleMapper = roleMapper;
        this.rolePointMapper = rolePointMapper;
        this.memberMapper = memberMapper;
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
        return roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getEndCode, end).orderByAsc(SysRole::getSort))
                .stream()
                .map(r -> new RoleVO(r.getRoleCode(), r.getName(), r.getEndCode(),
                        !Boolean.FALSE.equals(r.getBuiltin()),
                        counts.getOrDefault(r.getRoleCode(), 0L).intValue()))
                .toList();
    }

    @Override
    public List<String> rolePoints(String roleCode) {
        return rolePointMapper.selectList(Wrappers.<SysRolePoint>lambdaQuery()
                        .eq(SysRolePoint::getRoleCode, roleCode))
                .stream().map(SysRolePoint::getPointCode).toList();
    }
}
