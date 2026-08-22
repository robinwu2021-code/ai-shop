package ai.neargo.shop.community.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家提报的新社区（ADR-013 阶段三）。
 *
 * <p>商家开在一个平台还没开的小区里，此前<b>无路可走</b>：覆盖项只能从已有社区里勾，
 * 而「让平台加一个小区」没有任何入口 —— 只能找 BD 口头说，说完没人知道进展。
 *
 * <p><b>为什么不直接在 {@code cmt_community} 里建一行待审的</b>：待审的社区一旦进主表，
 * 每一个读社区的地方都要记得过滤它（C 端选点、B 端勾选、按区展开、自提点归属），
 * 漏一处就会有一个还没批的小区出现在用户的选点列表里，而点进去什么都没有。
 * 通过时才建社区行，这里回填 {@link #communityNo} 指过去。
 */
@Getter
@Setter
@TableName("cmt_community_apply")
public class CmtCommunityApply extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private String applyNo;

    /** 提报的商家。驳回理由要回给他，通过了也要让他知道 */
    private String entityNo;

    private String name;

    /** 运营靠它判断这是不是已有社区的另一个叫法 —— 同一个小区两条记录，商家会分不清该勾哪个 */
    private String address;

    /** 商家选的区划，<b>只是建议</b>：最终以运营裁决时填的为准 */
    private String regionCode;

    /** 提的是小区(ESTATE)还是村(VILLAGE)。裁决通过时原样带进聚落 */
    private String kind;

    /** 提报村时从词典选中的官方村码；自由输入则空 */
    private String originCode;

    /** 商家提报时的定位。他正站在那儿 —— 运营在办公室补不出坐标 */
    private Integer latE6;
    private Integer lngE6;

    /** 商家的补充说明：为什么要开这个点 */
    private String note;

    private String status;

    /** 通过后建出来的社区号。回填而不是提前占号 —— 驳回的提报不该消耗社区号 */
    private String communityNo;

    /** 驳回原因。<b>原样出现在商家 B 端</b>，所以驳回必须填 */
    private String reason;

    private Long submittedAt;
    private Long decidedAt;
    private String decidedBy;
}
