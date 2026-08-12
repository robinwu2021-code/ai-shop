package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 子账号在某家门店的角色。<b>一人一店可多行</b>（V18 起）。
 *
 * <p>不做成「一个角色 + 一串门店」：那等于假设一个人在所有授权门店里是同一个角色。
 * 而「A 店店长、B 店店员」在夫妻店 + 分店的场景里是常态 —— 假设不成立时，
 * 只能给他建两个子账号，而那意味着切换登录，等于把多门店要解决的问题又还回去了。
 *
 * <p><b>V18 起一人一店可持有多个角色</b>，权限取并集。小店最常见的那种人是
 * 「站收银台的顺手把货送了」（店员 + 配送员）—— 一店一角色放不下他，
 * 硬要一人一角色，结果只会是「都给店长」，那等于没分。
 */
@Getter
@Setter
@TableName("mch_store_role")
public class MchStoreRole extends BaseEntity {

    /** 店长：除结算与子账号管理外的经营权限。 */
    public static final String MANAGER = "MANAGER";
    /** 店员：站收银台 —— 核销、到货分拣、发货、改库存。不碰价格与售后 */
    public static final String CLERK = "CLERK";
    /** 理货员：只到货、分拣、报短少。**不核销**（那要面对顾客）、不看金额 */
    public static final String PICKER = "PICKER";
    /**
     * 配送员：只自送。看不到金额与核销码 —— 他拿的是
     * {@code CourierOrderVO}（单号 / 状态 / 履约方式 / 件数 / 下单时间）。
     *
     * <p>⚠️ <b>地址还没有</b>：需求要的是「待自送的单 + 地址」，
     * 而收货地址在 B 端从来没下发过（子单上只有 {@code address_id}），
     * <b>所有角色都看不到</b>。这一条是能力缺口，不是权限收窄。
     */
    public static final String COURIER = "COURIER";
    /**
     * 线上客服：回评价、处理售后、答咨询。不碰货、不碰钱。
     *
     * <p>⚠️ 与运营端的 {@code Role.CS} <b>同名不同义</b>：这个是商家自己雇的客服
     * （只管自己店），那个是平台客服（跨商家、能仲裁）。两端词表本就独立，
     * 但同名的东西迟早被人当成一回事 —— 所以在这里点名。
     */
    public static final String CS = "CS";

    /** 全部可授予的门店角色。**OWNER 不在其中** —— 他不需要逐店授权 */
    public static final java.util.Set<String> GRANTABLE =
            java.util.Set.of(MANAGER, CLERK, PICKER, COURIER, CS);

    private String mchAccountNo;
    private String storeNo;
    private String role;
}
