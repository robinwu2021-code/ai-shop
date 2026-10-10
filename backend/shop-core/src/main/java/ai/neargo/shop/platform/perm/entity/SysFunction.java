package ai.neargo.shop.platform.perm.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 功能（菜单分区）（{@code sys_function}）。
 *
 * <p>见 {@code docs/technical/design/权限配置落库-数据库设计与数据清单.md}。
 * 数据由 {@code ops-web/scripts/gen-perm-seed.mjs} 生成 —— 手写的清单三个月后必然过期。
 */
@Getter
@Setter
@TableName("sys_function")
public class SysFunction extends BaseEntity {

    /** 如 OPS_MERCHANT */
    private String functionCode;

    private String name;

    /** OPS/BIZ/MP。**进唯一键** —— 三端各有自己的 ORDER 与 FINANCE */
    private String endCode;

    /** 菜单图标 */
    private String icon;

    /** 分区默认落地页 */
    private String href;

    private Integer sort;

    private Boolean enabled;

}