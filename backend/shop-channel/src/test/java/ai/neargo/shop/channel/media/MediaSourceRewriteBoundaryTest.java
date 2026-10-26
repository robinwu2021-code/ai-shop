package ai.neargo.shop.channel.media;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.spi.media.MediaPublicSourceInternalApi;

import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MediaSourceRewriteBoundaryTest {
    @Test
    void internalEvidenceAndContextPathAreNotRewritten() throws Exception {
        var filter = new MediaHostRewriteFilter("https://img.example/", "https://cdn.example/");
        for (var context : new String[] {"", "/platform"}) {
            var req =
                    new MockHttpServletRequest(
                            "POST", context + MediaPublicSourceInternalApi.CAPTURE_PATH);
            req.setContextPath(context);
            req.setContentType("application/json");
            var res = new MockHttpServletResponse();
            filter.doFilter(
                    req,
                    res,
                    (request, response) -> {
                        var http = (HttpServletResponse) response;
                        http.setContentType("application/json");
                        http.getWriter()
                                .write("{\"publicUrl\":\"https://img.example/E/S/goods/a.jpg\"}");
                    });
            assertThat(res.getContentAsString())
                    .contains("https://img.example/")
                    .doesNotContain("https://cdn.example/");
        }
    }
}
