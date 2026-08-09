package ai.neargo.shop.user.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 子账号在某家门店的角色。<b>每店一行</b>。
 *
 * <p>不做成「一个角色 + 一串门店」：那等于假设一个人在所有授权门店里是同一个角色。
 * 而「A 店店长、B 店店员」在夫妻店 + 分店的场景里是常态 —— 假设不成立时，
 * 只能给他建两个子账号，而那意味着切换登录，等于把多门店要解决的问题又还回去了。
 */
@Getter
@Setter
@TableName("mch_store_role")
public class MchStoreRole extends BaseEntity {

    /** 店长：除结算与子账号管理外的经营权限。 */
    public static final String MANAGER = "MANAGER";
    /** 店员：只做订单与核销。 */
    public static final String CLERK = "CLERK";

    private String mchAccountNo;
    private String storeNo;
    private String role;
}
