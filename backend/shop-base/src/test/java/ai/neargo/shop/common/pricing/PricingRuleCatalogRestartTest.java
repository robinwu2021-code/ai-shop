package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

class PricingRuleCatalogRestartTest {
    @Test
    void compiledCatalogHasOneDigestAcrossFreshJvmProcesses() throws Exception {
        var hashes = new HashSet<String>();
        for (int i = 0; i < 4; i++) {
            var process =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-Xmx128m",
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    Probe.class.getName())
                            .redirectErrorStream(true)
                            .start();
            try {
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).isZero();
                String hash =
                        new String(
                                        process.getInputStream().readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8)
                                .trim();
                assertThat(hash).matches("[a-f0-9]{64}");
                hashes.add(hash);
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        }
        assertThat(hashes)
                .as("Identical mandatory rules must not invalidate publications after restart")
                .hasSize(1);
    }

    @Test
    void compiledEnumCollectionsRemainImmutableAndAcceptEmptySets() {
        var parameters =
                new RuleDefinition.ForbiddenCombination(
                        Set.of(), Set.of(RuleDefinition.BenefitKind.POINTS), false);
        assertThat(parameters.leftKinds()).isEmpty();
        assertThatThrownBy(() -> parameters.rightKinds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    public static class Probe {
        public static void main(String[] args) {
            System.out.println(
                    PricingSnapshotHash.of(
                            java.util.stream.IntStream.rangeClosed(2, 3)
                                    .mapToObj(PricingRuleCatalog::rulesForSchema)
                                    .toList()));
        }
    }
}
