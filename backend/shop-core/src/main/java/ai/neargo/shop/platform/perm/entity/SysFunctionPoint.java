package ai.neargo.shop.platform.perm.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 功能点（菜单叶子 / 可授权的最小动作）（{@code sys_function_point}）。
 *
 * <p>见 {@code docs/technical/design/权限配置落库-数据库设计与数据清单.md}。
 * 数据由 {@code ops-web/scripts/gen-perm-seed.mjs} 生成 —— 手写的清单三个月后必然过期。
 */
@Getter
@Setter
@TableName("sys_function_point")
public class SysFunctionPoint extends BaseEntity {

    private String pointCode;

    private String functionCode;

    private String name;

    /** 二级分组（「入驻与资质」）。不存的话动态菜单渲染不出这一层 */
    private String groupName;

    private String href;

    /** 前端 UI 码（细粒度） */
    private String uiPermCode;

    /** 后端权限码。<b>null = 不受权限约束（谁都能用）</b> —— 与 NOT_IMPLEMENTED 是两回事 */
    private String permCode;

    /** IMPLEMENTED / NOT_IMPLEMENTED / UNMAPPED */
    private String backendStatus;

    /** 后端通了但前端页面还没做完 */
    private Boolean uiReady;

    /** 需求编号 P-x.y */
    private String matrixCode;

    /**
     * MENU 菜单项 / ACTION 页面内的按钮级授权。
     *
     * <p><b>两类都要收</b>：只收菜单的话，页面内按钮用的那些码
     * （如 {@code industry:manage} 挂在「编辑自提点」按钮上）在库里没有落点，
     * 于是「角色 → 权限码」的集合与硬编码对不上 —— 一致性守卫会红。
     */
    private String pointType;

    /**
     * 界面形态：MENU / INLINE（页面内按钮） / NONE（后端有能力但前端没做页面）。
     *
     * <p><b>不合并成一个布尔</b>：INLINE 与 NONE 都没有 href，但性质相反 ——
     * 前者本来就不该有独立入口，后者是缺口。合成布尔就再也分不出
     * 「本来就不该有」和「还没做」。
     *
     * <p>此前靠 {@code group_name='无界面入口'} 这个中文字符串判断，
     * 改一次文案所有判断一起错，且不报错。
     */
    private String uiKind;

    private Integer sort;

}