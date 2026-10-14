package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家角色：6 个平台预置（只读）+ 商家自定义（V71）。
 *
 * <h2>预置角色为什么是全局一份</h2>
 * {@code entity_no = '*'}，所有商家共用。给每个商家复制一份的话，
 * <b>新增一个权限码时要回头刷全部商家的预置角色</b> —— 刷漏一个，
 * 那家店的店长就少一样能力，而且不报错。
 *
 * <p>全局一份 + 只读，语义永远与 {@link ai.neargo.shop.auth.BizPerms} 一致；
 * 商家要改就「复制为自定义角色」，那是显式动作，改的也是他自己的副本。
 *
 * <h2>⚠️ 自定义角色的硬边界</h2>
 * <b>{@code biz:store:admin} 不得出现在自定义角色里。</b>
 * 那是「管人」的码 —— 授出去等于让被授权的人能改所有人的授权、能给自己加任何角色，
 * <b>一次授权就绕开了整个模型</b>。校验在 {@code MerchantRoleServiceImpl}，
 * 测试在 {@code MerchantRoleFlowTest}。
 *
 * <p>{@code biz:finance} 则**允许**授出：「让会计看账」是真实诉求，
 * 且它只看不改结构。店长默认仍然没有 —— 老板要给，得显式建一个带它的角色。
 */
@Getter
@Setter
@TableName("mch_role")
public class MchRole extends BaseEntity {

    /** 预置角色的 owner：全局共享，不属于任何一个商家 */
    public static final String BUILTIN_ENTITY = "*";

    /**
     * 自定义角色**不能**包含的权限码。
     *
     * <p>只有一个，而它就是全部理由：管人的权限一旦能被授出去，
     * 「谁能给谁授权」这条链就没有底了。
     */
    public static final String FORBIDDEN_IN_CUSTOM = "biz:store:admin";

    private String entityNo;
    private String roleCode;
    private String name;

    /** JSON 数组文本。读写走 {@code MerchantRoleServiceImpl} 的两个转换方法，不在各处手写解析 */
    private String perms;

    private Boolean builtin;
}
