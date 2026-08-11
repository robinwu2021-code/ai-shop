package ai.neargo.shop.merchant.entity;

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

    /**
     * 自营：平台是销售主体。没有 EDI 时只能自营，故为默认值。
     *
     * <p>取值域的**唯一定义处**在 {@code MerchantQueryPort}——跨域调用方
     * （如 settle 生成结算单时）不能依赖商家域，所以放在契约上。这里只是转引用，
     * 不重新写一份字面量：两处各写一份，改一处就会静默分岔。
     */
    public static final String SELF_OPERATED = ai.neargo.shop.spi.user.MerchantQueryPort.MODE_SELF_OPERATED;
    /** 第三方：商家是销售主体，平台收佣金。需要 EDI 与收付通。 */
    public static final String THIRD_PARTY = ai.neargo.shop.spi.user.MerchantQueryPort.MODE_THIRD_PARTY;

    /** ACTIVE / SUSPENDED / READONLY（Plan 降级：不接新单，但未完成的单照常核销）。 */
    public static final String ACTIVE = "ACTIVE";
    public static final String READONLY = "READONLY";

    private String entityNo;

    /**
     * 经营模式：{@link #SELF_OPERATED} / {@link #THIRD_PARTY}。
     *
     * <p>挂在门店而不是主体上：同一主体下旗舰店做自营、加盟店做第三方是常见形态，
     * 而选择依据（品类、供货方资质、履约控制）本来就是按店不同的。
     *
     * <p><b>与「分账时机」是两个正交的轴</b>，不要合并成一个枚举——
     * 合并之后「自营 + 直连分账」这种非法组合在类型上就是可表达的。
     */
    private String businessMode;

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

    /**
     * 配送半径（米）。默认 3km。
     *
     * <p>挂门店不挂主体：半径是从**这家店门口**量出去的。挂主体的话，
     * 第二家店一开，两边的覆盖范围就都是错的 —— 而错法是「明明送得到却下不了单」，
     * 用户不会来反馈，只会换一家。
     */
    private Integer deliveryRadiusM;

    /** 起送价（分）。0 = 不设门槛。 */
    private Long deliveryMinOrderMinor;

    /** 配送费（分）。0 = 免费送。 */
    private Long deliveryFeeMinor;

    /** 免配送费门槛（分）。0 = 不免。 */
    private Long deliveryFreeThresholdMinor;
}
