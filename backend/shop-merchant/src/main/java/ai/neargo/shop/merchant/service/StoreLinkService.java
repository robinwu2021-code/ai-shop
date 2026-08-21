package ai.neargo.shop.merchant.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 店铺分享链接。<b>唯一一处拼这个 URL 的地方</b>。
 *
 * <p>此前它在两个控制器里各写死一遍：
 * {@code "https://shop.example.com/s/" + code} —— 一个<b>占位域名</b>。
 * 商家复制出去的链接、印在包装袋上的码，指向一个不存在的地方，
 * 而 B-3.3「分享素材」与 B-11.12.6「店铺码」在清单上都标着已实现。
 *
 * <p><b>没配域名就返回 null，不发假链接。</b> 端上据此只显示小程序码、
 * 不显示链接 —— 少显示一样东西，比显示一个点不开的链接好得多：
 * 后者要等商家印了几百张贴纸才会发现。
 *
 * <p>域名备案下来之后配上 {@code shop.web.base-url} 即可，这一层不用改。
 */
@Service
public class StoreLinkService {

    private final String baseUrl;

    public StoreLinkService(@Value("${shop.web.base-url:}") String baseUrl) {
        // 末尾斜杠会拼出 `//s/xxx`：多数服务器认，少数会 404，而这种错很难看出来
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    /** 是否已配好可用的对外域名。 */
    public boolean available() {
        return !baseUrl.isBlank();
    }

    /**
     * 店铺（或店内某商品）的分享链接。
     *
     * @return 未配域名时 <b>null</b> —— 调用方据此不显示链接
     */
    public String linkOf(String storeCode, String goodsNo) {
        if (!available() || storeCode == null || storeCode.isBlank()) {
            return null;
        }
        return baseUrl + "/s/" + storeCode
                + (goodsNo == null || goodsNo.isBlank() ? "" : "?g=" + goodsNo);
    }
}
