package ai.neargo.shop.merchant.service;

/**
 * 分享海报（获客工具 P2，2026-08-24）。
 *
 * <p>「分享素材」原来只有一句纯文字文案——那半句"海报"是假的：{@code ShareKitVO.posterUrl}
 * 一期直接回落地页链接，不是图。这里把它做成真的：服务端用 Java2D 把封面图/店名/价格/
 * 小程序码合成一张能直接发朋友圈的 PNG，不依赖任何外部渲染服务。
 */
public interface PosterService {

    /**
     * 渲染分享海报。
     *
     * @param merchantNo 商家单号
     * @param goodsNo    带则渲染单品海报（封面/标题/价格）；不带则渲染整店海报
     * @return PNG 字节；商家不存在时为 null（调用方据此 404）
     */
    byte[] render(String merchantNo, String goodsNo);
}
