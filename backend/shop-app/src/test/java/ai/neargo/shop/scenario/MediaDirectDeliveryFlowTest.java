package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 「经图片服务器」出口在<b>真实过滤器链</b>里生效（ADR-026）。
 *
 * <p>过滤器自己的单测是单独装配的，证不了三件事：它真的被注册了、排在安全过滤器与全局信封之外
 * 看得到完整的响应体、公开接口匿名访问时也经过它。
 *
 * <p><b>必须起真 Tomcat（RANDOM_PORT）</b>：MockMvc 的 webAppContextSetup 只带显式 addFilters 的过滤器，
 * 不会装 FilterRegistrationBean 注册的 —— 第一版这么写，响应里原样是 img，测的是一条不存在的链。
 *
 * <p>provider=cos 用假凭证：构造 COS 客户端不联网。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mediadirect;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.media.provider=cos",
        "shop.cos.secret-id=test-id",
        "shop.cos.secret-key=test-key",
        "shop.cos.region=ap-guangzhou",
        "shop.cos.bucket=hxmall-test-1300000000",
        "shop.cos.domain=https://img.hxmall.top",
        "shop.media.delivery=direct",
        "shop.media.direct-base-url=https://cdn.hxmall.top",
})
@DisplayName("图片出口 direct：真实过滤器链里 /mp/goods 返回 cdn 地址")
class MediaDirectDeliveryFlowTest {

    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();

    private java.net.http.HttpResponse<byte[]> goods() throws Exception {
        return http.send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + "/mp/goods?page=1&size=50")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    @DisplayName("★★★ 库里是 img，公开接口返回的是 cdn —— 匿名请求也经过出口改写")
    void publicGoodsListServesCdnHost() throws Exception {
        String before = new String(goods().body(), java.nio.charset.StandardCharsets.UTF_8);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT goods_no, cover FROM prd_goods");
        String goodsNo = rows.stream().map(r -> (String) r.get("goods_no"))
                .filter(before::contains).findFirst()
                .orElseThrow(() -> new AssertionError("种子里没有一个出现在 /mp/goods 的商品：" + before));
        Object original = rows.stream().filter(r -> goodsNo.equals(r.get("goods_no")))
                .findFirst().orElseThrow().get("cover");

        String canonical = "https://img.hxmall.top/M1/S1/goods/202609/direct-probe.jpg";
        jdbc.update("UPDATE prd_goods SET cover = ? WHERE goods_no = ?", canonical, goodsNo);
        try {
            var res = goods();
            String body = new String(res.body(), java.nio.charset.StandardCharsets.UTF_8);

            assertThat(body).contains("https://cdn.hxmall.top/M1/S1/goods/202609/direct-probe.jpg");
            assertThat(body).doesNotContain("https://img.hxmall.top/");
            res.headers().firstValueAsLong("Content-Length").ifPresent(len ->
                    assertThat(len).as("长度跟着改了，否则客户端按旧长度截断 JSON").isEqualTo(res.body().length));
            assertThat(jdbc.queryForObject("SELECT cover FROM prd_goods WHERE goods_no = ?", String.class, goodsNo))
                    .as("库里仍是规范地址").isEqualTo(canonical);
        } finally {
            jdbc.update("UPDATE prd_goods SET cover = ? WHERE goods_no = ?", original, goodsNo);
        }
    }
}
