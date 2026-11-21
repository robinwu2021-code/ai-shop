package ai.neargo.shop.invoice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 发票域不认识任何行业与交易域（PRD AC13，ADR-063）：行业事实只经 spi.invoice 进来，证明联只经 print-api 出去。
 * pom 里没有 shop-core 依赖是编译期的那道闸；这道看住「有人顺手加了依赖再 import」。
 */
class InvoiceBoundaryTest {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "^import\\s+(static\\s+)?ai\\.neargo\\.shop\\.(trade|food|beauty|printing|merchant|pay|inventory"
                    + "|marketing|promotion|member|logistics)\\.", Pattern.MULTILINE);

    @Test
    void invoiceDomainImportsNoIndustryOrTradeCode() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return FORBIDDEN.matcher(Files.readString(p)).find();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).map(Path::toString).toList();
        }
        assertEquals(List.of(), offenders);
    }
}
