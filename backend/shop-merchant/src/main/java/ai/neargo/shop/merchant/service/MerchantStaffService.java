package ai.neargo.shop.merchant.service;

import ai.neargo.shop.merchant.dto.StaffVO;

/**
 * 商家员工账号（B-11.10）。
 *
 * <p><b>员工有自己的登录身份，不依赖 C 端账号</b>。早先的设计强制绑 C 端账号，
 * 理由是「店员多半已是 C 端用户」——那只在小程序里成立。在 App 上，
 * 要求店员先注册成消费者才能上班，是把雇佣关系硬塞进一个消费关系里。
 *
 * <p>两条登录路径解析到<b>同一条员工记录</b>：
 * 小程序走 C 端账号（{@code user_no}），App 走员工手机号（{@code login_phone}）。
 */
public interface MerchantStaffService {

    /**
     * 员工登录（App 路径）。手机号 + 验证码，<b>不建 C 端账号</b>。
     *
     * <p>没有密码：小店里没有人能帮店员重置密码，而短信验证码不需要记。
     *
     * @return 令牌；该手机号不是任何主体的在职员工时抛 403 ——
     *         <b>不是「账号不存在」</b>，那会把「谁是这家店的员工」变成一条可枚举的信息
     */
    String loginByPhone(String phone, String code);

    /**
     * 这个 principal 的员工登录手机号；不是员工就返回空串。
     *
     * <p>{@code principal} 两条路径都认（{@code user_no} 或 {@code mch_account_no}），
     * 与 {@code BizIdentityResolver} 同一口径 —— 两处分岔的话，
     * 会出现「作用域解析得到、档案查不到」这种自相矛盾的响应。
     */
    String loginPhoneOf(String principal);

    // ---------------------------------------------------------------- 员工管理（B-11.10）

    /** 本主体的员工列表（含已停用的）。停用的也要看得见 —— 看不见的话没人能把他重新启用。 */
    java.util.List<StaffVO> list(String merchantNo);

    /**
     * 新增员工。
     *
     * <p><b>不发密码、不建 C 端账号</b>：加一行记录，他用自己的手机号 + 验证码就能登录。
     * 让店长替店员设密码，等于店长知道店员的密码。
     *
     * <p>同一手机号在同一主体下只能有一条（库上有唯一键兜底）。
     * 重复添加时**把已停用的那条重新启用**，而不是报「已存在」——
     * 店员离职再回来是常事，报错只会让店长去建一个带后缀的假号码。
     */
    StaffVO add(String merchantNo, String loginPhone);

    /**
     * 停用 / 启用员工。
     *
     * <p><b>老板不能被停用</b>：停掉之后这个主体就没有人能管了，
     * 而恢复它需要平台介入 —— 一个能把自己锁在门外的按钮不该存在。
     *
     * <p>停用**不删门店授权**：他回来时授权还在。真要收回授权是另一个动作。
     */
    StaffVO setStatus(String merchantNo, String mchAccountNo, boolean active);

    /**
     * 授权到店：给这个员工在某家门店一个角色。
     *
     * <p><b>逐店授权</b>，A 店店长可以同时是 B 店店员 —— 这是小连锁的常态：
     * 老店的店长去新店帮忙，但新店不归他管。
     *
     * <p>{@code role} 传空表示<b>收回这家店的授权</b>。
     */
    /**
     * 授予或撤销**一个**门店角色（V18 起一人一店可多角色）。
     *
     * <p><b>增量式，不是覆盖式</b>：这一次只动 {@code role} 这一个角色，不碰别的。
     * 覆盖式在多角色下是错的 —— 老板想「再加一个配送员」，结果把「店员」冲掉了。
     *
     * @param granted true 授予、false 撤销。撤到一个不剩 = 从这家店移除他
     */
    StaffVO grantStore(String merchantNo, String mchAccountNo, String storeNo,
                       String role, boolean granted);
}
