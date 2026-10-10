package ai.neargo.shop.auth;

import java.util.List;

/**
 * 「这组角色**此刻**有哪些权限码」的解析 SPI。实现方在 shop-core
 * （{@code RolePermResolver}，读 {@code sys_role_point}），shop-base 只留接口
 * —— 否则横切的鉴权层就要依赖业务表。与 {@link BizIdentityResolver} 同一手法。
 *
 * <h2>为什么判权不能只看会话快照</h2>
 * {@code LoginUser.perms} 是**登录那一刻**算好塞进会话的。它够用了很久，
 * 因为改权限的写接口都会 {@code revokeUser} 把人踢下线，让他重登时重新取。
 *
 * <p>但动态菜单（{@code GET /ops/menu}）是**每次请求现查库**的。两者不同源，
 * 一旦菜单能在不重登的情况下刷新，就会出现
 * <b>「菜单里出现了，点进去 403」</b> —— 判权还停在旧快照上。
 * 这比看不见那个菜单项更糟：用户以为功能坏了，而不是以为自己没权限。
 *
 * <p>所以判权改成**现算**：会话只存角色（角色变动频率远低于权限配置），
 * 权限码每次由这里解析。实现方带整表快照缓存，改配置时 {@code invalidate()}，
 * 因此代价是一次 map 查找，不是一次查库。
 *
 * <p>解析不到时返回 {@code null}，由 {@link PermChecker} 回落到会话快照
 * —— <b>宁可用旧权限，也不要因为解析器没装上而全员失权</b>。
 * 全员失权的表现是「所有人的后台都空了」，比多留一会儿旧权限坏得多。
 */
@FunctionalInterface
public interface LivePermResolver {

    /** 未装配实现时的兜底：一律解析不出，调用方回落会话快照（= 旧行为）。 */
    LivePermResolver NONE = roles -> null;

    /**
     * @param roles 会话里的角色码
     * @return 这组角色当前的权限码并集；{@code null} 表示无法解析
     */
    List<String> resolve(List<String> roles);
}
