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

    /** 非空 = 某商家自定义的角色 */
    private String entityNo;

    private Integer sort;

}