package ai.neargo.shop.member.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 会员在某一家门店的往来。
 *
 * <p><b>单店主体不写这张表</b> —— 那一行等于主表的复制。只有多店主体才有，
 * 读的时候取不到就回落主表。
 *
 * <p>它里面**没有任何身份字段**（没有状态、来源、标签、退订）：那些只在
 * {@link MbrMember} 上。所以不会出现「两边身份不一致」这种问题 ——
 * 这也是它不算「第二张会员表」的原因。
 */
@Getter
@Setter
@TableName("mbr_member_store")
public class MbrMemberStore extends BaseEntity {

    private String memberNo;
    private String entityNo;
    private String storeNo;
    private Long firstOrderAt;
    private Long lastOrderAt;
    private Integer orderCount;
    private Long totalSpentMinor;
    private Integer d90OrderCount;
    private Long d90SpentMinor;

    /**
     * 这家店自己的分层。
     *
     * <p>只有主体开了「按门店经营会员」时才展示它 —— 那时「新客」的含义是
     * <b>对这家店第一次买</b>（在别的店买过也算），而这正是十公里外那家店要的判断。
     */
    private String level;

    private Integer isFirstStore;
}
