package ai.neargo.shop.build;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Prevent bounded JVM lifetimes from silently reducing or multiplying suite coverage. */
class SurefirePartitionCoverageTest {
    private static final String PROFILE = "/project/profiles/profile[id='bounded-test-batches']";

    @Test
    void everyRepositoryTestBelongsToExactlyOneExecution() throws Exception {
        var batches = batchPatterns();
        try (var sources = Files.walk(Path.of("src/test/java"))) {
            var classes = sources.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> Path.of("src/test/java").relativize(path).toString()
                            .replace('\\', '/').replaceAll("\\.java$", ".class"))
                    .toList();
            assertThat(classes).isNotEmpty();
            for (String candidate : classes) {
                assertCoverage(candidate, batches);
            }
        }
    }

    @Test
    void namingConventionsBoundariesAndNonAsciiNamesKeepDefaultDiscovery() throws Exception {
        var batches = batchPatterns();
        // Exercise every ASCII initial, lowercase names, nonletters, and all four
        // Surefire defaults independently of the names currently in this repository.
        String initials = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_019é中";
        for (int i = 0; i < initials.length(); i++) {
            String initial = initials.substring(i, i + 1);
            for (String name : List.of(initial + "ExampleTest", initial + "ExampleTests",
                    initial + "ExampleTestCase", "Test" + initial + "Example",
                    initial + "ExampleIT", initial + "TestHelper", initial + "Test.java")) {
                assertCoverage(name + ".class", batches);
                assertCoverage("some/TestPackage/deep/" + name + ".class", batches);
                assertCoverage("some/" + name + "$NestedTest.class", batches);
            }
        }
    }

    @Test
    void focusedInvocationsHaveOnlyTheUsualExecutionAndPreserveInheritedFilters() throws Exception {
        Document pom = pom();
        assertThat(value(pom, PROFILE + "/activation/property/name")).isEqualTo("!test");
        assertThat(nodes(pom, "/project/build/plugins/plugin[artifactId='maven-surefire-plugin']/executions/execution")
                .getLength()).isZero();
        var executions = nodes(pom, PROFILE + "/build/plugins/plugin[artifactId='maven-surefire-plugin']/executions/execution");
        int defaultExecutions = 0;
        for (int i = 0; i < executions.getLength(); i++) {
            Element execution = (Element) executions.item(i);
            if (value(execution, "id").equals("default-test")) defaultExecutions++;
            assertThat(value(execution, "phase")).isEqualTo("test");
            assertThat(value(execution, "goals/goal")).isEqualTo("test");
            // Inherit fork settings, group/e2e filters and food.build.shape intact.
            assertThat(nodes(execution, "configuration/*[name() != 'includes']").getLength()).isZero();
        }
        assertThat(defaultExecutions).isOne();
        assertThat(nodes(pom, PROFILE + "//excludes | " + PROFILE + "//skip | " + PROFILE + "//skipTests")
                .getLength()).isZero();
    }

    private static void assertCoverage(String candidate, List<Pattern> batches) {
        String name = candidate.substring(candidate.lastIndexOf('/') + 1);
        // Surefire excludes nested classes by default, before applying its naming defaults.
        boolean discovered = !name.contains("$") && name.endsWith(".class")
                && (name.startsWith("Test") || name.endsWith("Test.class")
                || name.endsWith("Tests.class") || name.endsWith("TestCase.class"));
        long matches = candidate.contains("$") ? 0
                : batches.stream().filter(pattern -> pattern.matcher(candidate).matches()).count();
        assertThat(matches).as("default Surefire coverage of %s", candidate)
                .isEqualTo(discovered ? 1L : 0L);
    }

    private static List<Pattern> batchPatterns() throws Exception {
        var includes = nodes(pom(), PROFILE + "/build/plugins/plugin[artifactId='maven-surefire-plugin']"
                + "/executions/execution/configuration/includes/include");
        List<Pattern> patterns = new ArrayList<>();
        assertThat(includes.getLength()).isGreaterThan(1);
        for (int i = 0; i < includes.getLength(); i++) {
            String include = includes.item(i).getTextContent().trim();
            assertThat(include).startsWith("%regex[").endsWith("]");
            patterns.add(Pattern.compile(include.substring(7, include.length() - 1)));
        }
        return patterns;
    }

    private static Document pom() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
    }

    private static NodeList nodes(Object node, String expression) throws Exception {
        return (NodeList) XPathFactory.newInstance().newXPath().evaluate(expression, node, XPathConstants.NODESET);
    }

    private static String value(Object node, String expression) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(expression, node).trim();
    }
}
