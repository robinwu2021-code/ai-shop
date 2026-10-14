package ai.neargo.shop.channel.media;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 「经图片服务器」出口：在 HTTP 出入口换公开图的主机前缀（ADR-026）。
 *
 * <ul>
 *   <li><b>出</b>：JSON 响应体里的规范前缀（{@code https://img.hxmall.top/}）→ 图片服务器前缀
 *       （{@code https://cdn.hxmall.top/}）；</li>
 *   <li><b>入</b>：JSON 请求体里的图片服务器前缀 → 规范前缀。客户端会把拿到的图片地址原样提交回来
 *       （编辑商品、发评价），不换回去的话，库里就混进了 {@code cdn} 地址，切回 {@code server} 时全是死链。</li>
 * </ul>
 *
 * <p><b>为什么在这一层、而不是改 JSON 序列化</b>：代码里有几十处自行序列化，图片数组就是序列化后写进库的；
 * 全局改写会让 direct 模式把 cdn 地址写进数据库。这里只碰 HTTP 边界，库里永远只有规范地址。
 * 推送、导出这类不经过这里的出口拿到的是规范地址，照样能打开（经应用服务器），不会裂图。
 *
 * <p>按字节级的整串替换：前缀含 {@code https://} 与结尾的 {@code /}，不会误伤别的文本；
 * Jackson 不转义 {@code /}，JSON 里的地址与库里逐字相同。
 *
 * <p>只在 {@code shop.media.delivery=direct} 时启用（{@link MediaDeliveryConfig}）。
 */
public class MediaHostRewriteFilter extends OncePerRequestFilter {

    private final byte[] canonical;
    private final byte[] direct;

    MediaHostRewriteFilter(String canonicalPrefix, String directPrefix) {
        this.canonical = canonicalPrefix.getBytes(StandardCharsets.UTF_8);
        this.direct = directPrefix.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 流式与非 JSON 的不碰：SSE（{@code /ops/stream}）要边写边推，缓冲整个响应等于让它不再实时；
     * 文件导出没有图片地址，缓冲只是白占内存。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return request.getRequestURI().endsWith("/stream")
                || (accept != null && accept.contains("text/event-stream"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpServletRequest req = isJson(request.getContentType()) ? rewriteRequest(request) : request;
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(req, res);
        } finally {
            if (request.isAsyncStarted()) {
                // 异步响应在这一次分派里还没写完；不缓冲、原样放行（SSE 已在 shouldNotFilter 排除，这里兜底）
                res.copyBodyToResponse();
            } else if (isJson(res.getContentType())) {
                byte[] out = replace(res.getContentAsByteArray(), canonical, direct);
                res.resetBuffer();
                response.setContentLength(out.length);
                response.getOutputStream().write(out);
                response.flushBuffer();
            } else {
                res.copyBodyToResponse();
            }
        }
    }

    private HttpServletRequest rewriteRequest(HttpServletRequest request) throws IOException {
        byte[] in = request.getInputStream().readAllBytes();
        byte[] body = replace(in, direct, canonical);
        return new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
                ByteArrayInputStream buf = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override
                    public int read() {
                        return buf.read();
                    }

                    @Override
                    public int read(byte[] b, int off, int len) {
                        return buf.read(b, off, len);
                    }

                    @Override
                    public boolean isFinished() {
                        return buf.available() == 0;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        throw new UnsupportedOperationException();
                    }
                };
            }

            @Override
            public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }

            @Override
            public int getContentLength() {
                return body.length;
            }

            @Override
            public long getContentLengthLong() {
                return body.length;
            }
        };
    }

    static boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("json");
    }

    /** 整串替换；一处都没有时原样返回同一个数组（绝大多数请求走这条路）。 */
    static byte[] replace(byte[] src, byte[] from, byte[] to) {
        int first = indexOf(src, from, 0);
        if (first < 0) {
            return src;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(src.length + 64);
        int pos = 0;
        for (int i = first; i >= 0; i = indexOf(src, from, pos)) {
            out.write(src, pos, i - pos);
            out.write(to, 0, to.length);
            pos = i + from.length;
        }
        out.write(src, pos, src.length - pos);
        return out.toByteArray();
    }

    private static int indexOf(byte[] src, byte[] pat, int from) {
        outer:
        for (int i = from; i <= src.length - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (src[i + j] != pat[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
