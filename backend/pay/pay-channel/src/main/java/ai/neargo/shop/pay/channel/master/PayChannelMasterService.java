package ai.neargo.shop.pay.channel.master;

import ai.neargo.shop.pay.channel.entity.SysPayChannel;
import java.util.List;
import java.util.Optional;

/**
 * 支付通道的主数据 —— <b>通道的属性就是支付域的知识</b>。
 *
 * <h2>为什么从 platform 搬过来</h2>
 * 这些属性此前住在 {@code shop-core/platform}（平台主数据），
 * 支付域通过 {@code MasterDataPort} 反向问它。而按
 * 「除回调外不做反向依赖，pay 只解决 pay 的核心问题」这条原则，
 * 该问的不是「怎么远程调它」，而是<b>「它本来该在哪」</b>——
 * 没有比支付域更该知道「微信收多少手续费、支不支持补贴、几天结算」的地方。
 *
 * <p>搬过来之后 {@code MasterDataPort} 上那三个方法从 pay 的依赖里消失，
 * <b>一行网络调用都不用加</b>。
 *
 * <h2>业务域还要用的那两个，走 spi</h2>
 * {@code channelName} 与 {@code enabledChannels} 被 {@code shop-merchant}
 * 用于商家进件（选通道、显示通道名）。它们通过
 * {@link ai.neargo.shop.spi.pay.PayChannelMasterPort} 拿 ——
 * 方向是<b>业务域 → pay</b>，与既有原则一致（业务域 0 处 import pay）。
 */
public interface PayChannelMasterService {

    /** 一个通道的全部属性。查不到返回空 */
    Optional<SysPayChannel> find(String payChannel);

    /**
     * 启用中的通道。
     *
     * @param market 市场（如 {@code CN}）；为空不筛
     */
    List<SysPayChannel> enabled(String market);

    /**
     * 通道支不支持补贴。
     *
     * <p><b>查不到时返回 false</b>：不支持而当成支持的话，补贴调用会被通道拒绝，
     * 而那笔钱本该由平台补给商家 —— 症状是商家账上少一笔，且没有任何一处报错
     * （这句话是从原实现的注释里搬过来的，它说的仍然成立）。
     */
    boolean supportsSubsidy(String payChannel);

    /** 通道结算周期（如 {@code T+1}）。查不到返回 null，调用方取两者更短的那个 */
    String settleCycle(String payChannel);

    // ──────────────────────────────────── 运营端维护（controller 在主应用侧）

    /** 全部通道，按 id 排。运营页要看到停用的那些 */
    List<SysPayChannel> all();

    /**
     * 改开关与结算属性。
     *
     * <p><b>能力位不在这里改</b> —— 支不支持补差、分账上限多少是通道自己的事实，
     * 不是运营的选择。改错会让积分抵扣在一个做不到补差的通道上开出来，
     * 而那是资金差错。（这条规矩从原 controller 的注释搬过来，它仍然成立。）
     *
     * @return 改完之后的行；通道不存在时抛 {@code NOT_FOUND}
     */
    SysPayChannel updateSettings(String payChannel, Boolean enabled, String markets,
                                 String currency, String settleCycle);
}
