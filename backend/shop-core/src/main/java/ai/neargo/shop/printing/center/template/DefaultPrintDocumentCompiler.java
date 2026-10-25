package ai.neargo.shop.printing.center.template;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.printing.center.support.PrintJsonCodec;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public final class DefaultPrintDocumentCompiler implements PrintDocumentCompiler {
    private final PrintJsonCodec json;
    private final PrintSceneRegistry scenes;
    private static final Set<String> BLOCKS =
            Set.of(
                    "systemMarks",
                    "text",
                    "keyValue",
                    "lines",
                    "lineItems",
                    "payments",
                    "separator");

    public DefaultPrintDocumentCompiler(PrintJsonCodec j, PrintSceneRegistry s) {
        json = j;
        scenes = s;
    }

    public PrintDocument compile(
            PrintFact fact, ResolvedTemplate template, SystemPrintSlots slots) {
        scenes.validateSource(fact);
        validateTemplate(fact.ref().scene(), template.content().canonicalJson());
        if (fact.printability() != Printability.READY)
            throw new PrintFailure(
                    fact.blockCode() == null ? "print.sourceBlocked" : fact.blockCode());
        if (!fact.ref().scene().equals(template.scene())
                || fact.payloadSchemaVersion() != template.payloadSchemaVersion())
            throw new PrintFailure("print.templateMismatch");
        var data = (ObjectNode) json.tree(fact.immutablePayload()).deepCopy();
        if (fact.triggerType().equals("SAMPLE") && data.path("system").has("originalTicketState")) {
            var original =
                    json.read(
                            data.path("system").path("originalTicketState").toString(),
                            OriginalTicketState.class);
            var warnings = new HashSet<String>(slots.warnings());
            if (original.status() == PrintJobStatus.UNKNOWN) warnings.add("原票结果待核实");
            slots =
                    new SystemPrintSlots(
                            slots.ticketNo(),
                            slots.jobKind(),
                            slots.copyIndex(),
                            slots.generatedAt(),
                            original.jobNo(),
                            slots.reason(),
                            warnings,
                            original);
        }
        if (Set.of("KITCHEN_VOID", "URGE").contains(fact.ref().scene())
                && slots.originalTicketState() == null)
            throw new PrintFailure("print.originalTicketEvidenceRequired");
        data.set("system", json.tree(json.freeze(slots)));
        var scene = scenes.require(fact.ref().scene());
        for (var f : scene.definition().path("requiredFields")) {
            var val = at(data, f.path("path").asText());
            if (f.path("required").asBoolean() && empty(val))
                throw new PrintFailure("print.requiredFieldMissing");
            if (val.isArray()
                    && ((f.has("maxItems") && val.size() > f.path("maxItems").asInt())
                            || (f.has("minItems") && val.size() < f.path("minItems").asInt())))
                throw new PrintFailure("print.lineLimit");
        }
        for (var path :
                java.util.List.of(
                        "store.name",
                        "store.timezone",
                        "source.type",
                        "source.no",
                        "source.factNo",
                        "source.revision",
                        "source.occurredAt"))
            if (empty(at(data, path))) throw new PrintFailure("print.requiredFieldMissing");
        var content = json.tree(template.content());
        boolean compact = CompactReceiptRows.enabled(content);
        var rows = new ArrayList<Map<String, Object>>();
        String notice = scene.definition().path("mandatoryNotice").asText();
        rows.add(Map.of("text", notice, "size", 12));
        for (var block : json.tree(template.content()).path("blocks")) {
            String type = block.path("type").asText();
            if (block.path("literal").asText().length() > 4096
                    || block.path("label").asText().length() > 128)
                throw new PrintFailure("print.templateTextTooLong");
            if (!BLOCKS.contains(type)) throw new PrintFailure("print.templateBlockRejected");
            if (type.equals("systemMarks")) continue;
            if (type.equals("separator")) {
                rows.add(Map.of("text", "------------------------", "size", 9));
                continue;
            }
            String binding = block.path("binding").asText();
            JsonNode value = binding.isEmpty() ? null : at(data, binding);
            if (!binding.isEmpty() && !allowedBinding(scene.definition(), binding))
                throw new PrintFailure("print.bindingRejected");
            if (block.path("omitIfEmpty").asBoolean() && empty(value)) continue;
            if (type.equals("lines") || type.equals("lineItems") || type.equals("payments")) {
                if (value == null
                        || !value.isArray()
                        || value.size() > block.path("maxItems").asInt(500))
                    throw new PrintFailure("print.lineLimit");
                for (var line : value) {
                    var cells = new ArrayList<String>();
                    if (type.equals("lines") || type.equals("lineItems")) {
                        for (var f : block.path("fields"))
                            cells.add(
                                    format(
                                            line.path(f.asText()),
                                            f.asText().endsWith("Amount") ? "money" : "plain",
                                            data));
                    } else {
                        for (var key : List.of("label", "method", "amount"))
                            if (line.has(key))
                                cells.add(
                                        format(
                                                line.path(key),
                                                key.equals("amount") ? "money" : "plain",
                                                data));
                    }
                    rows.add(Map.of("text", String.join("  ", cells), "size", 10));
                }
            } else {
                String text =
                        block.has("literal")
                                ? block.path("literal").asText()
                                : format(value, block.path("format").asText("plain"), data);
                if (type.equals("keyValue")) text = block.path("label").asText() + ": " + text;
                rows.add(
                        Map.of("text", text, "size", block.path("emphasis").asBoolean() ? 12 : 10));
            }
        }
        if (compact) rows = new ArrayList<>(CompactReceiptRows.build(data, content, notice));
        var scopes = new HashSet<String>();
        if (!Set.of("SAMPLE", "DEVICE_TEST").contains(fact.triggerType()))
            scopes.addAll(PrintSourceDataScopes.required(fact.ref().sourceType()));
        for (var f : scene.definition().path("requiredDataScopes")) scopes.add(f.asText());
        return new PrintDocument(
                fact.ref().factNo() + ":" + fact.ref().scene(),
                fact.ref().scene(),
                fact.payloadSchemaVersion(),
                json.freeze(
                        compact
                                ? Map.of(
                                        "rows",
                                        rows,
                                        "mandatoryNotice",
                                        notice,
                                        "layoutStyle",
                                        "COMPACT_V2",
                                        "sourceSnapshot",
                                        data,
                                        "timezone",
                                        data.path("store").path("timezone").asText())
                                : Map.of("rows", rows, "mandatoryNotice", notice)),
                slots,
                scopes);
    }

    public void validateTemplate(String scene, String content) {
        var def = scenes.require(scene).definition();
        var n = json.tree(content);
        if (!n.path("blocks").isArray() || n.path("blocks").size() > 200)
            throw new PrintFailure("print.invalidTemplate");
        var displayed = new HashSet<String>();
        boolean systemMarks = false;
        var fieldTypes = new HashMap<String, String>();
        for (String collection : List.of("requiredFields", "optionalFields"))
            for (var field : def.path(collection))
                fieldTypes.put(field.path("path").asText(), field.path("type").asText());
        for (var b : n.path("blocks")) {
            String type = b.path("type").asText(),
                    binding = b.path("binding").asText(),
                    format = b.path("format").asText("plain");
            boolean bound = !binding.isBlank();
            if (b.path("omitIfZero").asBoolean()
                    && !(CompactReceiptRows.enabled(n) && binding.equals("fields.freightAmount")))
                throw new PrintFailure("print.unsafeZeroSuppression");
            if (!BLOCKS.contains(type) || (bound && !allowedBinding(def, binding)))
                throw new PrintFailure("print.bindingRejected");
            if (!Set.of("plain", "money", "dateTimeAtStore").contains(format))
                throw new PrintFailure("print.templateFormat");
            if (type.equals("systemMarks") || type.equals("separator")) {
                if (bound || b.has("literal") || b.has("fields"))
                    throw new PrintFailure("print.nonRenderingBindingRejected");
                systemMarks |= type.equals("systemMarks");
                continue;
            }
            if (bound && b.has("literal")) throw new PrintFailure("print.boundLiteralRejected");
            if (type.equals("text")) {
                if (bound == b.has("literal"))
                    throw new PrintFailure("print.templateBindingRequired");
            } else if (!bound) throw new PrintFailure("print.templateBindingRequired");
            if (type.equals("lines") || type.equals("lineItems")) {
                if (!binding.equals("lines")
                        || !b.path("fields").isArray()
                        || b.path("fields").isEmpty())
                    throw new PrintFailure("print.bindingRejected");
                var fields = new HashSet<String>();
                b.path("fields").forEach(f -> fields.add(f.asText()));
                var allowed = new HashSet<String>();
                def.path("lineFields").forEach(f -> allowed.add(f.asText()));
                if (!allowed.equals(fields))
                    throw new PrintFailure("print.mandatoryLineFieldMissing");
            } else if (type.equals("payments")) {
                if (!binding.equals("payments")) throw new PrintFailure("print.bindingRejected");
            } else if (binding.equals("lines") || binding.equals("payments"))
                throw new PrintFailure("print.bindingRejected");
            if ("Money".equals(fieldTypes.get(binding)) && !format.equals("money"))
                throw new PrintFailure("print.moneyFormatRequired");
            if ("DateTime".equals(fieldTypes.get(binding)) && !format.equals("dateTimeAtStore"))
                throw new PrintFailure("print.dateFormatRequired");
            if (bound) displayed.add(binding);
        }
        if (!systemMarks) throw new PrintFailure("print.systemMarksRequired");
        for (var required : def.path("mandatoryPrintPaths")) {
            String path = required.asText();
            if (Set.of(
                            "system.ticketNo",
                            "system.jobKind",
                            "system.copyIndex",
                            "system.originalTicketState",
                            "currency")
                    .contains(path)) continue;
            if (CompactReceiptRows.enabled(n) && CompactReceiptRows.snapshotOnly(scene, path))
                continue;
            if (!displayed.contains(path))
                throw new PrintFailure("print.mandatoryPrintFieldMissing");
        }
    }

    private boolean allowedBinding(JsonNode scene, String path) {
        if (Set.of(
                        "store.name",
                        "store.timezone",
                        "store.address",
                        "store.phone",
                        "source.type",
                        "source.no",
                        "source.factNo",
                        "source.revision",
                        "source.occurredAt",
                        "system.ticketNo",
                        "system.jobKind",
                        "system.copyIndex",
                        "system.generatedAt",
                        "system.warnings",
                        "lines",
                        "payments")
                .contains(path)) return true;
        for (var f : scene.path("requiredFields"))
            if (path.equals(f.path("path").asText())) return true;
        for (var f : scene.path("optionalFields"))
            if (path.equals(f.path("path").asText())) return true;
        return false;
    }

    private JsonNode at(JsonNode n, String path) {
        for (String p : path.split("\\.")) n = n.path(p);
        return n;
    }

    private boolean empty(JsonNode n) {
        return n == null
                || n.isMissingNode()
                || n.isNull()
                || (n.isTextual() && n.asText().isBlank())
                || (n.isArray() && n.isEmpty());
    }

    private String format(JsonNode n, String format, JsonNode payload) {
        if (empty(n)) return "";
        if (format.equals("money")) {
            if (!n.path("minor").asText().matches("-?[0-9]{1,30}"))
                throw new PrintFailure("print.invalidMoney");
            return new BigDecimal(new BigInteger(n.path("minor").asText()), n.path("scale").asInt())
                            .toPlainString()
                    + " "
                    + n.path("currency").asText();
        }
        if (format.equals("dateTimeAtStore")) {
            try {
                return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(ZoneId.of(payload.path("store").path("timezone").asText()))
                        .format(Instant.parse(n.asText()));
            } catch (Exception e) {
                throw new PrintFailure("print.invalidDate");
            }
        }
        if ((n.isObject() || n.isArray())) throw new PrintFailure("print.invalidField");
        String v = n.asText();
        if (v.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n'))
            throw new PrintFailure("print.controlCharacter");
        return v;
    }
}
