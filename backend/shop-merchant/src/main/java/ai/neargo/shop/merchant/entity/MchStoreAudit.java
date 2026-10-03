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
    /**
     * 地理覆盖项（ADR-013 阶段三）。
     *
     * <p>与上面两种的差别：那两种审的是<b>单据自己带的 content</b>，
     * 这一种审的是<b>另一张表里的一行</b> —— 所以它必须填 {@link #refNo}，
     * 裁决时按它把 {@code mch_service_area} 置为生效或删掉。
     */
    public static final String SERVICE_AREA = "SERVICE_AREA";

    private String auditNo;
    private String entityNo;

    /**
     * 这条内容是发给哪家店的。
     *
     * <p><b>存量单为空</b>：以前只记商户号，通过时按商户取第一家店写回 ——
     * 多店商家因此会把「南门店今天停电」写到总店去，两边都看不出错。
     * 空值仍按默认店兜底，不猜一个门店号补进去。
     */
    private String storeNo;

    private String kind;

    /** 指向的业务记录（kind=SERVICE_AREA 时是 {@code mch_service_area.area_no}）。NOTICE/BANNER 为空 */
    private String refNo;

    /** 待审内容：公告原文或店招图 URL。 */
    private String content;

    private String status;

    /** JSON 数组：机审命中的敏感词。人审要看到「机器为什么标它」。 */
    private String hits;

    private Long submittedAt;

    /**
     * 提交时选的公告失效时刻（epoch 毫秒）。空 = 长期。kind=NOTICE 才有意义。
     *
     * <p>不存的话，「今日到货」审出来之后会沿用门面表里的旧有效期（多半是长期），
     * 于是一直挂着 —— 而有效期这件事本来就是为了防这个。
     */
    private Long noticeUntil;

    /** 驳回原因。**原样出现在商家 B 端**，所以驳回必须填。 */
    private String reason;

    private Long decidedAt;
    private String decidedBy;
}
