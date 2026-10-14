package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 平台活动报名单（详细设计 §1.6）：一个商家对一个平台活动的一次报名。
 *
 * <p>两份「最多」在<b>提交时算定</b>：份数 × 每单平台最多补贴 / 每单商家最多承担。
 * 审核通过时拿 {@code platformMaxMinor} 去占平台预算 —— 事后按实际花费算的话，
 * 预算只能在超了之后才知道超了。
 */
@Getter
@Setter
@TableName("pmt_enrollment")
public class PmtEnrollment extends BaseEntity {

    public static final String SUBMITTED = "SUBMITTED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String WITHDRAWN = "WITHDRAWN";

    private String enrollmentNo;
    private String activityNo;
    /** 报名商家。数据域锚点 */
    private String entityNo;
    private Integer quota;
    /** 已用份数。下单时带条件 UPDATE 推进 */
    private Integer quotaUsed;
    private Long platformMaxMinor;
    private Long merchantMaxMinor;
    private String status;
    private String reviewedBy;
    private Long reviewedAt;
    private String rejectReason;
}
