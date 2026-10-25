package ai.neargo.shop.printing.center.template;

import ai.neargo.shop.print.api.PrintFailure;

import tools.jackson.databind.JsonNode;

import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** V2 presentation only; source values remain in the frozen document snapshot. */
final class CompactReceiptRows {
    static final Set<String> TRACE =
            Set.of("source.factNo", "source.revision", "system.generatedAt", "system.ticketNo");

    private CompactReceiptRows() {}

    static boolean enabled(JsonNode content) {
        return content.path("layout").path("style").asText().equals("COMPACT_V2");
    }

    static boolean snapshotOnly(String scene, String path) {
        return TRACE.contains(path)
                || (scene.equals("ORDER_PAID")
                        && Set.of("source.no", "source.occurredAt", "fields.orderCreatedAt")
                                .contains(path));
    }

    static List<Map<String, Object>> build(JsonNode data, JsonNode content, String notice) {
        verifyCurrencies(data, data.path("currency").asText());
        var rows = new ArrayList<Map<String, Object>>();
        boolean money = false;
        for (var b : content.path("blocks")) {
            String type = b.path("type").asText(), binding = b.path("binding").asText();
            if (type.equals("systemMarks")) continue;
            if (type.equals("separator")) {
                rows.add(Map.of("type", "rule"));
                continue;
            }
            var value = at(data, binding);
            if (b.path("omitIfEmpty").asBoolean() && empty(value)) continue;
            if (binding.equals("fields.freightAmount")
                    && b.path("omitIfZero").asBoolean()
                    && new BigInteger(value.path("minor").asText()).signum() == 0) continue;
            if (type.equals("lines") || type.equals("lineItems")) {
                for (var item : value) {
                    String name = plain(item.path("title")), variant = plain(item.path("variant"));
                    if (!variant.isBlank()) name += " " + variant;
                    String qty = plain(item.path("quantity")) + plain(item.path("unit"));
                    String amount = "";
                    for (var f : b.path("fields")) {
                        String key = f.asText();
                        if (key.endsWith("Amount")) {
                            amount = money(item.path(key));
                            money = true;
                        }
                    }
                    if (content.path("layout").path("paperWidthMm").asInt() == 58) {
                        rows.add(row(name, "", 10, "left"));
                        rows.add(
                                amount.isEmpty()
                                        ? row("  " + qty, amount, 10, "left")
                                        : moneyRow("  " + qty, amount, 10));
                    } else
                        rows.add(
                                amount.isEmpty()
                                        ? row(name + "  " + qty, amount, 10, "left")
                                        : moneyRow(name + "  " + qty, amount, 10));
                    for (var f : b.path("fields")) {
                        String key = f.asText();
                        if (!Set.of("lineRef", "title", "variant", "quantity", "unit").contains(key)
                                && !key.endsWith("Amount")
                                && !empty(item.path(key)))
                            rows.add(row("  " + plain(item.path(key)), "", 10, "left"));
                    }
                }
                continue;
            }
            if (type.equals("payments")) {
                if (!value.isArray()) throw new PrintFailure("print.invalidField");
                for (var payment : value) {
                    String label =
                            !empty(payment.path("methodLabel"))
                                    ? plain(payment.path("methodLabel"))
                                    : !empty(payment.path("label"))
                                            ? plain(payment.path("label"))
                                            : plain(payment.path("method"));
                    rows.add(
                            moneyRow(
                                    label,
                                    money(
                                            payment.has("receivedAmount")
                                                    ? payment.path("receivedAmount")
                                                    : payment.path("amount")),
                                    10));
                    money = true;
                }
                continue;
            }
            String text;
            if (b.has("literal")) {
                text = b.path("literal").asText();
                if (text.equals(notice)) continue;
            } else if (b.path("format").asText().equals("money")) {
                text = money(value);
                money = true;
            } else if (b.path("format").asText().equals("dateTimeAtStore")) {
                try {
                    text =
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                    .withZone(
                                            ZoneId.of(data.path("store").path("timezone").asText()))
                                    .format(Instant.parse(value.asText()));
                } catch (Exception e) {
                    throw new PrintFailure("print.invalidDate");
                }
            } else text = plain(value);
            boolean emph = b.path("emphasis").asBoolean();
            if (type.equals("keyValue"))
                rows.add(
                        b.path("format").asText().equals("money")
                                ? moneyRow(b.path("label").asText(), text, emph ? 12 : 10)
                                : row(b.path("label").asText(), text, emph ? 12 : 10, "left"));
            else
                rows.add(
                        row(
                                text,
                                "",
                                binding.equals("store.name") ? 12 : emph ? 12 : 10,
                                b.path("align").asText("left")));
        }
        if (money) {
            String currency = data.path("currency").asText();
            if (currency.isBlank()) throw new PrintFailure("print.invalidMoney");
            rows.add(row("金额单位：" + (currency.equals("CNY") ? "元（人民币）" : currency), "", 8, "left"));
        }
        return rows;
    }

    private static Map<String, Object> row(String left, String right, int size, String align) {
        return Map.of("text", left, "right", right, "size", size, "align", align);
    }

    private static Map<String, Object> moneyRow(String left, String right, int size) {
        var result = new HashMap<String, Object>(row(left, right, size, "left"));
        result.put("atomicRight", true);
        return result;
    }

    private static JsonNode at(JsonNode n, String path) {
        for (String p : path.split("\\.")) n = n.path(p);
        return n;
    }

    private static boolean empty(JsonNode n) {
        return n.isMissingNode()
                || n.isNull()
                || (n.isTextual() && n.asText().isBlank())
                || (n.isArray() && n.isEmpty());
    }

    private static String plain(JsonNode n) {
        if (empty(n)) return "";
        if ((n.isObject() || n.isArray())) throw new PrintFailure("print.invalidField");
        String v = n.asText();
        if (v.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n'))
            throw new PrintFailure("print.controlCharacter");
        return v;
    }

    private static void verifyCurrencies(JsonNode n, String currency) {
        if (n.isObject()
                && n.has("minor")
                && n.has("currency")
                && !n.path("currency").asText().equals(currency))
            throw new PrintFailure("print.currencyMismatch");
        if ((n.isObject() || n.isArray())) for (var child : n) verifyCurrencies(child, currency);
    }

    private static String money(JsonNode n) {
        if (!n.path("minor").asText().matches("-?[0-9]{1,30}")
                || !n.path("currency").asText().matches("[A-Z]{3}")
                || n.path("scale").asInt(-1) < 0
                || n.path("scale").asInt() > 6) throw new PrintFailure("print.invalidMoney");
        return new BigDecimal(new BigInteger(n.path("minor").asText()), n.path("scale").asInt())
                .toPlainString();
    }
}
