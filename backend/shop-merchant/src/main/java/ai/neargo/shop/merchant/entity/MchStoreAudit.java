package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 店招与公告的人审单。
 *
 * <p><b>只有机审命中的内容才会进这张表</b>：没命中直接生效。
 * 全部先审后发的话，「今日到货」这类时效内容要等几小时，那等于功能没用。
 */
@Getter
@Setter
@TableName("mch_store_audit")
public class MchStoreAudit extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String PASSED = "PASSED";
    public static final String REJECTED = "REJECTED";

    /** 公告文本。一期只审它 —— 店招图要接图片识别，未落地 */
    public static final String NOTICE = "NOTICE";
    public static final String BANNER = "BANNER";

    private String auditNo;
    private String entityNo;
    private String kind;

    /** 待审内容：公告原文或店招图 URL。 */
    private String content;

    private String status;

    /** JSON 数组：机审命中的敏感词。人审要看到「机器为什么标它」。 */
    private String hits;

    private Long submittedAt;

    /** 驳回原因。**原样出现在商家 B 端**，所以驳回必须填。 */
    private String reason;

    private Long decidedAt;
    private String decidedBy;
}
