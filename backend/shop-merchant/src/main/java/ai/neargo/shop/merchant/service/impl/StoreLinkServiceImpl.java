package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.merchant.service.StoreLinkService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * {@link StoreLinkService} 的实现。域名备案下来之后配上 {@code shop.web.base-url} 即可，
 * 这一层不用动。
 */
@Service
public class StoreLinkServiceImpl implements StoreLinkService {

    private final String baseUrl;

    public StoreLinkServiceImpl(@Value("${shop.web.base-url:}") String baseUrl) {
        // 末尾斜杠会拼出 `//s/xxx`：多数服务器认，少数会 404，而这种错很难看出来
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    @Override
    public boolean available() {
        return !baseUrl.isBlank();
    }

    @Override
    public String linkOf(String storeCode, String goodsNo) {
        if (!available() || storeCode == null || storeCode.isBlank()) {
            return null;
        }
        return baseUrl + "/s/" + storeCode
                + (goodsNo == null || goodsNo.isBlank() ? "" : "?g=" + goodsNo);
    }
}
