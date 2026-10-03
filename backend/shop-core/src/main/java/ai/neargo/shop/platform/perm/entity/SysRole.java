package ai.neargo.shop.platform.perm.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 角色（{@code sys_role}）。
 *
 * <p>见 {@code docs/technical/design/权限配置落库-数据库设计与数据清单.md}。
 * 数据由 {@code ops-web/scripts/gen-perm-seed.mjs} 生成 —— 手写的清单三个月后必然过期。
 */
@Getter
@Setter
@TableName("sys_role")
public class SysRole extends BaseEntity {

    private String roleCode;

    private String name;

    private String endCode;

    /** 平台预置，不可删 */
    private Boolean builtin;

    /**
     * 通配角色（超管）：拥有全部权限码。
     *
     * <p><b>库里没有 {@code *} 这个「码」</b> —— 超管靠「被授予全部功能点」
     * 表达可见性，但那展开出来是一组具体码，{@code contains("*")} 永远为假。
     * 判权要的是「他有没有全部权限」这个事实本身，所以在角色上显式标出来。
     */
    private Boolean wildcard;

    /** 非空 = 某商家自定义的角色 */
    private String entityNo;

    private Integer sort;

}