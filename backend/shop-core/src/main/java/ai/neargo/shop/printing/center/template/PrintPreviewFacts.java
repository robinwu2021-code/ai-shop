package ai.neargo.shop.printing.center.template;

import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.printing.center.source.PrintSourceRegistry;
import ai.neargo.shop.printing.center.support.PrintJsonCodec;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Preview shares source authorization and immutable facts with production, without creating jobs.
 */
@Component
public final class PrintPreviewFacts {
    private final PrintSceneRegistry scenes;
    private final PrintSourceRegistry sources;
    private final PrintJsonCodec json;

    public PrintPreviewFacts(
            PrintSceneRegistry scenes, PrintSourceRegistry sources, PrintJsonCodec json) {
        this.scenes = scenes;
        this.sources = sources;
        this.json = json;
    }

    public PrintFact get(String scene, PrintSourceReference reference, PrintActor actor) {
        if (reference == null) {
            var definition = scenes.require(scene);
            var payload = json.freeze(definition.example());
            var ref =
                    new PrintFactRef(
                            actor.tenantNo(),
                            actor.entityNo(),
                            actor.storeNo(),
                            definition.definition().path("sourceTypes").get(0).asText(),
                            "SAMPLE",
                            "1",
                            scene,
                            payload.sha256());
            return new PrintFact(
                    ref,
                    null,
                    null,
                    "SAMPLE",
                    "SAMPLE",
                    Instant.EPOCH,
                    1,
                    payload,
                    Printability.READY,
                    null,
                    List.of());
        }
        require(actor.dataScopes().contains("SOURCE_ORDER_READ"), "print.forbidden");
        require(scene.equals(reference.scene()), "print.sourceMismatch");
        var port = sources.require(reference.type());
        var ref = port.resolveReference(reference, scope(actor));
        var fact = port.snapshot(ref);
        access(
                new PrintScope(ref.tenantNo(), ref.entityNo(), ref.storeNo()),
                actor,
                Set.of("SOURCE_ORDER_READ"));
        require(fact.printability() == Printability.READY, "print.sourceBlocked");
        scenes.validateSource(fact);
        return fact;
    }
}
