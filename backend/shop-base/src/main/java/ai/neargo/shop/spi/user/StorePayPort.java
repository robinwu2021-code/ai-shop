package ai.neargo.shop.spi.user;

/**
 * product → user：查门店的支付开关。
 *
 * <p><b>为什么与 {@link QualificationPort} 分开而不是合成一个「这家店能不能线下收款」</b>：
 * 两者失败时要给出<b>不同的原因</b> —— 「主体资质不足」与「这家店没开」
 * 是商家要采取的两种完全不同的行动（去补证 vs 去后台点一下开关）。
 * 合成一个布尔值的话，界面只能说「不支持」，商家不知道该干什么。
 */
public interface StorePayPort {

    /**
     * 这家店是否接受线下（当面）收款。<b>默认关</b> —— 查不到门店也返回 false。
     *
     * <p>资质挂在<b>主体</b>上，这个开关在<b>门店</b>上：
     * 一家主体下三家分店共用同一张证，但可以只有临街那家开线下收款。
     */
    boolean offlinePayEnabled(String storeNo);

    /**
     * 这家店是否接受货到付款（商家自送 + 线下付）。
     *
     * <p><b>与 {@link #offlinePayEnabled} 是两个开关，不跟着一起开</b>：
     * 它是整张「支付方式 × 履约方式」组合表里风险最高的一格 —— 拒收、跑单，
     * 损失全在商家，所以要商家在承担得起的时候自己打开。
     */
    boolean codEnabled(String storeNo);
}
