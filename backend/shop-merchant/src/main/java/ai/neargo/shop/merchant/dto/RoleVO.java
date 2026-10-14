package ai.neargo.shop.merchant.dto;

import java.util.List;

/**
 * 一个角色：能做什么、几个人在用（V71）。
 *
 * @param roleCode   角色码。预置的是 {@code MANAGER} 这类；自定义的是 {@code R…}
 * @param name       显示名。预置角色也有 —— 端上不该拿 `MANAGER` 直接显示给店主
 * @param builtin    平台预置。**只读**：改要走「复制为自定义角色」
 * @param perms      权限码
 * @param permLabels 与 {@code perms} 一一对应的中文短说明。
 *                   <b>由后端给</b>，前端不抄一份 —— 抄的那份迟早与权限码本身漂开，
 *                   而漂开的表现是「界面写着能改库存，实际打不通」
 * @param usedBy     现在有几个人持有这个角色。删除按钮据此禁用，
 *                   并且要显示出来 —— 「删不掉」而不说为什么，比不给删更难受
 */
public record RoleVO(String roleCode, String name, boolean builtin,
                     List<String> perms, List<String> permLabels, int usedBy) {
}
