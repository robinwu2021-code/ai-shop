package ai.neargo.shop.spi.pay;

import java.util.List;

/**
 * 业务域 → pay：拿通道的名字与可用列表。
 *
 * <h2>为什么只有两个方法</h2>
 * 通道主数据一共五项，其余三项（{@code supportsSubsidy} / {@code channelFeeRate} /
 * {@code settleCycle}）<b>只有支付域自己用</b>，所以它们不在这个 Port 上 ——
 * 支付域内部直接调 {@code PayChannelMasterService}，不绕 spi。
 *
 * <p>暴露在 Port 上的两个是 {@code shop-merchant} 商家进件要用的：
 * 选通道、显示通道名。<b>方向是业务域 → pay</b>，
 * 与「业务域 0 处 import pay」的既有原则一致。
 *
 * <h2>它替代了什么</h2>
 * 2026-09-01 之前这两个方法在 {@code MasterDataPort}（平台主数据）上，
 * 而支付域也从那里拿 {@code supportsSubsidy} 等 —— 那是一条<b>反向依赖</b>：
 * pay 去问主应用「这个通道支不支持补贴」。
 *
 * <p>按「pay 只解决 pay 的核心问题」这条原则，该问的不是「怎么远程调它」，
 * 而是<b>「它本来该在哪」</b>。通道属性搬进 pay 之后那条反向依赖直接消失，
 * 剩下这两个方法方向反过来，成了正向的 spi。
 */
public interface PayChannelMasterPort {

    /** 通道展示名。查不到时返回通道码本身，不返回 null —— 界面上宁可显示 WECHAT 也别显示空 */
    String channelName(String payChannel);

    /**
     * 启用中的通道码。
     *
     * @param market 市场（如 {@code CN}）；为空按默认市场
     */
    List<String> enabledChannels(String market);

    /**
     * <b>这个市场里真的能下单的通道</b> = 运营开着的 ∩ <b>网关有实现的</b>。
     *
     * <h2>为什么不能直接用 {@link #enabledChannels}</h2>
     * 那个只看 {@code sys_pay_channel.enabled} —— 运营开了微信通道
     * 而凭证还没配、网关没装配时，它照样返回 WECHAT，
     * 于是下单走到路由那一步才失败，<b>而失败发生在流水已经落库之后</b>。
     *
     * <h2>为什么这不是「自动降级」</h2>
     * 端上<b>指定</b>了某个通道而它没有实现时，要<b>直接失败</b>，不换别的 ——
     * 换一个等于把钱发到另一个通道的商户号，那是资金事故。
     * 这个方法只用来<b>挑默认通道</b>：从一开始就只在有实现的里面挑。
     */
    List<String> payableChannels(String market);

    /**
     * 启用中的通道，带展示信息 —— 端上主数据快照要用。
     *
     * <p>与 {@link #enabledChannels} 分开而不是让调用方拿码再逐个查名字：
     * 那样 N 个通道就是 N+1 次查询，而这份快照是每次冷启动都要的。
     */
    List<ChannelBrief> enabledBriefs(String market);

    /**
     * @param payMethods 该通道支持的支付方式（JSAPI / APP / H5 …）。
     *                   <b>端上据此决定收银台显示哪几个按钮</b>
     */
    record ChannelBrief(String payChannel, String name, boolean enabled,
                        List<String> payMethods) {
    }
}
