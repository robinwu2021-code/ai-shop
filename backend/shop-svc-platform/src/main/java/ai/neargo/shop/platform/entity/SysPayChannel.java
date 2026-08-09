package ai.neargo.shop.platform.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 支付通道注册表：取值域与能力位（V38、V39）。
 *
 * <p><b>表管能力，类型管取值。</b> 取值域在 shared 的 {@code PayChannel} 联合类型里，
 * 能力位在这张表里：
 * <ul>
 *   <li>只有表 —— 会冒出一个谁都没定义过的通道，而代码里的 switch 会静默漏掉它</li>
 *   <li>只有类型 —— 通道能力一变（对方开放新接口、调额度）就要发版</li>
 * </ul>
 *
 * <p>建这张表最主要的理由是 {@code supportsSubsidy}：
 * 「这个通道支不支持补差」决定积分抵扣能不能用，
 * 而这是<b>运行时判据</b>，不能是代码里的 if —— 每加一个通道就要改一次结算逻辑。
 */
@Getter
@Setter
@TableName("sys_pay_channel")
public class SysPayChannel extends BaseEntity {

    public static final String WECHAT = "WECHAT";
    public static final String ALIPAY = "ALIPAY";

    private String payChannel;

    private String name;

    private Boolean enabled;

    /**
     * 能否<b>补差</b>（分账前把平台补贴转入二级商户账户）。
     *
     * <p>为 0 时该通道<b>不开积分抵扣</b> —— {@code canPoints()} 直接判否，
     * 用户看到「当前支付方式不支持积分抵扣」。<b>不做兜底记账</b>：
     * 那意味着一套余额表、冲抵逻辑、打款流程和监控，服务的却是一个尚不存在的通道。
     */
    private Boolean supportsSubsidy;

    private Boolean supportsSplit;

    private Boolean supportsPayout;

    /** JSON：["JSAPI","APP","H5","NATIVE"] */
    private String payMethods;

    /** JSON：该通道在哪些市场可用，如 ["CN"] */
    private String markets;

    /** 平台在该通道的资金账户标识。**只存对账用的引用，不存密钥或完整账号**。 */
    private String poolAccountRef;

    /**
     * 单笔订单最多可部分退款几次（微信 50，0 = 未知/不限）。
     *
     * <p>它约束的是设计而不只是调用：<b>不能设计成「每件商品独立退」的高频路径</b>。
     */
    private Integer maxPartialRefunds;

    /** 两次退款调用的最小间隔（微信 60 秒）。批量退款要按它排队，否则被通道拒绝。 */
    private Integer refundIntervalSeconds;

    /**
     * 单笔交易最高分账比例（万分比；支付宝直付通 3000 = 30%）。
     *
     * <p>我们分走佣金 + 服务费约 3–5%，安全。但费率是会调的，
     * <b>调过头的表现是分账被通道拒绝</b> —— 而那时订单已经付过款了。
     */
    private Integer maxSplitRate;
}
