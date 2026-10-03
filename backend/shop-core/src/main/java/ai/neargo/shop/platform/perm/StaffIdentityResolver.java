package ai.neargo.shop.platform.perm;

import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.auth.LiveIdentityResolver;
import ai.neargo.shop.platform.entity.SysOpsStaff;
import ai.neargo.shop.platform.mapper.PlatformMappers.StaffMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * {@link LiveIdentityResolver} 的实现：运营账号 → 此刻的角色与数据域，**从库读**。
 *
 * <p>为什么要它、以及回落策略，见接口上的说明。这里只讲缓存：
 *
 * <p><b>整表快照 + TTL</b>，与 {@link RolePermResolver} 同一形状。
 * 运营账号总共几十个，一次读完常驻内存，判权路径上只是一次 map 查找。
 * 按人缓存要处理「这个人查过没有」，整表只有「有没有加载过」——
 * 而这条路径<b>每个请求都要走</b>，少一个分支就少一处能出错的地方。
 *
 * <p>TTL 的作用与 RolePermResolver 一样是<b>兜底</b>：写接口改完主动
 * {@link #invalidate()}（同实例 0 延迟），TTL 只覆盖多实例与「绕过写接口改库」。
 */
@Component
public class StaffIdentityResolver implements LiveIdentityResolver {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(StaffIdentityResolver.class);

    /** 过期这么久还重建不出来就不是「抖一下」了 —— 升级成 error 告警 */
    private static final long STALE_ALERT_MS = 5 * 60_000L;

    private record Snapshot(Map<String, Identity> byStaffNo, long loadedAt) {
    }

    private final AtomicReference<Snapshot> cache = new AtomicReference<>();
    private final StaffMapper staffMapper;
    private final RolePermResolver rolePermResolver;
    private final long ttlMs;

    /** 取当前时间。留一个缝是为了能测过期 —— 让测试等 60 秒不现实 */
    LongSupplier clock = System::currentTimeMillis;

    public StaffIdentityResolver(StaffMapper staffMapper, RolePermResolver rolePermResolver,
                                 @Value("${shop.perm.cache-ttl-ms:60000}") long ttlMs) {
        this.staffMapper = staffMapper;
        this.rolePermResolver = rolePermResolver;
        this.ttlMs = ttlMs;
    }

    @Override
    public Identity resolve(String staffNo) {
        if (staffNo == null || staffNo.isBlank()) {
            return null;
        }
        Snapshot snap = snapshot();
        /*
         * **查不到这个人时返回 null（回落会话快照），而不是「零角色」。**
         *
         * 查不到只有两种可能：他刚被删掉，或者这份快照旧了。
         * 前者由停用/删除时的 revokeUser 兜住（他的会话已经没了，走不到这里）；
         * 后者返回零角色的表现是「这个人的后台突然空了」—— 而原因是缓存旧了，
         * 页面上看不出任何线索。
         */
        return snap == null ? null : snap.byStaffNo().get(staffNo);
    }

    /** 改角色成员、改数据域、增删账号之后调它 —— 与 RolePermResolver.invalidate 成对使用 */
    public void invalidate() {
        cache.set(null);
    }

    private Snapshot snapshot() {
        Snapshot cached = cache.get();
        if (cached != null && clock.getAsLong() - cached.loadedAt() < ttlMs) {
            return cached;
        }
        try {
            Snapshot fresh = load();
            cache.set(fresh);
            return fresh;
        } catch (RuntimeException e) {
            // 重建失败继续用过期的那份 —— 库抖一下不该让全体运营失权，理由见接口注释
            if (cached == null) {
                log.error("[identity] 身份快照一次都没建起来，本次回落会话快照", e);
                return null;
            }
            long age = clock.getAsLong() - cached.loadedAt();
            if (age > STALE_ALERT_MS) {
                log.error("[identity] 身份快照已过期 {} 秒仍无法重建", age / 1000, e);
            } else {
                log.warn("[identity] 身份快照重建失败，继续用 {} 秒前的那份", age / 1000, e);
            }
            return cached;
        }
    }

    private Snapshot load() {
        Map<String, Identity> byStaffNo = staffMapper.selectList(Wrappers.emptyWrapper()).stream()
                .collect(Collectors.toMap(SysOpsStaff::getStaffNo, this::identityOf, (a, b) -> a));
        return new Snapshot(byStaffNo, clock.getAsLong());
    }

    private Identity identityOf(SysOpsStaff staff) {
        List<String> roles = readRoles(staff.getRoles());
        return new Identity(roles, scopeOf(staff, rolePermResolver.of(roles)));
    }

    /**
     * 数据域。**算法只有一份**，在 {@link ai.neargo.shop.platform.StaffScopes}。
     *
     * <p>这里原本抄了一份 {@code OpsServiceImpl.scopeOf}，并留了一句
     * 「必须同一套算法」的注释 —— 而**注释拦不住分岔**：两处各自演进的表现是
     * 「刚登录看得到、过一会儿看不到了」，那种间歇性差异最难查。
     * 会话外置又要加第三个调用方（{@code OperatorIdentityLoader}），
     * 三份靠注释同步就更没指望了，所以抽成了一份。
     */
    private static DataScopeSpec scopeOf(SysOpsStaff staff, List<String> perms) {
        return ai.neargo.shop.platform.StaffScopes.of(
                staff.getMerchantNo(), staff.getCommunityNo(), staff.getPickupNo(), perms);
    }

    /** `["A","B"]` → List。与 OpsServiceImpl 的 readList 同一口径 */
    private static List<String> readRoles(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : json.replace("[", "").replace("]", "").split(",")) {
            String v = part.trim().replace("\"", "");
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }
}
