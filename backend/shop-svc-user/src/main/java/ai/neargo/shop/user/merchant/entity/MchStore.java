package ai.neargo.shop.user.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 门店（V44 起由「主体的门面」升级为门店实体，表名 mch_store）。与 {@link MchEntity}（主体）分开：
 * 公告今天写明天改，而主体表被下单链路读 —— 改公告不该去抢那一行的锁。
 *
 * <p>经营范围（{@code serviceScope} / 覆盖社区）刻意<b>留在主体上</b>：
 * 那决定的是「这家店的货谁能看到」（ADR-009），是主体属性而不是门面装修。
 */
@Getter
@Setter
@TableName("mch_store")
public class MchStore extends BaseEntity {

    /** ACTIVE / SUSPENDED / READONLY（Plan 降级：不接新单，但未完成的单照常核销）。 */
    public static final String ACTIVE = "ACTIVE";
    public static final String READONLY = "READONLY";

    private String entityNo;

    /** 门店业务键。 */
    private String storeNo;

    /** 门店名，<b>可与主体名不同</b>（「张记粮油·文三路店」）。 */
    private String name;

    /** 默认门店。一主体<b>恰好一个</b>，删不掉 —— 它是单店商家的全部。 */
    private Boolean isDefault;

    private String status;

    /**
     * 这家店用哪个收款商户号（{@code mch_payment_merchant}）。
     *
     * <p><b>为空 = 用主体的默认商户号</b> —— 单通道时永远为空，行为与今天一致。
     *
     * <p>它是<b>关联而不是归属</b>：门店归属主体，商户号也归属主体，
     * 两者之间可以换。如果门店归属商户号，换收款方就等于换店 ——
     * 评价清零、老客断链，而它本质上只是换了个收钱的口子。
     */
    private String payMerchantNo;

    /** 最近一次切换收款商户号的时间。换商户号会改变钱的去向，要能追。 */
    private Long paymentChangedAt;
    private String announcement;
    private String openHours;
    private String address;

    /** JSON 数组：主推商品 goods_no，<b>有序</b>。顺序是门面的编排，不是商品自身属性。 */
    private String featured;
}
