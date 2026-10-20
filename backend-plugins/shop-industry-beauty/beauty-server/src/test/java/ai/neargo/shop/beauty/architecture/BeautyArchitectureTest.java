package ai.neargo.shop.beauty.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Guards the beauty's real Maven boundary even before business classes exist.
 * Negative fixtures prove that transitive coupling and cycles cannot silently pass.
 */
class BeautyArchitectureTest {

    private static final Set<String> MODULES = Set.of(
            "beauty-api", "beauty-core", "beauty-web", "beauty-channel",
                "beauty-server");
    private static final Set<String> FORBIDDEN_IMPLEMENTATIONS = Set.of(
            "shop-core", "shop-app", "shop-merchant", "shop-inventory", "shop-report",
            "pay-domain", "pay-channel", "pay-svc", "food-api", "food-core", "food-web");

    @Test
    void reactorContainsFiveModulesAndUsesPlatformParent() throws Exception {
        Path root = reactorRoot();
        Element pom = read(root.resolve("pom.xml"));
        assertEquals(MODULES, new HashSet<>(children(child(pom, "modules"), "module").stream()
                .map(Element::getTextContent).map(String::strip).toList()));
        assertEquals(5, children(child(pom, "modules"), "module").size());
        assertEquals("pom", value(pom, "packaging"));
        assertPlatformParent(pom, root);
        for (String name : MODULES) {
            Element module = read(root.resolve(name).resolve("pom.xml"));
            assertEquals(name, value(module, "artifactId"));
            assertEquals("jar", value(module, "packaging"));
            assertPlatformParent(module, root.resolve(name));
            assertTrue(children(child(module, "modules"), "module").isEmpty());
        }
    }

    @Test
    void rejectCoreImplementationDependency() throws Exception {
        assertNoImplementations(dependencies());
    }

    @Test
    void rejectWebDependingOnCore() throws Exception {
        assertUnreachable(dependencies(), "beauty-web", Set.of("beauty-core", "beauty-server"));
    }

    @Test
    void rejectChannelDependingOnWeb() throws Exception {
        assertUnreachable(dependencies(), "beauty-channel", Set.of("beauty-web", "beauty-core", "beauty-server"));
    }

    @Test
    void contractsDoNotDependOnRuntime() throws Exception {
        assertUnreachable(dependencies(), "beauty-api", Set.of(
                "beauty-core", "beauty-web", "beauty-channel",
                "beauty-server", "shop-store-mybatis", "shop-base-auth"));
    }

    @Test
    void moduleGraphHasNoCycles() throws Exception {
        assertAcyclic(dependencies());
    }

    @Test
    void onlyServerConfiguresExecutablePackaging() throws Exception {
        for (String name : MODULES) {
            Element pom = read(reactorRoot().resolve(name).resolve("pom.xml"));
            List<Element> boot = children(child(child(pom, "build"), "plugins"), "plugin").stream()
                    .filter(p -> "spring-boot-maven-plugin".equals(value(p, "artifactId"))).toList();
            if ("beauty-server".equals(name)) {
                assertEquals(1, boot.size());
                assertEquals("ai.neargo.shop.beauty.server.BeautyApplication",
                        value(child(boot.getFirst(), "configuration"), "mainClass"));
                assertEquals("repackage", value(child(child(boot.getFirst(), "executions"), "execution"), "id"));
                assertEquals(List.of("repackage"), children(child(boot.getFirst(), "executions"), "execution")
                        .stream().flatMap(e -> children(child(e, "goals"), "goal").stream())
                        .map(Element::getTextContent).map(String::strip).toList());
            } else {
                assertTrue(boot.isEmpty(), name + " must remain a normal library jar");
            }
        }
    }

    @Test
    void thirdPartyVersionsStayInPlatformParent() throws Exception {
        for (String name : MODULES) {
            Element pom = read(reactorRoot().resolve(name).resolve("pom.xml"));
            assertTrue(children(child(child(pom, "dependencyManagement"), "dependencies"), "dependency").isEmpty());
            for (Element dependency : children(child(pom, "dependencies"), "dependency")) {
                String version = value(dependency, "version");
                if ("ai.neargo.shop".equals(value(dependency, "groupId"))) {
                    assertEquals("${project.version}", version);
                } else {
                    assertEquals("", version, value(dependency, "artifactId") + " pins a third-party version");
                }
            }
        }
    }

    @Test
    void negativeFixtureRejectsBusinessImplementation() {
        assertThrows(AssertionError.class,
                () -> assertNoImplementations(Map.of("beauty-core", Set.of("shop-core"))));
    }

    @Test
    void negativeFixtureRejectsTransitiveWebCoupling() {
        Map<String, Set<String>> graph = Map.of(
                "beauty-web", Set.of("beauty-channel"), "beauty-channel", Set.of("beauty-core"));
        assertThrows(AssertionError.class, () -> assertUnreachable(graph, "beauty-web", Set.of("beauty-core")));
    }

    @Test
    void negativeFixtureRejectsTransitiveChannelCoupling() {
        Map<String, Set<String>> graph = Map.of(
                "beauty-channel", Set.of("beauty-api"), "beauty-api", Set.of("beauty-web"));
        assertThrows(AssertionError.class, () -> assertUnreachable(graph, "beauty-channel", Set.of("beauty-web")));
    }

    @Test
    void negativeFixtureRejectsCycles() {
        assertThrows(AssertionError.class, () -> assertAcyclic(Map.of(
                "beauty-api", Set.of("beauty-channel"), "beauty-channel", Set.of("beauty-api"))));
    }

    @Test
    void runtimeClasspathHasNoRetailOrInventoryImplementations() {
        ClassLoader loader = getClass().getClassLoader();
        for (String type : List.of(
                "ai/neargo/shop/trade/service/OrderService.class",
                "ai/neargo/shop/inventory/service/ReservationService.class",
                "ai/neargo/shop/pay/SettleService.class")) {
            assertEquals(null, loader.getResource(type), type + " leaked into beauty runtime");
        }
    }

    private static Path reactorRoot() {
        return Path.of(System.getProperty("beauty.reactor.root")).toAbsolutePath().normalize();
    }

    private static void assertPlatformParent(Element pom, Path module) throws Exception {
        Element parent = child(pom, "parent");
        assertEquals("ai.neargo.shop", value(parent, "groupId"));
        assertEquals("shop-parent", value(parent, "artifactId"));
        assertEquals("", value(parent, "relativePath"), "independent reactor resolves published platform parent");
        assertEquals("0.1.0-SNAPSHOT", value(parent, "version"));
    }

    private static Map<String, Set<String>> dependencies() throws Exception {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String name : MODULES) {
            Set<String> dependencies = new HashSet<>();
            Element pom = read(reactorRoot().resolve(name).resolve("pom.xml"));
            for (Element dependency : children(child(pom, "dependencies"), "dependency")) {
                if (!"test".equals(value(dependency, "scope"))) {
                    dependencies.add(value(dependency, "artifactId"));
                }
            }
            result.put(name, dependencies);
        }
        return result;
    }

    private static void assertNoImplementations(Map<String, Set<String>> graph) {
        graph.forEach((name, dependencies) -> {
            for (String forbidden : FORBIDDEN_IMPLEMENTATIONS) {
                assertUnreachable(graph, name, Set.of(forbidden));
            }
        });
    }

    private static void assertUnreachable(Map<String, Set<String>> graph, String start, Set<String> forbidden) {
        Set<String> reachable = new HashSet<>();
        collect(graph, start, reachable);
        for (String name : forbidden) {
            assertFalse(reachable.contains(name), start + " depends on forbidden " + name);
        }
    }

    private static void collect(Map<String, Set<String>> graph, String name, Set<String> visited) {
        for (String dependency : graph.getOrDefault(name, Set.of())) {
            if (visited.add(dependency)) {
                collect(graph, dependency, visited);
            }
        }
    }

    private static void assertAcyclic(Map<String, Set<String>> graph) {
        for (String name : graph.keySet()) {
            assertUnreachable(graph, name, Set.of(name));
        }
    }

    private static Element read(Path file) throws Exception {
        assertTrue(Files.isRegularFile(file), "missing beauty POM: " + file);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
    }

    private static Element child(Element parent, String name) {
        List<Element> elements = children(parent, name);
        return elements.isEmpty() ? null : elements.getFirst();
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent != null) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && name.equals(element.getTagName())) {
                    result.add(element);
                }
            }
        }
        return result;
    }

    private static String value(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : element.getTextContent().strip();
    }
}
