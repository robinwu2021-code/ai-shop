package ai.neargo.shop.payclient;

import java.util.List;

/**
 * 平台端 · 支付通道设置与费率。
 *
 * <p>2026-09-01 从 {@code shop-core/platform/api/ops} 搬过来：通道属性归 pay 之后，
 * 它的运营界面也该走 {@code portal/ops/pay} 这条路 —— 与其余 12 个支付
 * controller 一致（三端 controller 在主应用，业务在 app service，领域在 pay）。
 *
 * <h2>两条与既有页面一致的规矩</h2>
 * <ul>
 *   <li><b>费率只增不改</b>：调费率是插一个新版本，旧版本永久保留 ——
 *       真正会被问到的是「上个月那批单当时按什么费率算的」；</li>
 *   <li><b>关掉一个通道只影响新进件与新下单</b>：已开通的商户与在途的单不受影响。</li>
 * </ul>
 */
public interface OpsPayChannelAppService {

    /** 通道清单 + 每个通道的费率版本（含预约生效的） */
    List<ChannelVO> channels();

    /**
     * 改开关与结算属性。<b>能力位不在这里改</b> ——
     * 那几个位是通道自己的事实，不是运营的选择。
     */
    ChannelVO update(String channel, Boolean enabled, String markets,
                     String currency, String settleCycle);

    /** 加一版费率。<b>不改旧行</b> */
    RateVO addRate(String channel, String payMethod, String legalForm, Integer rateBp,
                   Long minFeeMinor, Long effectiveFrom, String remark);

    /**
     * @param currentRate 此刻生效的那一版；<b>一条都没配时为 null</b> ——
     *                    页面要把它显示成「未配置费率」，不是 0
     */
    record ChannelVO(String payChannel, String name, boolean enabled, String markets,
                     String currency, String settleCycle, boolean supportsSubsidy,
                     RateVO currentRate, List<RateVO> rates) {
    }

    /**
     * 费率的一个版本。
     *
     * <p>不直接返回 {@code SysPayChannelRate}：它带 {@code @TableName}，
     * 而且继承 {@code BaseEntity} —— 那会把 {@code tenantNo}/{@code deleted}/
     * {@code version} 一起发给运营端。与 {@code FeeRuleVO} 是同一条理由，
     * 那次也是「顺手返回 entity」变成的事实契约。
     */
    record RateVO(String rateNo, String payChannel, String payMethod, String legalForm,
                  Integer rateBp, Long minFeeMinor, Long effectiveFrom, Boolean enabled,
                  String remark) {
    }
}
