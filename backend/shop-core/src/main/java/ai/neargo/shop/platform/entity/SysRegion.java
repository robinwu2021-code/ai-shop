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

    /**
     * 数据来源：{@code OFFICIAL}（官方）/ {@code MERCHANT}（商家补录）。
     *
     * <p><b>定期更新只能动 OFFICIAL 那批</b> —— 把商家录的当过期数据清掉，
     * 是把他自己填的东西删了，而他不会收到任何通知。
     */
    private String source;

    /**
     * 谁提报的。<b>永久保留</b> —— 不兼作可见性开关。
     *
     * <p>V182 曾让它兼任两职（通过即置 NULL），于是<b>通过之后再也查不出是谁报的</b>：
     * 某个村名写错了要追源头，追不到。可见性改由 {@link #auditStatus} 单独表示。
     */
    private String ownerEntityNo;

    /**
     * {@code PENDING}（待运营确认）/ {@code APPROVED}（全网可见）/ {@code REJECTED}（已驳回）。
     *
     * <p><b>可见性只看这一个字段</b>：APPROVED 全网可见；PENDING 与 REJECTED
     * 只对提报的那家店可见。官方数据恒为 APPROVED。
     *
     * <p>REJECTED 也要让提报方看得见（连同 {@link #rejectReason}）——
     * 驳回即删行的话，商家那边那个村凭空消失，而他不知道为什么，多半原样再录一遍。
     */
    private String auditStatus;

    /** 驳回理由，原样回给商家 —— 不写的话他只会原样再提一次 */
    private String rejectReason;

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String SOURCE_MERCHANT = "MERCHANT";

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

    /**
     * 中心点（gcj02，E6，V192）。**这张表原本没有坐标** —— 于是「把区域名换成坐标」
     * 只能在端上实时搜、内存缓存，重启即失；村级聚落没坐标时 withinRadius 恒 false。
     * 由高德批量补录（先跑运城与深圳），命中不了的留空，靠商家提报时纠正。
     */
    private Integer latE6;
    private Integer lngE6;

    /** AMAP 批量补录 / MERCHANT 商家纠正 / OPS 运营录入。重跑批量时据此决定要不要覆盖 */
    private String coordsSource;
    private java.time.LocalDateTime coordsAt;
}
