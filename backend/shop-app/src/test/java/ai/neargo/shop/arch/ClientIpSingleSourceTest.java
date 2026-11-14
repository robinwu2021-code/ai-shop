package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 取客户端 IP 只有一处（支付服务-安全规范 §4.5，G2）：按 IP 的限流、白名单、审计都要用同一个「可信 IP」。
 * 第二处自己读 {@code X-Forwarded-For} 的代码，就是第二个能被伪造头绕过的口子（2026-10-26 前有两处）。
 */
class ClientIpSingleSourceTest {

    private static final String OWNER = "shop-base-auth/src/main/java/ai/neargo/shop/auth/ClientMeta.java";

    @Test
    @DisplayName("★★★ 主代码里只有 ClientMeta 读 X-Forwarded-For / X-Real-IP")
    void onlyClientMetaReadsForwardingHeaders() throws IOException {
        Path backend = Path.of("..").toAbsolutePath().normalize();
        List<String> offenders;
        try (Stream<Path> files = Files.walk(backend)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> {
                        try {
                            // 不分大小写：头名大小写不敏感，代码里也有人写成 "x-forwarded-for"
                            String s = Files.readString(p).toLowerCase(java.util.Locale.ROOT);
                            return s.contains("\"x-forwarded-for\"") || s.contains("\"x-real-ip\"");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(p -> backend.relativize(p).toString())
                    .filter(p -> !p.equals(OWNER))
                    .toList();
        }
        assertThat(offenders).as("改用 ClientMeta.clientIp(request)").isEmpty();
    }
}
