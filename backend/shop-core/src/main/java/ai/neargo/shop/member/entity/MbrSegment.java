package ai.neargo.shop.member.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 人群：一组筛选条件。
 *
 * <p><b>存条件不存名单</b>。名单每天都在变 —— 有人昨天刚下单就不再沉睡；
 * 存快照的话，商家两周后会照着一份过期名单发券，而他不会知道那份名单已经旧了。
 * 要留痕的是「发放那一刻命中了谁」，那属于发放记录。
 *
 * <p>{@link #lastCount} 只是展示（「上次算于 X 时」），发券与触达前一律当场重算。
 */
@Getter
@Setter
@TableName("mbr_segment")
public class MbrSegment extends BaseEntity {

    private String segmentNo;
    private String entityNo;
    private String name;

    /** 限定门店。空 = 全主体 */
    private String scopeStoreNo;

    /** JSON。**只存号**（标签号/门店号）—— 标签改名之后这条件还得成立 */
    private String ruleJson;

    private Integer lastCount;
    private Long countedAt;
}
