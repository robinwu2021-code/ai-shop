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

    record RoleVO(String roleCode, String name, String endCode, boolean builtin, int pointCount) {
    }
}
