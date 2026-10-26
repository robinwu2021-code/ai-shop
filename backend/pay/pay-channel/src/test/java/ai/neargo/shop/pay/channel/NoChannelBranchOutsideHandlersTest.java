package ai.neargo.shop.pay.channel;

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
 * 渠道差异只允许出现在处理器与协议适配两层（渠道接入统一封装设计 §1、§17；TDD-支付服务-I2渠道统一封装 AC12）。
 *
 * <p>门面、管线、容错、注册表里出现一个 {@code "NEWEBPAY"} 字面量，就是「渠道差异漏到了公共层」——
 * 加第六个渠道时要改的地方从「加一个处理器」变成「全仓 grep」，而漏改的那一处不会报错。
 */
class NoChannelBranchOutsideHandlersTest {

    private static final Path ROOT = Path.of("src", "main", "java", "ai", "neargo", "shop", "pay", "channel");
    private static final List<String> COMMON_LAYERS = List.of("api", "invoke", "fault", "handler", "capability");
    private static final Pattern CHANNEL_LITERAL = Pattern.compile("\"(WECHAT|ALIPAY|NEWEBPAY|LINEPAY|PAYBY|STUB|TEST)\"");

    @Test
    @DisplayName("★★★ 公共层（门面 / 管线 / 容错 / 注册表）里没有渠道码字面量")
    void noChannelLiteralInCommonLayers() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (String layer : COMMON_LAYERS) {
            Path dir = ROOT.resolve(layer);
            assertThat(Files.isDirectory(dir)).as("%s 不在了 —— 搬家了？", dir).isTrue();
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    scanned++;
                    String code = Files.readString(f, StandardCharsets.UTF_8)
                            .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
                    if (CHANNEL_LITERAL.matcher(code).find()) {
                        offenders.add(ROOT.relativize(f).toString());
                    }
                }
            }
        }
        assertThat(scanned).isGreaterThan(20);
        assertThat(offenders).as("这些公共层文件按渠道码分支：%s\n  渠道差异放进处理器（channel.<渠道>）或协议适配", offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("★ 判据自身能量出东西；注释里提到渠道名不算")
    void detectorFires() {
        assertThat(CHANNEL_LITERAL.matcher("if (\"NEWEBPAY\".equals(c))").find()).isTrue();
        assertThat(CHANNEL_LITERAL.matcher("PayScene.ANY").find()).isFalse();
    }
}
