package ai.neargo.shop.member.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 会员：一个人（{@code personNo}）与一家主体（{@code entityNo}）的关系。
 *
 * <p><b>挂主体不挂门店</b>：同一个人在总店买米、南门店买油是同一个人，标签也该是同一份。
 * 门店维度在 {@link MbrMemberStore} 里 —— 多店商家真的会问「南门店有多少熟客」，
 * 尤其两家店隔着十公里的时候。
 *
 * <p><b>不存手机号、不存 userNo</b>：那两样都从人档取。散在各商家表里的手机号，
 * 是最容易出事的那种数据。
 *
 * <p>几个指标（单数、消费额、近 90 天）都是**订单的派生缓存** ——
 * 唯一真源是 {@code ord_sub_order}，夜里全量重算兜底，对不上以订单为准。
 */
@Getter
@Setter
@TableName("mbr_member")
public class MbrMember extends BaseEntity {

    /** 商家录进来、本人还没在平台出现过。**不可触达、不进任何受众** */
    public static final String LEAD = "LEAD";
    public static final String ACTIVE = "ACTIVE";
    /** 商家拉黑。仍在名单里（他的历史成交是事实），但不再出现在可发放的人群里 */
    public static final String BLOCKED = "BLOCKED";

    public static final String SOURCE_ORDER = "ORDER";
    public static final String SOURCE_SHARE = "SHARE";
    public static final String SOURCE_SCAN = "SCAN";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_FAVORITE = "FAVORITE";
    public static final String SOURCE_SEARCH = "SEARCH";

    public static final String LEVEL_NEW = "NEW";
    public static final String LEVEL_REGULAR = "REGULAR";
    public static final String LEVEL_LOYAL = "LOYAL";
    public static final String LEVEL_SLEEPING = "SLEEPING";

    private String memberNo;
    private String entityNo;
    private String personNo;
    private String status;

    /** 首次来源。<b>首次即定</b>，后续每一次来源都在 {@link MbrMemberSource} 里各留一行 */
    private String source;

    private String firstStoreNo;
    private Long firstOrderAt;
    private Long lastOrderAt;
    private Integer orderCount;
    private Long totalSpentMinor;
    private Integer d90OrderCount;
    private Long d90SpentMinor;
    private String level;

    /** 买家在店铺页关掉了这家店的消息。商家看得到状态，看不到原因 */
    private Integer reachOptOut;

    private String remark;
    private Long joinedAt;
    private Long claimedAt;

    /** 能不能给他发消息。线索、拉黑、已退订三种都不行 */
    public boolean reachable() {
        return ACTIVE.equals(status) && (reachOptOut == null || reachOptOut == 0);
    }
}
