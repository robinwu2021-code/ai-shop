package ai.neargo.shop.platform.perm;

import java.util.List;

/**
 * 权限配置的读取（运营端先行）。
 *
 * <p><b>这一层只负责「展示」，不负责「拦截」</b> ——
 * 判权仍在 {@code @PreAuthorize} + {@code Perms.can()}，签名与行为一行没改。
 * 菜单是展示、判权是拦截，两者读同一份数据但用途不同；
 * 把它们合成一件事的话，「菜单上看不到」就会被当成「后端会拦」，而那是两回事。
 */
public interface PermConfigService {

    /**
     * 当前登录人的**动态菜单**。
     *
     * <p>按 {@code sys_role_member → sys_role_point → sys_function_point} 三跳算出，
     * 而不是读前端写死的 nav。
     *
     * <p><b>后端未实现的功能点照样返回</b>，带 {@code backendStatus = NOT_IMPLEMENTED} ——
     * 端上灰显 + 「待建」角标、不可点。藏起来的话运营不知道平台规划了这个功能；
     * 而让它可点就是死按钮。<b>渲染但禁用是第三条路</b>。
     */
    List<MenuFunctionVO> menu();

    /** 功能矩阵（全量，含每个功能点的后端状态）。平台端配置页用。 */
    List<MenuFunctionVO> functions(String endCode);

    /** 角色清单。 */
    List<RoleVO> roles(String endCode);

    /** 某个角色被授予的功能点码。 */
    List<String> rolePoints(String roleCode);

    // ---------------------------------------------------------------- 写侧

    /**
     * 新建自定义角色。
     *
     * <p><b>换源之后自定义角色才真的有意义</b>：判权已改成读
     * {@code sys_role_point}，所以新建一个角色、勾上功能点，
     * 那个人登录后就真的拿到对应权限 —— 在这之前它只能改菜单，
     * 而菜单能看、接口 403 是最坏的一种。
     *
     * <p><b>不能建通配角色</b>：{@code wildcard} 一律为 0 ——
     * 否则任何有 staff:manage 的人都能造一个超管出来。
     */
    RoleVO createRole(String roleCode, String name, String operatorNo);

    /**
     * 设置角色的功能点（整体替换）。
     *
     * <p><b>预置角色（builtin）拒绝修改</b>：它们是 {@code Perms.java} 的镜像，
     * 改了会与回落表分叉 —— 而回落什么时候被触发不由我们决定。
     * 要调整预置角色请改代码并重跑生成器。
     */
    RoleVO setRolePoints(String roleCode, List<String> pointCodes, String operatorNo);

    /**
     * 删除自定义角色。
     *
     * <p>预置角色不可删；<b>还有人在用的角色也不可删</b> ——
     * 删了之后那些人的 perms 会变成空集，他们能登录但什么都点不动，
     * 而界面上看不出是「角色被删了」。
     */
    void deleteRole(String roleCode, String operatorNo);

    record MenuFunctionVO(String functionCode, String name, String icon, String href,
                          int sort, List<MenuPointVO> points) {
    }

    /**
     * @param backendStatus IMPLEMENTED / NOT_IMPLEMENTED / UNMAPPED
     * @param permCode      null = 不受权限约束（谁都能用）——<b>与 NOT_IMPLEMENTED 是两回事</b>
     */
    record MenuPointVO(String pointCode, String name, String groupName, String href,
                       String uiPermCode, String permCode, String backendStatus,
                       boolean uiReady, String matrixCode, String pointType, int sort) {
    }

    /**
     * @param staffCount 持有该角色的账号数。
     *     <p><b>它是删角色前唯一能看出「会影响谁」的信息</b> ——
     *     {@code deleteRole} 已经拦住了在用的角色（{@code PERM_ROLE_IN_USE}），
     *     但那是拦在点下去之后。列表上就显示出来，才不会让人白点一次。
     */
    record RoleVO(String roleCode, String name, String endCode, boolean builtin, int pointCount,
                  int staffCount) {
    }
}
