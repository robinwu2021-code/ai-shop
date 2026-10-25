package ai.neargo.shop.printing.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.printing.PrintPort.Document;
import ai.neargo.shop.spi.printing.PrintPort.Scope;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-only allowlist prevents template execution and printer command injection from source names.
 */
public final class PrintDocumentPolicy {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(document|reprint)}}");
    private static final Set<String> SCENES =
            Set.of(
                    "ORDER",
                    "KITCHEN",
                    "PREBILL",
                    "RETURN",
                    "KITCHEN_CHANGE",
                    "TABLE_CHANGE",
                    "LABEL");

    private PrintDocumentPolicy() {}

    public static void scope(Scope value) {
        if (value == null) {
            invalid();
        }
        key(value.entityNo());
        key(value.storeNo());
        key(value.actorNo());
    }

    public static void key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,32}")) {
            invalid();
        }
    }

    public static void scene(String value) {
        if (!SCENES.contains(value == null ? "" : value)) {
            invalid();
        }
    }

    public static void text(String value, int max) {
        if (value == null
                || value.isBlank()
                || value.length() > max
                || value.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\t' || c == 127)) {
            invalid();
        }
    }

    public static void template(String content) {
        text(content, 16000);
        if (!content.contains("{{document}}")
                || content.replace("{{document}}", "")
                        .replace("{{reprint}}", "")
                        .matches("(?s).*(\\{|\\}|\\$|<|>).*")) {
            invalid();
        }
    }

    public static void document(Document value) {
        if (value == null
                || value.schemaVersion() != 1
                || value.lines() == null
                || value.lines().isEmpty()
                || value.lines().size() > 500) {
            invalid();
        }
        scene(value.scene());
        key(value.sourceRef());
        text(value.title(), 128);
        text(value.notice(), 512);
        if (value.currency() != null) {
            try {
                if (!value.currency().matches("[A-Z]{3}")
                        || Currency.getInstance(value.currency()).getDefaultFractionDigits() < 0) {
                    invalid();
                }
            } catch (IllegalArgumentException failure) {
                invalid();
            }
        }
        for (var line : value.lines()) {
            if (line == null) {
                invalid();
            }
            text(line.label(), 256);
            text(line.quantity(), 32);
            if (line.amountMinor() != null
                    && (line.amountMinor() < 0 || value.currency() == null)) {
                invalid();
            }
            if (line.note() != null) {
                text(line.note(), 512);
            }
        }
    }

    public static String render(
            Document document, String template, String original, String reason) {
        document(document);
        template(template);
        var out =
                new StringBuilder(document.title())
                        .append('\n')
                        .append(document.sourceRef())
                        .append('\n');
        for (var line : document.lines()) {
            out.append(line.label()).append(" x ").append(line.quantity());
            if (line.amountMinor() != null) {
                out.append(" ")
                        .append(document.currency())
                        .append(" ")
                        .append(
                                BigDecimal.valueOf(
                                                line.amountMinor(),
                                                Currency.getInstance(document.currency())
                                                        .getDefaultFractionDigits())
                                        .toPlainString());
            }
            if (line.note() != null) {
                out.append(" ").append(line.note());
            }
            out.append('\n');
        }
        out.append(document.notice());
        String marker = original == null ? "" : "REPRINT / 补打 " + original + " / " + reason + "\n";
        String rendered =
                PLACEHOLDER
                        .matcher(template)
                        .replaceAll(
                                match ->
                                        Matcher.quoteReplacement(
                                                "document".equals(match.group(1))
                                                        ? out.toString()
                                                        : marker));
        return original != null && !template.contains("{{reprint}}") ? marker + rendered : rendered;
    }

    private static void invalid() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
