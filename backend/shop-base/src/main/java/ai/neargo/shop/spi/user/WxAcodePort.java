package ai.neargo.shop.spi.user;

/**
 * 域 → channel：微信小程序码（{@code wxacode.getUnlimited}）。
 *
 * <p><b>为什么是小程序码而不是 H5 链接</b>：ADR-004 的主获客路径是
 * 「商家把码印在包装袋、发进客户群，老客扫码直达」。小程序码
 * <b>不依赖备案域名</b>（备案要 7–20 个工作日），扫了直接进小程序门店页，
 * 而 H5 链接要先落网页再引导跳小程序，中间那一跳会掉一半人。
 */
public interface WxAcodePort {

    /**
     * 生成小程序码。
     *
     * @param scene 场景值，<b>原样带回小程序</b>（微信限 32 字符）。放店铺码即可
     * @param page  落地页，如 {@code pages/store/index}
     * @return PNG 字节；通道未开启时返回 null（调用方据此不显示码，而不是显示一张坏图）
     */
    byte[] unlimited(String scene, String page);

    /** 通道是否真的接通。端上据此决定「显示码」还是「显示一句稍后再来」 */
    boolean enabled();
}
