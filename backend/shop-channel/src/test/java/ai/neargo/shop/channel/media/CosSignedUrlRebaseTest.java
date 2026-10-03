package ai.neargo.shop.channel.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 私有图的签名地址换到 {@code https://www.hxmall.top/cos-private}（ADR-026）。
 *
 * <p>换的只能是「协议 + 主机」：路径与查询串（签名本身）差一个字节，COS 就回 403 ——
 * 而那要到有人打开证件照时才看得见。
 */
@DisplayName("私有图签名地址：只换域名，签名一个字节不动")
class CosSignedUrlRebaseTest {

    private static final String BASE = "https://www.hxmall.top/cos-private";
    private static final String KEY = "M001/S001/license/202609/7d1e.png";

    @Test
    @DisplayName("★★★ 配了 private-base-url：前缀换掉，路径与签名逐字保留")
    void rebasesHostKeepsSignature() throws Exception {
        CosMediaStore raw = new CosMediaStore("id", "key", "ap-guangzhou", "b-1300000000", "", "");
        CosMediaStore ours = new CosMediaStore("id", "key", "ap-guangzhou", "b-1300000000", "", BASE + "/");

        URL sdk = new URI(raw.signedUrl(KEY, java.time.Duration.ofMinutes(5))).toURL();
        String rebased = CosMediaStore.rebase(sdk, BASE);

        assertThat(rebased).startsWith(BASE + "/" + KEY + "?");
        assertThat(rebased.substring(BASE.length())).isEqualTo(sdk.getPath() + "?" + sdk.getQuery());
        assertThat(sdk.getQuery()).as("SDK 给的确实是签名地址").contains("q-signature=");
        assertThat(ours.signedUrl(KEY, java.time.Duration.ofMinutes(5))).startsWith(BASE + "/" + KEY + "?q-sign");
    }

    @Test
    @DisplayName("★ 没配：原样返回 SDK 的地址（本地与未切换的环境不受影响）")
    void emptyBaseKeepsSdkUrl() {
        CosMediaStore raw = new CosMediaStore("id", "key", "ap-guangzhou", "b-1300000000", "", "");

        assertThat(raw.signedUrl(KEY, java.time.Duration.ofMinutes(5)))
                .startsWith("https://b-1300000000.cos.ap-guangzhou.myqcloud.com/" + KEY + "?");
    }
}
