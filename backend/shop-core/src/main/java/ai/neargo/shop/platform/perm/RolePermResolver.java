package ai.neargo.shop.platform.perm;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.platform.perm.entity.SysFunctionPoint;
import ai.neargo.shop.platform.perm.entity.SysRolePoint;
import ai.neargo.shop.platform.perm.mapper.PermMappers.FunctionPointMapper;
import ai.neargo.shop.platform.perm.entity.SysRole;
import ai.neargo.shop.platform.perm.mapper.PermMappers.RoleMapper;
import ai.neargo.shop.platform.perm.mapper.PermMappers.RolePointMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 角色 → 后端权限码，**从库读**（`sys_role_point` → `sys_function_point.perm_code`）。
 *
 * <p>这是「配置改了就生效」的最后一环：此前 {@link Perms#of} 是硬编码表，
 * 加一个角色要发一次版。
 *
 * <p><b>回落到 {@link Perms#of}</b> 有两种情况，都不是异常：
 * <ul>
 *   <li>库里查不到这个角色 —— 迁移还没跑、或者这是个代码里新加而库里还没有的角色；</li>
 *   <li>库里查到了但结果为空 —— 视为「没配」，而不是「配了零权限」。
 *       后者会让一个本该有权限的岗位静默失权，而失权在界面上长得像「功能没做」。</li>
 * </ul>
 *
 * <p><b>为什么敢换</b>：一致性守卫（`OpsPermConfigFlowTest`）钉着
 * 「库里的角色→权限码必须与 `Perms.ROLE_PERMS` 逐条相等」。
 * 换数据源之后行为不变是<b>可验证的</b>，而不是靠人读代码确认。
 */
@Component
public class RolePermResolver implements ai.neargo.shop.auth.LivePermResolver {

    /**
     * 整表快照，一次读完。
     *
     * <p>数据量是「角色数 × 功能点数」几百行，且**每个请求判权都要用**
     * （2026-08-12 判权从会话快照改成现算之后）—— 逐次查库等于每次 @PreAuthorize
     * 都打一趟数据库。缓存整表而不是按角色缓存：
     * 按角色缓存要处理「这个角色查过没有」，而整表只有「有没有加载过」。
     *
     * <p><b>通配角色一起缓存</b>：此前 {@code isWildcard} 每个角色查一次 {@code sys_role}，
     * 在「每次登录」的口径下只是多两次往返，改成现算之后就是**每个请求每个角色一次查库**
     * —— 缓存整表却在旁边留一条逐次查询，等于没缓存。
     */
    private record Snapshot(Map<String, Set<String>> byRole, Set<String> wildcardRoles) {
    }

    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    private final RolePointMapper rolePointMapper;
    private final FunctionPointMapper pointMapper;
    private final RoleMapper roleMapper;

    public RolePermResolver(RolePointMapper rolePointMapper, FunctionPointMapper pointMapper,
                            RoleMapper roleMapper) {
        this.rolePointMapper = rolePointMapper;
        this.pointMapper = pointMapper;
        this.roleMapper = roleMapper;
    }

    /** 这组角色合起来有哪些权限码（并集）。 */
    public List<String> of(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        Snapshot snap = snapshot();
        // 通配角色直接短路：它的语义是「全部」，展开成一组具体码会漏掉将来新加的码
        if (roles.stream().anyMatch(snap.wildcardRoles()::contains)) {
            return List.of("*");
        }
        Set<String> out = new LinkedHashSet<>();
        boolean anyFromDb = false;
        for (String r : roles) {
            Set<String> codes = snap.byRole().get(r);
            if (codes != null && !codes.isEmpty()) {
                out.addAll(codes);
                anyFromDb = true;
            }
        }
        // 一个角色都没在库里命中 → 回落，见类注释
        return anyFromDb ? List.copyOf(out) : Perms.of(roles);
    }

    /**
     * {@link LivePermResolver} 实现：判权时现算权限码。
     *
     * <p>与 {@link #of} 同一份实现，只是签名上属于 shop-base 的 SPI。
     * 这里**不返回 null** —— {@code of()} 自带「库里没有就回落 Perms.of」的兜底，
     * 已经比会话快照更新，没有必要再让调用方退回更旧的那一份。
     */
    @Override
    public List<String> resolve(List<String> roles) {
        return of(roles);
    }

    /**
     * 配置变更后清缓存。
     *
     * <p>改角色的功能点、增删角色时调它（{@code PermConfigServiceImpl} 两处写接口都在调）。
     *
     * <p>判权改成现算之后，这一步**就是「实时生效」本身** ——
     * 清完缓存，下一个请求判权拿到的就是新配置，不必等谁重新登录。
     */
    public void invalidate() {
        cache.set(null);
    }

    private Snapshot snapshot() {
        Snapshot cached = cache.get();
        if (cached != null) {
            return cached;
        }
        Set<String> wildcards = roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getEndCode, "OPS"))
                .stream().filter(r -> Boolean.TRUE.equals(r.getWildcard()))
                .map(SysRole::getRoleCode).collect(java.util.stream.Collectors.toSet());
        // point_code → perm_code（null 的点不进：它们是「不受权限约束」或「后端未实现」，
        // 两种都不该变成一个权限码）
        Map<String, String> permOfPoint = new HashMap<>();
        for (SysFunctionPoint p : pointMapper.selectList(Wrappers.emptyWrapper())) {
            if (p.getPermCode() != null && !p.getPermCode().isBlank()) {
                permOfPoint.put(p.getPointCode(), p.getPermCode());
            }
        }
        Map<String, Set<String>> byRole = new HashMap<>();
        for (SysRolePoint rp : rolePointMapper.selectList(Wrappers.<SysRolePoint>lambdaQuery()
                .eq(SysRolePoint::getEndCode, "OPS"))) {
            String perm = permOfPoint.get(rp.getPointCode());
            if (perm != null) {
                byRole.computeIfAbsent(rp.getRoleCode(), k -> new LinkedHashSet<>()).add(perm);
            }
        }
        Snapshot snap = new Snapshot(byRole, wildcards);
        cache.set(snap);
        return snap;
    }
}
