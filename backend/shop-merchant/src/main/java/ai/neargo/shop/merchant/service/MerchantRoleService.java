package ai.neargo.shop.merchant.service;

import ai.neargo.shop.merchant.dto.RoleVO;

import java.util.List;

/**
 * 商家角色（V71）：6 个平台预置 + 商家自定义。
 *
 * <p><b>这推翻了一条写下来的决定</b>（需求 §十一「不让商家自定义权限码」）。
 * 放开的理由是六个预置角色装不下所有小店的分工（夜班店长、只管收银的、只对账的会计）；
 * <b>放开的是角色，不是边界</b> —— 见 {@link #create} 的三条校验。
 */
public interface MerchantRoleService {

    /**
     * 本主体可用的全部角色：预置（只读）+ 自定义，各带权限码、中文说明与「几个人在用」。
     *
     * <p>「几个人在用」是删除按钮的依据，也是老板改权限前该看到的东西 ——
     * 改一个角色的权限，<b>所有持有者同时变</b>。
     */
    List<RoleVO> list(String merchantNo);

    /**
     * 建一个自定义角色。三条校验，每条都对应一种绕过：
     *
     * <ol>
     *   <li><b>不得包含 {@code biz:store:admin}</b> —— 那是「管人」的码，
     *       授出去等于让被授权的人能改所有人的授权、给自己加任何角色</li>
     *   <li><b>权限码必须真的存在</b> —— 手滑写错的码存进去不会报错，
     *       只会让那个角色少一样能力，而没人看得出来</li>
     *   <li><b>角色名与预置码不能撞</b> —— 同名不同义是本项目反复在治的病</li>
     * </ol>
     */
    RoleVO create(String merchantNo, String name, List<String> perms);

    /** 改名 / 改权限码。**预置角色拒**：要改就先复制一份 */
    RoleVO update(String merchantNo, String roleCode, String name, List<String> perms);

    /**
     * 删除自定义角色。
     *
     * <p><b>还有人持有时拒绝</b> —— 删掉的后果是那些人的权限凭空消失，
     * 而他们只会看到「昨天还能做的事今天点不动了」，没有任何解释。
     * 要删就先把人从这个角色上撤下来。
     */
    void delete(String merchantNo, String roleCode);
}
