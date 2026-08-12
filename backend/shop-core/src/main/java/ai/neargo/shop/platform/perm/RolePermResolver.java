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
public class RolePermResolver {

    /**
     * 整表快照，一次读完。
     *
     * <p>数据量是「角色数 × 功能点数」几百行，且**每次登录都要用** ——
     * 逐次查库会让登录多两次往返。缓存整表而不是按角色缓存：
     * 按角色缓存要处理「这个角色查过没有」，而整表只有「有没有加载过」。
     */
    private final AtomicReference<Map<String, Set<String>>> cache = new AtomicReference<>();

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
        // 通配角色直接短路：它的语义是「全部」，展开成一组具体码会漏掉将来新加的码
        if (roles.stream().anyMatch(this::isWildcard)) {
            return List.of("*");
        }
        Map<String, Set<String>> map = snapshot();
        Set<String> out = new LinkedHashSet<>();
        boolean anyFromDb = false;
        for (String r : roles) {
            Set<String> codes = map.get(r);
            if (codes != null && !codes.isEmpty()) {
                out.addAll(codes);
                anyFromDb = true;
            }
        }
        // 一个角色都没在库里命中 → 回落，见类注释
        return anyFromDb ? List.copyOf(out) : Perms.of(roles);
    }

    /**
     * 配置变更后清缓存。
     *
     * <p>改角色的功能点、增删角色时调它。**目前没有写接口**，
     * 所以它只在测试与将来的配置页用得上 —— 先留出口，
     * 免得那天临时去想「缓存怎么失效」。
     */
    public void invalidate() {
        cache.set(null);
    }

    private boolean isWildcard(String role) {
        SysRole r = roleMapper.selectOne(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getRoleCode, role).eq(SysRole::getEndCode, "OPS").last("LIMIT 1"));
        return r != null && Boolean.TRUE.equals(r.getWildcard());
    }

    private Map<String, Set<String>> snapshot() {
        Map<String, Set<String>> cached = cache.get();
        if (cached != null) {
            return cached;
        }
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
        cache.set(byRole);
        return byRole;
    }
}
