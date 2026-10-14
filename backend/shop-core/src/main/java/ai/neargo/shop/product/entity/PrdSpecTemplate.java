package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
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

    /**
     * 类目级模板的归属类目；<b>NULL = 按 {@link #categoryType} 兜底</b>。
     *
     * <p>两层的理由：品类只有 3 个而二级类目有 32 个，STANDARD 一个就盖住 18 个 ——
     * 手机数码与鲜花共用「包装：袋装/瓶装/罐装」这种推荐等于没有推荐。
     */
    private String categoryNo;

    /** 规格维度名，如「重量」「香型」。 */
    private String name;

    /** {@code [{code,label}]}：来自平台模板的有 code，商家手输的没有。 */
    private String options;

    /** scope=MERCHANT 时归属的商家。 */
    private String entityNo;

    /** 归档后仍要能恢复。 */
    public static final String ACTIVE = "ACTIVE";
    public static final String DISABLED = "DISABLED";

    /**
     * ACTIVE / DISABLED（V102，P-3.4）。
     *
     * <p>不用 {@code deleted} 逻辑删除承载：模板停用是常态（换季、类目调整），停了还要能恢复；
     * 而真删掉之后，历史商品记下的 {@code templateNo} 就再也解释不了那些 optionCode 是什么意思。
     *
     * <p>商家侧查询同步只取 ACTIVE —— <b>归档了商家还能选，等于没归档</b>。
     */
    private String status;
}
