package ai.neargo.shop.channel.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图片出口切换（ADR-026）：direct 模式下 HTTP 出入口换前缀，库里永远只有规范地址。
 *
 * <p>控制器收发的是原始字符串 —— 要证的是<b>过滤器在字节层做对了</b>，不让 JSON 转换器掺进来。
 */
@DisplayName("图片出口切换：出口换 cdn、入口换回 img")
class MediaHostRewriteFilterTest {

    private static final String IMG = "https://img.hxmall.top";
    private static final String CDN = "https://cdn.hxmall.top";
    private static final String KEY = "M001/S001/goods/202609/ab12.jpg";

    /** 充当「库」：入口换回来的值最终落在这里。 */
    static final AtomicReference<String> STORED = new AtomicReference<>();

    @RestController
    static class Echo {
        @GetMapping("/mp/goods")
        ResponseEntity<String> goods() {
            String json = "{\"cover\":\"" + IMG + "/" + KEY + "\",\"images\":[\"" + IMG + "/" + KEY + "\"],"
                    + "\"content\":\"<p><img src=\\\"" + IMG + "/" + KEY + "\\\"></p>\","
                    + "\"other\":\"https://img.hxmall.topx/not-ours.jpg\"}";
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
        }

        @GetMapping("/mp/export")
        ResponseEntity<String> export() {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(IMG + "/" + KEY);
        }

        @PostMapping("/biz/goods")
        ResponseEntity<String> save(@RequestBody String body) {
            STORED.set(body);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
        }
    }

    private MockMvc mvc(String delivery) {
        FilterRegistrationBean<MediaHostRewriteFilter> reg =
                new MediaDeliveryConfig().mediaHostRewriteFilter(delivery, CDN, IMG);
        var b = MockMvcBuilders.standaloneSetup(new Echo());
        if (reg.isEnabled()) {
            b.addFilters(reg.getFilter());
        }
        return b.build();
    }

    @Test
    @DisplayName("★★★ direct：响应里的规范前缀全部换成 cdn —— 字段、数组、富文本里的都算")
    void directRewritesResponse() throws Exception {
        String body = mvc("direct").perform(get("/mp/goods")).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(IMG + "/");
        assertThat(body).contains("\"cover\":\"" + CDN + "/" + KEY + "\"");
        assertThat(body).contains("[\"" + CDN + "/" + KEY + "\"]");
        assertThat(body).contains("src=\\\"" + CDN + "/" + KEY);
        assertThat(body).as("只换完整前缀（带结尾的 /），相似的别家域名不动")
                .contains("https://img.hxmall.topx/not-ours.jpg");
    }

    @Test
    @DisplayName("★★★ direct：客户端提交回来的 cdn 地址，入库前换回规范地址 —— 否则切回 server 时全是死链")
    void directNormalizesRequestBeforeStore() throws Exception {
        String submitted = "{\"cover\":\"" + CDN + "/" + KEY + "\",\"images\":[\"" + CDN + "/" + KEY + "\"]}";

        MvcResult r = mvc("direct").perform(post("/biz/goods")
                .contentType(MediaType.APPLICATION_JSON).content(submitted)).andReturn();

        assertThat(STORED.get()).as("入库的").doesNotContain(CDN).contains(IMG + "/" + KEY);
        assertThat(r.getResponse().getContentAsString()).as("回给客户端的").doesNotContain(IMG + "/").contains(CDN);
        assertThat(r.getResponse().getContentLength())
                .as("长度要跟着改，否则客户端按旧长度截断 JSON")
                .isEqualTo(r.getResponse().getContentAsByteArray().length);
    }

    @Test
    @DisplayName("★★ server（默认）：原样放行，客户端拿到的就是规范地址")
    void serverPassesThrough() throws Exception {
        String body = mvc("server").perform(get("/mp/goods")).andReturn().getResponse().getContentAsString();

        assertThat(body).contains(IMG + "/" + KEY).doesNotContain(CDN);
    }

    @Test
    @DisplayName("★ direct：非 JSON 响应不碰")
    void directLeavesNonJsonAlone() throws Exception {
        String body = mvc("direct").perform(get("/mp/export")).andReturn().getResponse().getContentAsString();

        assertThat(body).isEqualTo(IMG + "/" + KEY);
    }

    @Test
    @DisplayName("★★ direct：SSE 不经过它 —— 缓冲整个响应等于让推送不再实时")
    void sseIsNotFiltered() {
        MediaHostRewriteFilter f = new MediaHostRewriteFilter(IMG + "/", CDN + "/");
        var req = new org.springframework.mock.web.MockHttpServletRequest("GET", "/ops/stream");
        var req2 = new org.springframework.mock.web.MockHttpServletRequest("GET", "/ops/anything");
        req2.addHeader("Accept", "text/event-stream");

        assertThat(f.shouldNotFilter(req)).isTrue();
        assertThat(f.shouldNotFilter(req2)).isTrue();
        assertThat(f.shouldNotFilter(new org.springframework.mock.web.MockHttpServletRequest("GET", "/mp/goods")))
                .isFalse();
    }

    @Test
    @DisplayName("★★ 配错就拒绝启动 —— 否则要么以为切了其实没切，要么全站裂图，都不报错")
    void misconfigurationFailsFast() {
        MediaDeliveryConfig cfg = new MediaDeliveryConfig();

        assertThatThrownBy(() -> cfg.mediaHostRewriteFilter("direct", "", IMG))
                .hasMessageContaining("SHOP_MEDIA_DIRECT_BASE_URL");
        assertThatThrownBy(() -> cfg.mediaHostRewriteFilter("direct", CDN, ""))
                .hasMessageContaining("COS_DOMAIN");
        assertThatThrownBy(() -> cfg.mediaHostRewriteFilter("direct", IMG + "/", IMG))
                .hasMessageContaining("没切");
        assertThatThrownBy(() -> cfg.mediaHostRewriteFilter("cdn", CDN, IMG))
                .hasMessageContaining("server 或 direct");
        assertThat(cfg.mediaHostRewriteFilter("server", "", IMG).isEnabled()).isFalse();
        assertThat(cfg.mediaHostRewriteFilter("direct", CDN, IMG).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("★ 没有要换的：原样返回同一个数组，不复制")
    void replaceNoMatchReturnsSameArray() {
        byte[] src = "{\"a\":1}".getBytes();

        assertThat(MediaHostRewriteFilter.replace(src, "x".getBytes(), "y".getBytes())).isSameAs(src);
        assertThat(new String(MediaHostRewriteFilter.replace("aXbXc".getBytes(), "X".getBytes(), "YY".getBytes())))
                .isEqualTo("aYYbYYc");
    }
}
