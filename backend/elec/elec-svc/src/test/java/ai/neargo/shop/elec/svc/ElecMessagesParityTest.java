package ai.neargo.shop.elec.svc;

import ai.neargo.shop.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 元器件服务的文案包 {@code i18n/elec/messages*} 是从主系统抄的一份：
 * <b>两个进程报同一个码要说同一句话</b>。这里钉两件事：本服务会报的码都有文案，且与主系统逐字一致。
 */
class ElecMessagesParityTest {

    private static final Path MAIN = Path.of("../../shop-app/src/main/resources/i18n");

    @Test
    @DisplayName("★★ 每个元器件错误码三种语言都有文案，且与主系统逐字一致")
    void elecBundleMatchesMain() throws IOException {
        for (String suffix : new String[]{"", "_en", "_ar"}) {
            Properties elec = load("i18n/elec/messages" + suffix + ".properties");
            Properties main = new Properties();
            try (var r = Files.newBufferedReader(MAIN.resolve("messages" + suffix + ".properties"),
                    StandardCharsets.UTF_8)) {
                main.load(r);
            }
            assertThat(main).as("主系统文案包没找到 —— 目录搬了？").isNotEmpty();
            for (ErrorCode c : ErrorCode.values()) {
                if (c.name().startsWith("ELEC_")) {
                    assertThat(elec.getProperty(c.msgKey())).as(suffix + " 缺 " + c.msgKey()).isNotBlank();
                }
            }
            for (String key : elec.stringPropertyNames()) {
                assertThat(elec.getProperty(key)).as(suffix + " 的 " + key + " 与主系统不一致")
                        .isEqualTo(main.getProperty(key));
            }
        }
    }

    private static Properties load(String cp) throws IOException {
        Properties p = new Properties();
        try (var in = ElecMessagesParityTest.class.getClassLoader().getResourceAsStream(cp)) {
            assertThat(in).as(cp + " 不在 classpath 上").isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }
}
