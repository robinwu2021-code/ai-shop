package ai.neargo.shop.pay.api.guard.core;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 缓存原始请求体：验签要对<b>原始字节</b>算哈希，controller 之后还要再读一遍。超过上限直接拒（安全规范 §4.7：支付报文 1MB 内）。
 */
public final class CachedBodyRequest extends HttpServletRequestWrapper {

    public static final int MAX_BODY = 1 << 20;

    private final byte[] body;
    /** 交给 controller 的请求体：⑨ 解密后替换；没替换就是原始那份 */
    private byte[] effective;

    public CachedBodyRequest(HttpServletRequest req) throws IOException {
        super(req);
        byte[] b = req.getInputStream().readNBytes(MAX_BODY + 1);
        if (b.length > MAX_BODY) {
            throw new IOException("请求体超过 " + MAX_BODY + " 字节");
        }
        this.body = b;
        this.effective = b;
    }

    /** 原始请求体（验签、请求记录用 —— 记录里不出现解密后的明文） */
    public byte[] body() {
        return body;
    }

    /** ⑨ 敏感字段解密后，controller 读到的是这一份（开发计划 M3.5） */
    public void replaceBody(byte[] decrypted) {
        this.effective = decrypted;
    }

    @Override
    public int getContentLength() {
        return effective.length;
    }

    @Override
    public long getContentLengthLong() {
        return effective.length;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(effective);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener l) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read() {
                return in.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
