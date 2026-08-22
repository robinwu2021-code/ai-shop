package ai.neargo.shop.platform.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 行政区划：省 / 市 / 区县 / 街道四级，国家统计局统计用区划代码口径（ADR-013）。
 *
 * <p><b>用国标码而不是自造</b>：地址、快递单、发票、通道进件全都按国标走。
 * 自造一套迟早要在对外接口上做一次映射，而映射永远有对不上的那天 ——
 * 那时的症状是「这个地址快递下不了单」，没人会想到根因在区划表。
 *
 * <p>数据由 {@code V31__seed_regions.java} 灌入（44703 行），不写在 SQL 迁移里：
 * 参考数据不是 schema，4 万行字面量会让往后每次看 schema diff 都得先翻过它们，
 * 还会被 {@code gen-test-schema.py} 抄进 H2 测试 schema。
 */
@Getter
@Setter
@TableName("sys_region")
public class SysRegion extends BaseEntity {

    /** 统计用区划代码：省 2 位 / 市 4 位 / 区县 6 位 / 街道 9 位 / 村 12 位 */
    private String regionCode;

    /** 上级区划码。**省级为 NULL 而不是空串** —— 两者并存的话「取顶层」要判两次，漏一处就少半棵树 */
    private String parentCode;

    /** PROVINCE / CITY / DISTRICT / STREET / VILLAGE（村委会·居委会，第五级） */
    private String level;

    private String name;

    /**
     * 开城开关：这个区划能不能被选为经营范围。
     *
     * <p><b>停用只影响新的选择，存量商家不动</b> —— 与行业停用同一口径。
     * 反过来做（停用即撤销）会让一次开关操作把一批店的货直接从 C 端抹掉。
     */
    private Boolean enabled;

    private Integer sort;
}
