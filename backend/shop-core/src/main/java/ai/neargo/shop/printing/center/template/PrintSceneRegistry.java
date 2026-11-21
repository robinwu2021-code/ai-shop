package ai.neargo.shop.printing.center.template;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.printing.center.support.PrintJsonCodec;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import java.util.*;

@Component
public final class PrintSceneRegistry {
    public record SceneDefinition(
            String code, JsonNode definition, JsonNode example, Map<String, JsonNode> templates) {}

    private final Map<String, SceneDefinition> scenes;

    public PrintSceneRegistry(PrintJsonCodec codec) {
        var catalog = load(codec, "print-scene-catalog.json").path("scenes");
        var templates = load(codec, "print-template-definitions.json").path("templates");
        var examples = load(codec, "print-scene-examples.json").path("examples");
        var map = new LinkedHashMap<String, SceneDefinition>();
        for (var scene : catalog) {
            String code = scene.path("scene").asText();
            var variants = new HashMap<String, JsonNode>();
            for (var t : templates)
                if (code.equals(t.path("scene").asText()))
                    variants.put(t.path("variantCode").asText(), t);
            JsonNode ex = null;
            for (var e : examples) if (code.equals(e.path("scene").asText())) ex = e;
            map.put(code, new SceneDefinition(code, scene, ex, Map.copyOf(variants)));
        }
        scenes = Map.copyOf(map);
    }

    private JsonNode load(PrintJsonCodec c, String name) {
        try (var in = getClass().getResourceAsStream("/print/" + name)) {
            if (in == null) throw new IllegalStateException(name);
            return c.tree(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public SceneDefinition require(String scene) {
        var s = scenes.get(scene);
        if (s == null) throw new PrintFailure("print.sceneUnavailable");
        return s;
    }

    /**
     * 场景是否允许「加印」（EXTRA，另出一份同样的件）。法定凭证（发票证明联）正本只能有一份，
     * 再出必须走补印并标明 —— 由场景目录的 {@code extraCopiesAllowed: false} 声明，默认允许。
     */
    public boolean extraCopiesAllowed(String scene) {
        return require(scene).definition().path("extraCopiesAllowed").asBoolean(true);
    }

    public Collection<SceneDefinition> all() {
        return scenes.values();
    }

    public void validateSource(PrintFact fact) {
        var s = require(fact.ref().scene());
        boolean valid = false;
        for (var t : s.definition().path("sourceTypes"))
            if (t.asText().equals(fact.ref().sourceType())) valid = true;
        if (!valid
                || fact.payloadSchemaVersion()
                        != s.definition().path("payloadSchemaVersion").asInt())
            throw new PrintFailure("print.sourceMismatch");
    }
}
