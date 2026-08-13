package ai.neargo.shop.platform.perm;

import ai.neargo.shop.platform.perm.entity.SysFunctionPoint;
import ai.neargo.shop.platform.perm.entity.SysRole;
import ai.neargo.shop.platform.perm.entity.SysRolePoint;
import ai.neargo.shop.platform.perm.mapper.PermMappers.FunctionPointMapper;
import ai.neargo.shop.platform.perm.mapper.PermMappers.RoleMapper;
import ai.neargo.shop.platform.perm.mapper.PermMappers.RolePointMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 权限快照的缓存行为。**判权每个请求都要读它**，所以这三条都是热路径上的性质。
 *
 * <p>为什么值得单独测：
 * <ul>
 *   <li>TTL 是<b>兜底</b>（多实例的 invalidate 只清本实例；绕过写接口改库时谁也不知道）。
 *       兜底的东西平时不发生，不测就等于没有。</li>
 *   <li>「重建失败继续用旧的」是一条<b>反直觉</b>的规则 —— 下一个人很容易顺手改成
 *       「失败就清空重来」，而那意味着库抖一下全体运营的后台一起变空。</li>
 * </ul>
 */
class RolePermResolverCacheTest {

    /** 每次调用都记一笔，用来数「查了几次库」 */
    private final AtomicInteger loads = new AtomicInteger();
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private RolePermResolver resolver(long ttlMs, RuntimeException failWith) {
        RolePointMapper rolePointMapper = mock(RolePointMapper.class);
        FunctionPointMapper pointMapper = mock(FunctionPointMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);

        when(roleMapper.selectList(any())).thenAnswer(inv -> {
            loads.incrementAndGet();
            if (failWith != null && loads.get() > 1) {
                throw failWith;   // 第一次成功（先有一份快照），之后失败
            }
            return List.of();
        });
        SysFunctionPoint point = new SysFunctionPoint();
        point.setPointCode("OPS_ORDER");
        point.setPermCode("order:order:read");
        when(pointMapper.selectList(any())).thenReturn(List.of(point));
        SysRolePoint rp = new SysRolePoint();
        rp.setRoleCode("BD");
        rp.setPointCode("OPS_ORDER");
        rp.setEndCode("OPS");
        when(rolePointMapper.selectList(any())).thenReturn(List.of(rp));

        RolePermResolver r = new RolePermResolver(rolePointMapper, pointMapper, roleMapper, ttlMs);
        r.clock = now::get;
        return r;
    }

    @Test
    @DisplayName("★★ TTL 之内只查一次库 —— 判权每个请求都走这里，逐次查库等于没缓存")
    void withinTtlItLoadsOnce() {
        RolePermResolver r = resolver(60_000, null);

        for (int i = 0; i < 50; i++) {
            assertThat(r.of(List.of("BD"))).containsExactly("order:order:read");
            now.addAndGet(1_000);   // 走过 50 秒，仍在 TTL 内
        }
        assertThat(loads.get()).as("50 次判权应当只查一次库").isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ TTL 过了会重建 —— 多实例与「绕过写接口改库」全靠这一条兜底")
    void afterTtlItReloads() {
        RolePermResolver r = resolver(60_000, null);
        r.of(List.of("BD"));
        assertThat(loads.get()).isEqualTo(1);

        now.addAndGet(60_001);
        r.of(List.of("BD"));
        assertThat(loads.get())
                .as("过期之后必须重新读库：另一个实例上的 invalidate 传不过来，"
                        + "直接改库更是没人通知得到 —— 没有这条，旧配置会一直用到重启")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("★★★ 重建失败继续用过期的那份 —— 库抖一下不该让全体运营一起失权")
    void reloadFailureKeepsStale() {
        RolePermResolver r = resolver(60_000, new IllegalStateException("db down"));
        assertThat(r.of(List.of("BD"))).containsExactly("order:order:read");

        now.addAndGet(60_001);
        assertThat(r.of(List.of("BD")))
                .as("重建失败时清空缓存的后果是「整个运营端一起变空」——"
                        + "那看起来像系统坏了，而真正的原因（一次查询失败）没有任何东西指向它。"
                        + "多用一会儿旧权限的代价小得多")
                .containsExactly("order:order:read");
    }

    @Test
    @DisplayName("★★ 一次都没成功过就要抛错 —— 没有旧的可用时，静默返回空集是最坏的失败方式")
    void firstLoadFailurePropagates() {
        RolePointMapper rolePointMapper = mock(RolePointMapper.class);
        FunctionPointMapper pointMapper = mock(FunctionPointMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        when(roleMapper.selectList(any())).thenThrow(new IllegalStateException("db down"));

        RolePermResolver r = new RolePermResolver(rolePointMapper, pointMapper, roleMapper, 60_000);
        /*
         * 返回空集的话，判权全 false —— 与「这个人确实没有权限」一模一样，
         * 而运营看到的是「我的后台空了」。抛出去至少能在日志里指向数据库。
         */
        assertThatThrownBy(() -> r.of(List.of("BD"))).isInstanceOf(IllegalStateException.class);
    }
}
