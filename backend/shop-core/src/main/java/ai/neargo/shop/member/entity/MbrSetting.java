package ai.neargo.shop.member.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 这个主体怎么经营会员。
 *
 * <p><b>开关只改展示与分层口径，不改存储</b> —— 主体级与门店级两份指标一直都在算，
 * 所以商家随时可以切、切回来也不丢。界面上必须写这句，否则没人敢点。
 */
@Getter
@Setter
@TableName("mbr_setting")
public class MbrSetting extends BaseEntity {

    /** 按主体：三家店共用一份会员名单。**默认，多数情况** */
    public static final String ENTITY = "ENTITY";
    /** 按门店：各店各算各的。适合门店相距较远 —— 十公里外那家店的会员对这家确实没用 */
    public static final String STORE = "STORE";

    private String entityNo;
    private String memberScope;
    private Integer autoJoinOnOrder;
}
