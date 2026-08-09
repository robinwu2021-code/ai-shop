package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家子账号：账号 ↔ 主体的成员关系。
 *
 * <p><b>这是 B 端身份的来源</b>，取代了 {@code mch_entity.owner_user_no}。
 * 一个 {@code user_no} 有多行就是「一个账号参与多个主体」——
 * 而此前 {@code BizIdentityResolver} 的 {@code limit 1} 让第二个主体永远查不出来，
 * 且不报错。
 *
 * <p>角色不在这里：同一个人可以在 A 店是店长、B 店是店员，见 {@link MchStoreRole}。
 */
@Getter
@Setter
@TableName("mch_account")
public class MchAccount extends BaseEntity {

    public static final String ACTIVE = "ACTIVE";
    public static final String DISABLED = "DISABLED";

    private String mchAccountNo;
    private String entityNo;

    /**
     * <b>可选</b>关联的 C 端账号，小程序免登用。
     *
     * <p>V46 起可空：员工身份不该依赖 C 端账号存在。
     * 早先强制绑定的理由「店员多半已是 C 端用户」只在小程序里成立 ——
     * 在 App 上，<b>要求店员先注册成消费者才能上班，是把雇佣关系硬塞进消费关系里</b>。
     */
    private String userNo;

    /**
     * 员工自己的登录手机号（App 走这条，独立于 C 端账号池）。
     *
     * <p>与 {@link #userNo} <b>至少一个非空</b>。库上不加 CHECK ——
     * MySQL 部分版本会静默忽略它，而一个被忽略的约束比没有约束更危险：
     * 它让人以为库上拦得住。由应用层保证 + 守卫测试。
     */
    private String loginPhone;

    /** 老板：<b>全主体全门店</b>，不需要逐店授权行。 */
    private Boolean isOwner;

    /** 该用户的默认主体。App 多商家切换用；小程序恒取它。 */
    private Boolean isPrimary;

    private String status;
}
