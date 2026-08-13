package ai.neargo.shop.auth;

import ai.neargo.common.data.scope.DataScopeSpec;

import java.util.List;

/**
 * 「这个运营账号**此刻**是什么角色、能看哪些数据」的解析 SPI。
 * 实现方在 shop-core（读 {@code sys_ops_staff} / {@code sys_role_member}），
 * shop-base 只留接口 —— 与 {@link LivePermResolver} 同一手法。
 *
 * <h2>为什么它必须存在</h2>
 * 判权早就是现算的了（{@link LivePermResolver}），但那只解决了一半：
 * <b>它现算的是「这组角色有哪些权限码」，而「他是哪组角色」仍然来自登录那一刻的会话快照。</b>
 *
 * <p>后果很具体：给某人换个角色、或收窄他的数据域，
 * <b>不重建会话就没有任何机制能让它生效</b> —— 不是「滞后到下次登录」，
 * 是他不主动重登就永远是旧的。而他没有任何理由去重登。
 * 所以那三个写接口（setStaffRole / setStaffRoles / setStaffScope）
 * 一直只能靠 {@code revokeUser} 把人踢下线，把一次调权变成一次打断。
 *
 * <p>把这一半也挪出会话之后，会话里只剩「他是谁」（staffNo）——
 * 一个不会变的东西。于是：
 * <ul>
 *   <li>改角色 / 改数据域<b>立刻生效，且不打断任何人</b>；</li>
 *   <li>踢会话只留给它真正该管的两件事：<b>停用账号</b>（这个人不该再进来）
 *       与<b>改密码</b>（怀疑泄露），以及运营手动点的那个紧急撤回按钮。</li>
 * </ul>
 *
 * <h2>失败时回落，不失权</h2>
 * 解析不到时返回 {@code null}，调用方回落会话快照 ——
 * <b>宁可多用一会儿旧身份，也不要因为解析器没装上而全员失权</b>。
 * 全员失权的表现是「所有人的后台都空了」，看起来像系统坏了，
 * 而没有任何东西指向真正的原因。理由与 {@link LivePermResolver} 完全一致。
 */
public interface LiveIdentityResolver {

    /** 未装配实现时的兜底：一律解析不出，调用方回落会话快照（= 旧行为）。 */
    LiveIdentityResolver NONE = staffNo -> null;

    /**
     * @param staffNo 运营账号业务键（会话里唯一还留着的身份信息）
     * @return 他此刻的角色与数据域；{@code null} 表示无法解析（回落会话快照）
     */
    Identity resolve(String staffNo);

    /**
     * @param roles 角色码。**空集合与 null 的含义不同**：空集合是「他确实一个角色都没有」
     *              （判权全 false），而 null 由 {@link #resolve} 整体返回，表示解析失败
     * @param scope 数据域。为 null 时调用方沿用会话里的那一份
     */
    record Identity(List<String> roles, DataScopeSpec scope) {
    }
}
