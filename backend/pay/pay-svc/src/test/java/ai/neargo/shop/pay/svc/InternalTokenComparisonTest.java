package ai.neargo.shop.pay.svc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 内部令牌只能做常量时间比较（F11 · Review R12；TDD-支付服务-I1存量缺陷）。
 *
 * <p>{@code String.equals} 在第一个不同的字节就返回，耗时随「猜对了几位」变化 —— 时序侧信道，
 * 能逐位猜出共享密钥。仓库里 188 处端点走 {@code InternalServiceResponses.invoke}（{@code MessageDigest.isEqual}），
 * 只有 {@code InternalPayEndpoint} 手写了 {@code token.equals(given)}。这条守卫让下一处手写的当场红。
 */
class InternalTokenComparisonTest {

    private static final Path SRC = Path.of("src", "main", "java");

    /** {@code xxxToken.equals(…)} 或 {@code .equals(xxxToken)}，大小写都算 */
    private static final Pattern EQUALS_ON_TOKEN = Pattern.compile(
            "\\b\\w*[tT]oken\\w*\\s*\\.\\s*equals(IgnoreCase)?\\s*\\(|\\.equals(IgnoreCase)?\\s*\\(\\s*\\w*[tT]oken\\w*\\s*\\)");

    @Test
    @DisplayName("★★★ pay-svc 里没有用 equals 比较令牌 —— 非常量时间比较能被逐位猜出密钥")
    void noStringEqualsOnTokens() throws IOException {
        assertThat(Files.isDirectory(SRC)).as("pay-svc 源码目录不在 —— 搬家了？").isTrue();
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                String code = Files.readString(f, StandardCharsets.UTF_8)
                        .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
                if (EQUALS_ON_TOKEN.matcher(code).find()) {
                    offenders.add(SRC.relativize(f).toString());
                }
            }
        }
        assertThat(scanned).isGreaterThan(30);
        assertThat(offenders)
                .as("这些文件用 equals 比较令牌：%s\n  改走 InternalServiceResponses.invoke，或 MessageDigest.isEqual", offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("★ 判据自身能量出东西")
    void detectorFires() {
        assertThat(EQUALS_ON_TOKEN.matcher("return token.equals(given);").find()).isTrue();
        assertThat(EQUALS_ON_TOKEN.matcher("if (given.equals(internalToken))").find()).isTrue();
        assertThat(EQUALS_ON_TOKEN.matcher("MessageDigest.isEqual(a, b)").find()).isFalse();
        assertThat(EQUALS_ON_TOKEN.matcher("status.equals(other)").find()).isFalse();
    }
}
