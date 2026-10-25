package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 支付模块的 MyBatis Mapper 只能住 {@code ai.neargo.shop.pay.mapper..}（TDD-支付服务-I0地基 AC6）。
 *
 * <h2>放错包的后果</h2>
 * 只有这个包被 {@code PayDataSourceConfig} 的 {@code @MapperScan(sqlSessionFactoryRef = "paySqlSessionFactory")} 扫到。
 * 放到别处的 Mapper 会被平台那份扫描（或干脆没人扫）—— 前者走的是平台连接池与平台事务，
 * 「支付写入随下单事务一起回滚」又回来了，而且不报错；后者启动时才报找不到 bean。
 * v4 要新建 35 张表、几十个 Mapper，这条在第一张表之前立起来。
 */
class PayMapperPackageTest {

    private static final Path PAY = Paths.get(System.getProperty("user.dir")).getParent().resolve("pay");
    private static final String MAPPER_ROOT = "ai.neargo.shop.pay.mapper";

    private static final Pattern MAPPER = Pattern.compile(
            "@Mapper\\b|interface\\s+\\w+\\s+extends\\s+(?:[\\w.]+\\.)?BaseMapper\\s*<");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);

    @Test
    @DisplayName("★★★ 支付 Mapper 全在 pay.mapper.. 下 —— 放错包会连到平台连接池，支付写入跟着业务事务回滚")
    void mappersLiveUnderPayMapper() throws IOException {
        assertThat(Files.isDirectory(PAY)).as("backend/pay 不在了 —— 搬家了？").isTrue();

        List<String> offenders = new ArrayList<>();
        int mappers = 0;
        try (Stream<Path> files = Files.walk(PAY)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .toList()) {
                String code = Files.readString(f, StandardCharsets.UTF_8)
                        .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
                if (!MAPPER.matcher(code).find()) {
                    continue;
                }
                mappers++;
                String pkg = packageOf(code);
                if (!(pkg.equals(MAPPER_ROOT) || pkg.startsWith(MAPPER_ROOT + "."))) {
                    offenders.add(PAY.relativize(f) + "（package " + pkg + "）");
                }
            }
        }

        assertThat(mappers).as("一个 Mapper 都没认出来 —— 判据失效，下面的断言恒真").isGreaterThan(20);
        assertThat(offenders)
                .as("这些支付 Mapper 不在 %s.. 下：%s\n  只有那个包被 paySqlSessionFactory 扫描。", MAPPER_ROOT, offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("★ 判据自身能量出东西：@Mapper 与 extends BaseMapper 两种写法都认")
    void detectorRecognisesBothStyles() {
        assertThat(MAPPER.matcher("@Mapper public interface A {}").find()).isTrue();
        assertThat(MAPPER.matcher("public interface A extends BaseMapper<X> {}").find()).isTrue();
        assertThat(MAPPER.matcher("interface A extends com.baomidou.mybatisplus.core.mapper.BaseMapper<X> {}").find()).isTrue();
        assertThat(MAPPER.matcher("class MapperUtils {}").find()).isFalse();
        assertThat(packageOf("package ai.neargo.shop.pay.impl;\n")).isEqualTo("ai.neargo.shop.pay.impl");
    }

    private static String packageOf(String code) {
        Matcher m = PACKAGE.matcher(code);
        return m.find() ? m.group(1) : "";
    }
}
