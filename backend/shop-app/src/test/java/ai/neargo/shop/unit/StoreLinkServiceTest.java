package ai.neargo.shop.unit;

import ai.neargo.shop.merchant.service.StoreLinkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 店铺分享链接的拼装规则。
 *
 * <p><b>核心一条：没配域名就返回 null，不发假链接。</b>
 * 这条规则是被一个具体的错换来的 —— 两个控制器各自写死了占位域名
 * {@code https://shop.example.com/s/<code>}，商家复制出去的链接、印在包装袋上的贴纸，
 * 全都指向一个不存在的地方，而功能点在清单上标着「已实现」。
 * <b>它不报错、不崩溃、测试全绿</b>：物料生成得出来，只是物料是废的，
 * 要等商家印完几百张才暴露。
 *
 * <p>用纯构造而不是 Spring 上下文：这条规则只关乎一个字符串的处理，
 * 起个上下文来验它既慢又会与共享 H2 抢建表（见 application-testcfg.yml 里那段注释）。
 */
class StoreLinkServiceTest {

    private static final String CODE = "S-ABC123";

    @Test
    @DisplayName("★★ 没配域名 → 返回 null，绝不返回占位链接")
    void returnsNullWhenBaseUrlNotConfigured() {
        for (String unset : new String[]{"", "   ", null}) {
            StoreLinkService svc = new StoreLinkService(unset);
            assertThat(svc.available()).isFalse();
            assertThat(svc.linkOf(CODE, null))
                    .as("未配 shop.web.base-url 时必须返回 null（入参 %s）", String.valueOf(unset))
                    .isNull();
            assertThat(svc.linkOf(CODE, "G-1")).isNull();
        }
    }

    @Test
    @DisplayName("配了域名 → 整店链接 /s/{code}，带商品时挂 ?g=")
    void buildsStoreAndGoodsLinks() {
        StoreLinkService svc = new StoreLinkService("https://m.neargo.ai");

        assertThat(svc.available()).isTrue();
        assertThat(svc.linkOf(CODE, null)).isEqualTo("https://m.neargo.ai/s/" + CODE);
        assertThat(svc.linkOf(CODE, "")).isEqualTo("https://m.neargo.ai/s/" + CODE);
        assertThat(svc.linkOf(CODE, "G-XYZ")).isEqualTo("https://m.neargo.ai/s/" + CODE + "?g=G-XYZ");
    }

    @Test
    @DisplayName("末尾斜杠不会拼出 //s/ —— 少数服务器对它 404，且这种错很难看出来")
    void trimsTrailingSlashes() {
        assertThat(new StoreLinkService("https://m.neargo.ai///").linkOf(CODE, null))
                .isEqualTo("https://m.neargo.ai/s/" + CODE);
    }

    @Test
    @DisplayName("店铺码缺失时也返回 null —— 不拼一个 /s/null 出去")
    void returnsNullWhenStoreCodeMissing() {
        StoreLinkService svc = new StoreLinkService("https://m.neargo.ai");
        assertThat(svc.linkOf(null, null)).isNull();
        assertThat(svc.linkOf("  ", null)).isNull();
    }
}
