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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PermConfigServiceImpl implements PermConfigService {

    private static final String OPS = "OPS";

    /**
     * 角色码格式：大写字母开头，只能有大写字母/数字/下划线，2~32 位。
     *
     * <p>它是授权的键——{@code sys_role_point}/{@code sys_role_member} 都指着这个字符串，
     * 不是展示用的名字。前端此前只做了「自动转大写」，没有拦真正非法的字符
     * （空格、连字符、中文……），那种码存进库以后，改名容易、改码就是换一个角色，
     * 格式必须在写入那一刻就挡住，不能指望前端每次都记得转换正确。
     */
    private static final Pattern ROLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");

    private final FunctionMapper functionMapper;
    private final FunctionPointMapper pointMapper;
    private final RoleMapper roleMapper;
    private final RolePointMapper rolePointMapper;
    private final RoleMemberMapper memberMapper;
    private final RolePermResolver resolver;
    private final AuditLogPort auditLogPort;
    private final TokenStore tokenStore;
    /** 分池之后踢人必须指明是哪个池，见 TokenStores 的类注释 */
    private final ai.neargo.shop.auth.TokenStores tokenStores;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public PermConfigServiceImpl(FunctionMapper functionMapper, FunctionPointMapper pointMapper,
                                 RoleMapper roleMapper, RolePointMapper rolePointMapper,
                                 RoleMemberMapper memberMapper, RolePermResolver resolver,
                                 AuditLogPort auditLogPort, TokenStore tokenStore, ai.neargo.shop.auth.TokenStores tokenStores, 
                                 tools.jackson.databind.ObjectMapper objectMapper) {
        this.functionMapper = functionMapper;
        this.pointMapper = pointMapper;
        this.roleMapper = roleMapper;
        this.rolePointMapper = rolePointMapper;
        this.memberMapper = memberMapper;
        this.resolver = resolver;
        this.auditLogPort = auditLogPort;
        this.tokenStore = tokenStore;
        this.tokenStores = tokenStores;
        this.objectMapper = objectMapper;
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
                && !"ACTION".equals(p.getPointType()),
                p -> !"ACTION".equals(p.getPointType()));
    }

    @Override
    public List<MenuFunctionVO> functions(String endCode) {
        return build(endCode == null || endCode.isBlank() ? OPS : endCode, p -> true);
    }

    private List<MenuFunctionVO> build(String endCode, Predicate<SysFunctionPoint> keep) {
        return build(endCode, keep, p -> true);
    }

    /**
     * @param keep      这个点要不要返回（授权 + 类型）
     * @param candidate 这个点**算不算本视图的叶子** —— 决定「这个分区本来就没有叶子」怎么判。
     *                  菜单视图里 ACTION 不算叶子：它是页面内的按钮级授权，没有菜单入口。
     *
     *                  <p>不区分的后果实测过：经营看板有 1 个 ACTION 点、0 个菜单点，
     *                  于是被判成「有叶子但一条都没授权」而整个分区不返回 ——
     *                  端上因此拿不到它的 sort，**菜单里它的顺序怎么调都不动**，
     *                  而且不报错。
     */
    private List<MenuFunctionVO> build(String endCode, Predicate<SysFunctionPoint> keep,
                                       Predicate<SysFunctionPoint> candidate) {
        List<SysFunction> fns = functionMapper.selectList(Wrappers.<SysFunction>lambdaQuery()
                .eq(SysFunction::getEndCode, endCode)
                .orderByAsc(SysFunction::getSort));
        Map<String, List<MenuPointVO>> kept = new LinkedHashMap<>();
        Map<String, Integer> total = new LinkedHashMap<>();
        for (SysFunctionPoint p : pointMapper.selectList(Wrappers.<SysFunctionPoint>lambdaQuery()
                .orderByAsc(SysFunctionPoint::getSort))) {
            if (candidate.test(p)) {
                total.merge(p.getFunctionCode(), 1, Integer::sum);
            }
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
        if (!ROLE_CODE.matcher(roleCode).matches()) {
            throw BizException.of(ErrorCode.PERM_ROLE_CODE_INVALID);
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
        List<String> before = rolePointMapper.selectList(Wrappers.<SysRolePoint>lambdaQuery()
                        .eq(SysRolePoint::getRoleCode, roleCode).eq(SysRolePoint::getEndCode, OPS))
                .stream().map(SysRolePoint::getPointCode).toList();
        rolePointMapper.delete(Wrappers.<SysRolePoint>lambdaQuery()
                .eq(SysRolePoint::getRoleCode, roleCode).eq(SysRolePoint::getEndCode, OPS));
        for (String pc : codes) {
            SysRolePoint rp = new SysRolePoint();
            rp.setRoleCode(roleCode);
            rp.setPointCode(pc);
            rp.setEndCode(OPS);
            rolePointMapper.insert(rp);
        }
        /*
         * 判权读的是缓存过的整表快照，不清就要等重启。
         *
         * <p><b>清完就够了，不踢会话</b>（2026-08-13 改）。判权是现算的：
         * 会话里存的是<b>角色</b>，权限码每个请求由 {@code LivePermResolver} 按角色解析。
         * 角色没变，所以下一个请求算出来的就是新配置 —— <b>连重新登录都不需要</b>。
         *
         * <p>此前这里踢了所有持有者，理由写的是「perms 是登录那一刻算好塞进会话的」——
         * 那句话在换成现算之前是对的，之后就过期了。为一次纯配置改动把一屋子人
         * 从工作中间踢出去，换的是一个他们本来就会得到的结果。
         *
         * <p><b>与旁边三个改人的写接口不同</b>（setStaffRole / setStaffRoles / setStaffScope）：
         * 那三个改的是<b>「他是谁」</b> —— 角色与数据域都在会话里，
         * 不重建会话就没有任何机制能让它们变，收紧权限会**永远不生效**。
         * 那三处的 revokeUser 必须留着，而且它就是「让他重新登录一次」本身。
         */
        resolver.invalidate();
        auditLogPort.record("PERM_ROLE_POINTS", roleCode,
                codes.size() + " 个功能点（判权现算，未踢会话）", true,
                objectMapper.writeValueAsString(Map.of("pointCodes", before)),
                objectMapper.writeValueAsString(Map.of("pointCodes", codes)));
        return new RoleVO(roleCode, r.getName(), OPS, false, codes.size(),
                memberMapper.selectCount(Wrappers.<SysRoleMember>lambdaQuery()
                        .eq(SysRoleMember::getEndCode, OPS)
                        .eq(SysRoleMember::getRoleCode, roleCode)).intValue());
    }

    @Override
    @Transactional
    public RoleVO renameRole(String roleCode, String name, String operatorNo) {
        if (name == null || name.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        SysRole r = requireRole(roleCode);
        if (!Boolean.FALSE.equals(r.getBuiltin())) {
            throw BizException.of(ErrorCode.PERM_BUILTIN_ROLE_READONLY, roleCode);
        }
        String before = r.getName();
        r.setName(name.trim());
        roleMapper.updateById(r);
        /*
         * **不清缓存、不踢会话**：改的是展示名，谁能干什么一点没变。
         * 顺手 invalidate 看着更安全，实际是把一次纯展示改动变成全员重新登录。
         */
        auditLogPort.record("PERM_ROLE_RENAME", roleCode, before + " → " + r.getName(), false,
                objectMapper.writeValueAsString(Map.of("name", before)),
                objectMapper.writeValueAsString(Map.of("name", r.getName())));
        return roles(OPS).stream().filter(v -> v.roleCode().equals(roleCode)).findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
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
        auditLogPort.record("PERM_ROLE_DELETE", roleCode, r.getName(), true,
                objectMapper.writeValueAsString(Map.of("name", r.getName(), "roleCode", roleCode)), null);
    }

    @Override
    @Transactional
    public int forceLogoutRole(String roleCode, String operatorNo) {
        // 角色必须存在：对着一个打错的角色码点「强制下线」，返回 0 会被当成「没人在线」
        requireRole(roleCode);
        List<SysRoleMember> members = memberMapper.selectList(Wrappers.<SysRoleMember>lambdaQuery()
                .eq(SysRoleMember::getEndCode, OPS).eq(SysRoleMember::getRoleCode, roleCode));
        int kicked = 0;
        for (SysRoleMember m : members) {
            kicked += tokenStores.of(ai.neargo.shop.auth.Realm.OPERATOR).revokeUser(m.getSubjectNo());
        }
        /*
         * **高危 + 单独记一条**。它打断的是别人正在做的事，
         * 事后要能回答「那天是谁把整个客服组踢下线的」——
         * 混在「改角色功能点」那条审计里就回答不了。
         */
        auditLogPort.record("PERM_ROLE_FORCE_LOGOUT", roleCode,
                "强制 %d 人重新登录，踢掉 %d 个会话".formatted(members.size(), kicked), true);
        return kicked;
    }

    // ---------------------------------------------------------------- 菜单排序

    /**
     * 同级内与相邻项**交换 sort**。
     *
     * <p>交换而不是「重排整段」：只写两行，并发下最坏结果是两次交换互相抵消，
     * 不会把整段顺序搅乱。运营端并发调菜单顺序的概率极低，为它引乐观锁不值。
     *
     * @param siblings 同级全部项，**已按当前 sort 排好**
     * @param idxOf    取某项的 sort
     * @param setOf    写回 sort
     * @return 是否真的换了位（边界上返回 false）
     */
    private static <T> boolean swapAdjacent(List<T> siblings, T self, MoveDirection dir,
                                            java.util.function.ToIntFunction<T> idxOf,
                                            java.util.function.ObjIntConsumer<T> setOf) {
        int i = siblings.indexOf(self);
        int j = dir == MoveDirection.UP ? i - 1 : i + 1;
        if (i < 0 || j < 0 || j >= siblings.size()) {
            // 已经到头了。**不抛错** —— 把「到顶了」做成错误提示，只会让人以为点坏了什么
            return false;
        }
        T other = siblings.get(j);
        int a = idxOf.applyAsInt(self);
        int b = idxOf.applyAsInt(other);
        /*
         * 两项 sort 相同时（老数据、或人为改库）交换等于没换，会表现成「按钮点了没反应」。
         * 用「插到对方另一侧」来兜底：给自己一个必然落在对方另一边的值。
         */
        if (a == b) {
            a = dir == MoveDirection.UP ? b - 1 : b + 1;
        }
        setOf.accept(self, b);
        setOf.accept(other, a);
        return true;
    }

    @Override
    @Transactional
    public void moveFunction(String functionCode, MoveDirection direction, String operatorNo) {
        List<SysFunction> siblings = functionMapper.selectList(Wrappers.<SysFunction>lambdaQuery()
                .eq(SysFunction::getEndCode, OPS).orderByAsc(SysFunction::getSort));
        SysFunction self = siblings.stream().filter(f -> functionCode.equals(f.getFunctionCode()))
                .findFirst().orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        if (!swapAdjacent(siblings, self, direction, SysFunction::getSort, SysFunction::setSort)) {
            return;
        }
        int j = siblings.indexOf(self) + (direction == MoveDirection.UP ? -1 : 1);
        functionMapper.updateById(self);
        functionMapper.updateById(siblings.get(j));
        afterReorder("PERM_FUNCTION_MOVE", functionCode, self.getName(), direction);
    }

    @Override
    @Transactional
    public void movePoint(String pointCode, MoveDirection direction, String operatorNo) {
        SysFunctionPoint me = pointMapper.selectOne(Wrappers.<SysFunctionPoint>lambdaQuery()
                .eq(SysFunctionPoint::getPointCode, pointCode).last("LIMIT 1"));
        if (me == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 同级 = 同一个 function 下、且同为菜单项。ACTION 是页面内按钮，不参与菜单排序
        List<SysFunctionPoint> siblings = pointMapper.selectList(
                Wrappers.<SysFunctionPoint>lambdaQuery()
                        .eq(SysFunctionPoint::getFunctionCode, me.getFunctionCode())
                        .eq(SysFunctionPoint::getPointType, me.getPointType())
                        .orderByAsc(SysFunctionPoint::getSort));
        SysFunctionPoint self = siblings.stream()
                .filter(x -> pointCode.equals(x.getPointCode())).findFirst().orElseThrow();
        if (!swapAdjacent(siblings, self, direction,
                SysFunctionPoint::getSort, SysFunctionPoint::setSort)) {
            return;
        }
        int j = siblings.indexOf(self) + (direction == MoveDirection.UP ? -1 : 1);
        pointMapper.updateById(self);
        pointMapper.updateById(siblings.get(j));
        afterReorder("PERM_POINT_MOVE", pointCode, self.getName(), direction);
    }

    /**
     * 校验「传进来的顺序」与「现有集合」是同一批东西。
     *
     * <p>只比集合不比顺序：顺序正是要改的。多一个、少一个、混进别的父级的一律拒绝 ——
     * 少一个尤其危险：它会让被漏掉的那项 sort 保持原值，混在新序列里排到莫名其妙的位置，
     * 而**界面上看起来只是「顺序有点怪」**，没人会当成 bug。
     */
    private static void requireSameSet(List<String> given, List<String> existing) {
        if (given == null || given.size() != existing.size()
                || !new java.util.HashSet<>(given).equals(new java.util.HashSet<>(existing))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    @Override
    @Transactional
    public void reorderFunctions(List<String> codes, String operatorNo) {
        List<SysFunction> all = functionMapper.selectList(Wrappers.<SysFunction>lambdaQuery()
                .eq(SysFunction::getEndCode, OPS));
        requireSameSet(codes, all.stream().map(SysFunction::getFunctionCode).toList());
        Map<String, SysFunction> byCode = all.stream()
                .collect(Collectors.toMap(SysFunction::getFunctionCode, f -> f));
        for (int i = 0; i < codes.size(); i++) {
            SysFunction f = byCode.get(codes.get(i));
            f.setSort((i + 1) * 10);   // 重写成等差序列：顺序的真源就是这份列表
            functionMapper.updateById(f);
        }
        afterReorderList("PERM_FUNCTION_REORDER", "OPS", codes.size());
    }

    @Override
    @Transactional
    public void reorderPoints(String functionCode, List<String> pointCodes, String operatorNo) {
        // 只重排菜单项：ACTION 是页面内的按钮级授权，它没有"顺序"可言
        List<SysFunctionPoint> all = pointMapper.selectList(Wrappers.<SysFunctionPoint>lambdaQuery()
                .eq(SysFunctionPoint::getFunctionCode, functionCode)
                .eq(SysFunctionPoint::getPointType, "MENU"));
        requireSameSet(pointCodes, all.stream().map(SysFunctionPoint::getPointCode).toList());
        Map<String, SysFunctionPoint> byCode = all.stream()
                .collect(Collectors.toMap(SysFunctionPoint::getPointCode, x -> x));
        for (int i = 0; i < pointCodes.size(); i++) {
            SysFunctionPoint p = byCode.get(pointCodes.get(i));
            p.setSort((i + 1) * 10);
            pointMapper.updateById(p);
        }
        afterReorderList("PERM_POINT_REORDER", functionCode, pointCodes.size());
    }

    /** 与 {@link #afterReorder} 同一条规矩：清缓存，但不踢会话（顺序变了，权限没变）。 */
    private void afterReorderList(String action, String scope, int n) {
        resolver.invalidate();
        auditLogPort.record(action, scope, "重排 %d 项".formatted(n));
    }

    /**
     * 调序之后要做的两件事。
     *
     * <p><b>清缓存但不踢会话</b>：顺序变了，「谁能干什么」一点没变 ——
     * 踢会话会把一次纯展示改动变成全员重新登录（与 renameRole 同一条判断）。
     * 端上下一次拉 /ops/menu（进入页面或切回窗口时）就看到新顺序 ——
     * 运营端已经没有轮询了（2026-08-13），纯展示的改动不值得为它每分钟打一次。
     */
    private void afterReorder(String action, String code, String name, MoveDirection dir) {
        resolver.invalidate();
        auditLogPort.record(action, code, "%s %s".formatted(name, dir == MoveDirection.UP ? "上移" : "下移"));
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
