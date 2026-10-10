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
     * 改角色展示名。
     *
     * <p><b>只改名，不改角色码</b> —— 角色码是授权的键（{@code sys_role_point} /
     * {@code sys_role_member} 都指着它），改了等于换一个角色。
     * 真要换码得走「新建 + 迁移成员 + 删旧」。
     *
     * <p>预置角色拒绝：与改功能点同一条闸，它们是 {@code Perms.java} 的镜像。
     */
    RoleVO renameRole(String roleCode, String name, String operatorNo);

    /**
     * 删除自定义角色。
     *
     * <p>预置角色不可删；<b>还有人在用的角色也不可删</b> ——
     * 删了之后那些人的 perms 会变成空集，他们能登录但什么都点不动，
     * 而界面上看不出是「角色被删了」。
     */
    void deleteRole(String roleCode, String operatorNo);

    /**
     * <b>强制这个角色的全部成员重新登录</b>（紧急撤回）。
     *
     * <p>为什么是一个<b>独立的动作</b>，而不是「改权限」的副作用：
     *
     * <ul>
     *   <li>改角色的功能点<b>本来就立刻生效</b> —— 判权现算，会话里存的是角色，
     *       角色没变，下一个请求算出来就是新配置。为它踢人换不到任何东西，
     *       只是把一屋子人从工作中间打断（正在写的裁决说明、填了一半的表单都没了）。</li>
     *   <li>但<b>跨实例最坏要等一个 TTL</b>（60 秒），而「权限开错了要立刻收回」
     *       这种时候，60 秒是不能接受的。这条路径就是给那一刻准备的。</li>
     * </ul>
     *
     * <p>所以它在界面上是一个<b>二级按钮</b>、要确认、并且单独记审计 ——
     * 紧急动作必须可追溯：事后要能回答「那天是谁把整个客服组踢下线的」。
     *
     * @return 实际踢掉的会话数（一个人可能有多个设备/标签页）
     */
    int forceLogoutRole(String roleCode, String operatorNo);

    /** 上移 / 下移的方向。只在**同级内换位**，不跨组。 */
    enum MoveDirection { UP, DOWN }

    /**
     * 调整菜单分区（L1）的顺序。
     *
     * <p><b>只在同级内与相邻项换位</b>，不允许跨组移动 —— 跨组等于改 function_code /
     * group_name，那是**改菜单结构**，应当走 nav.ts → 生成器 → 迁移这条链路，
     * 而不是在配置页上点两下就改掉。
     *
     * <p>已在边界（首项上移 / 末项下移）时是 no-op，不报错：把「已经到头了」
     * 做成错误提示，只会让人以为自己点坏了什么。
     */
    void moveFunction(String functionCode, MoveDirection direction, String operatorNo);

    /** 调整功能点（菜单叶子 / tab）的顺序。同 {@link #moveFunction}，范围是同一个 function 内。 */
    void movePoint(String pointCode, MoveDirection direction, String operatorNo);

    /**
     * 整段重排菜单分区（拖动用）。
     *
     * <p>传**该父级下的完整顺序**，而不是「把 X 挪到第 N 位」——
     * 后者要服务端反推其余项怎么让位，而客户端本来就知道最终顺序。
     *
     * <p><b>{@code codes} 必须与现有集合完全相同，只是顺序不同</b>：
     * 多一个、少一个、混进别的父级的一律拒绝。前端拦是体验，这里拦才是约束。
     */
    void reorderFunctions(List<String> codes, String operatorNo);

    /** 整段重排某个分区下的菜单项。同 {@link #reorderFunctions}，范围限定在 {@code functionCode} 内。 */
    void reorderPoints(String functionCode, List<String> pointCodes, String operatorNo);

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
