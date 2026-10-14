package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家资质。**结构化**存证件类型、编号、有效期。
 *
 * <p>此前资质只以图片 URL 数组的形式留在入驻申请单（{@code mch_entity_apply.qualifications}）上，
 * 审核通过后没有转存到主体。后果是三层的：
 * 运营在商家详情里看不到「这家店有哪些证」；即便翻出申请单也只有图、没有有效期；
 * 而上架校验读的是审核时写死的 {@code category_codes}——**证过期了那串编码不会变**，
 * 商家照样上架、系统照样放行，平台收不到任何信号。
 */
@Getter
@Setter
@TableName("mch_qualification")
public class MchQualification extends BaseEntity {

    public static final String BUSINESS_LICENSE = "BUSINESS_LICENSE";
    public static final String FOOD_PERMIT = "FOOD_PERMIT";
    public static final String FOOD_WORKSHOP = "FOOD_WORKSHOP";
    public static final String OTHER = "OTHER";

    public static final String VALID = "VALID";
    /** 由定时任务置——不是运营手动改的，所以它反映的是「事实」而非「有人记得改」。 */
    public static final String EXPIRED = "EXPIRED";
    public static final String REVOKED = "REVOKED";

    private String qualNo;
    private String entityNo;
    private String qualType;
    private String qualName;
    private String qualNumber;
    private String imageUrl;

    /**
     * 有效期至；<b>空 = 长期有效</b>。
     *
     * <p>空与「已过期」必须分开处理：把「长期有效」当成「没填就是过期」，
     * 会把一批持长期营业执照的正常商家误伤下架。
     */
    private Long expireAt;

    private String status;
}
