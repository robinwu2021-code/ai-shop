package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.Getter;
import lombok.Setter;

/**
 * 主体按平台类目设置记不记库存（V345，TDD-商品纳入进销存开关 §3）。
 *
 * <p>门店交易库起放在 {@code prd_store_category} 的「主体级行」上（批次 2-g，原 {@code prd_entity_category_inv}）：
 * 门店号与目录号存空串、{@code platform_category_no} 是平台类目、只用 {@code inv_managed}。
 * 同一张表里门店号非空的是商家的门店展示分类，不是这里的开关；读写一律从
 * {@code EntityCategoryInvMapper.entityLevel()} 起手。
 *
 * <p>{@code category_no}（全表唯一）用 {@link #keyOf} 按「主体 + 平台类目」算出来：同一个开关永远落同一行，
 * 两个人同时首次设置时撞唯一键而不是各插一行。
 *
 * <p><b>稀疏</b>：没有行 = 用平台默认（实物 / 生鲜记，服务 / 券 / 虚拟不记）。<b>行只改不删。</b>
 */
@Getter
@Setter
@TableName("prd_store_category")
public class PrdEntityCategoryInv extends BaseEntity {
    /** 主体级行的门店号与目录号 */
    public static final String ENTITY_LEVEL = "";

    private String entityNo;

    /** 新表的 tenant_no 没有默认值：不填就是 NULL，按租户查的地方（门店换绑检查）会漏掉这一行 */
    public PrdEntityCategoryInv() {
        setTenantNo("MAIN");
    }
    private String storeNo = ENTITY_LEVEL;
    private String catalogNo = ENTITY_LEVEL;
    /** 这一行的编号，由 {@link #keyOf} 算出 */
    @TableField("category_no")
    private String rowNo;
    /** 平台类目号 */
    @TableField("platform_category_no")
    private String categoryNo;
    /** 1 记 / 0 不记 */
    @TableField("inv_managed")
    private Integer managed;

    /** 「主体 + 平台类目」→ 32 位以内的行编号 */
    public static String keyOf(String entityNo, String categoryNo) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((entityNo + "|" + categoryNo).getBytes(StandardCharsets.UTF_8));
            return "INV" + HexFormat.of().formatHex(d).substring(0, 29);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
