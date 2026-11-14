package ai.neargo.shop.pay.channel.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 渠道的 HTTP 应答。头名大小写不敏感（{@link #header}） */
public record ChannelHttpResponse(int status, Map<String, String> headers, String body) {

    public ChannelHttpResponse {
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        body = body == null ? "" : body;
    }

    public String header(String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    public boolean ok() {
        return status >= 200 && status < 300;
    }
}
