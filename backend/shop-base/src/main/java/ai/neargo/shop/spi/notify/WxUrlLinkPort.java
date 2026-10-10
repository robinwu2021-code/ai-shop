package ai.neargo.shop.spi.notify;

import java.util.Optional;

/**
 * 域 → channel：生成一条微信小程序 URL Link（{@code urllink.generate}）。
 *
 * <p><b>为什么要它</b>：短信里的短链点开是在浏览器里，浏览器打不开小程序页 ——
 * 要一条 {@code wxaurl.cn/...} 的 URL Link 才能从浏览器唤起小程序。
 * 发货短信的短链最终指向的就是这条 URL Link（TDD-收件人物流触达与分享裂变 §4）。
 *
 * <p><b>返回 Optional 而不是抛</b>：URL Link 依赖小程序已发布且接口已开通，
 * 这两件在我们这条链上都可能暂时不满足。生成不出来时返回空，<b>调用方退回 H5 看件页</b> ——
 * 对一个 SMS 收件人来说，H5 页反而更通用（他未必是小程序用户）。
 * 所以这一跳是「能唤起小程序就唤起」的增强，不是发货短信的前置。
 */
public interface WxUrlLinkPort {

    /**
     * @param path  小程序页路径，不带前导斜杠（如 {@code pages/track/index}）
     * @param query 页面参数（如 {@code t=<token>}），URL Link 会原样带给小程序页
     * @return 形如 {@code https://wxaurl.cn/xxxx} 的链接；生成失败 / 未接通时为空
     */
    Optional<String> generate(String path, String query);
}
