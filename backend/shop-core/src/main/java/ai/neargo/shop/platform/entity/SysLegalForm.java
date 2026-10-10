package ai.neargo.shop.platform.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家主体类型注册表。与 {@link SysIndustry}、{@code sys_pay_channel} 同构：
 * <b>表管能力，类型管取值</b>。
 *
 * <p>它回答的是「选了这个主体，接下来要什么」：要不要营业执照、钱打到个人还是对公、
 * 通道那边叫什么、要不要过行业白名单。这四件事此前散在四处各写一遍。
 */
@Getter
@Setter
@TableName("sys_legal_form")
public class SysLegalForm extends BaseEntity {

    private String legalForm;
    private String name;
    private Integer sort;
    private Boolean enabled;

    /** 对应 shared {@code MerchantSubject} 的旧取值。存量数据还是老取值，映射只此一份。 */
    private String legacySubject;

    /** 通道侧主体码；为空表示该通道不收这种主体。 */
    private String wechatCode;
    private String alipayCode;

    /** 小微不需要营业执照 —— 这正是它存在的意义。 */
    private Boolean needLicense;

    /** PERSONAL_BANK_CARD（打到个人）/ MERCHANT_ID（打到对公）。 */
    private String settleAccountType;

    /** 仅小微为 true：行业白名单只对小微生效，个体户/企业走另一套准入。 */
    private Boolean industryGated;

    private String remark;
}
