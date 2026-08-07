package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 规格模板（平台维护 + 商家自存）。B 端录商品时用。
 *
 * <p>选项存 JSON 而不是拆子表：模板的选项是**整体替换**的（改模板就是改一整组），
 * 拆表后每次保存都要 diff 出增删改，没有收益。
 */
@Getter
@Setter
@TableName("prd_spec_template")
public class PrdSpecTemplate extends BaseEntity {

    /** 平台统一维护，商家只能用不能改。 */
    public static final String PLATFORM = "PLATFORM";
    /** 商家自存。 */
    public static final String MERCHANT = "MERCHANT";

    private String templateNo;
    private String scope;

    /** 平台模板按类目推荐；商家模板不限类目。 */
    private String categoryType;

    /** 规格维度名，如「重量」「香型」。 */
    private String name;

    /** {@code [{code,label}]}：来自平台模板的有 code，商家手输的没有。 */
    private String options;

    /** scope=MERCHANT 时归属的商家。 */
    private String merchantNo;
}
