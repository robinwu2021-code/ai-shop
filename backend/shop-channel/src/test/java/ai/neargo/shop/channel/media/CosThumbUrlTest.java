package ai.neargo.shop.channel.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 运营端列表的小图地址（COS 下）。挂错后缀的代价：那一格 404，运营又回到盲删。
 */
@DisplayName("COS 下列表小图：公开图缩略图、私有图短签名")
class CosThumbUrlTest {

    private static final String GOODS = "M1/S1/goods/202609/ab.jpg";
    private static final String LICENSE = "M1/S1/license/202609/cd.png";

    private final CosMediaStore own = new CosMediaStore("id", "key", "ap-guangzhou", "b-1300000000",
            "https://img.hxmall.top", "https://www.hxmall.top/cos-private");
    private final CosMediaStore bare = new CosMediaStore("id", "key", "ap-guangzhou", "b-1300000000", "", "");

    @Test
    @DisplayName("★★★ 配了自有域名：公开图挂 !w200（nginx 与数据万象都认的写法）")
    void ownDomainGetsThumbSuffix() {
        assertThat(own.thumbUrl(GOODS, true, 200)).isEqualTo("https://img.hxmall.top/" + GOODS + "!w200");
    }

    @Test
    @DisplayName("★★ 没配自有域名：给原图 —— 桶默认域名上没有这个样式，挂了就 404")
    void bareBucketGetsOriginal() {
        assertThat(bare.thumbUrl(GOODS, true, 200))
                .isEqualTo("https://b-1300000000.cos.ap-guangzhou.myqcloud.com/" + GOODS);
    }

    @Test
    @DisplayName("★★ nginx 不放行的宽度：给原图，不拼一个必然 404 的地址")
    void unsupportedWidthGetsOriginal() {
        assertThat(own.thumbUrl(GOODS, true, 160)).isEqualTo("https://img.hxmall.top/" + GOODS);
    }

    @Test
    @DisplayName("★★★ 私有图（证件）：签名地址，经 /cos-private/，绝不给公开缩略图")
    void privateGetsSignedUrl() {
        String url = own.thumbUrl(LICENSE, false, 200);

        assertThat(url).startsWith("https://www.hxmall.top/cos-private/" + LICENSE + "?q-sign");
        assertThat(url).doesNotContain("!w").doesNotContain("img.hxmall.top");
    }
}
