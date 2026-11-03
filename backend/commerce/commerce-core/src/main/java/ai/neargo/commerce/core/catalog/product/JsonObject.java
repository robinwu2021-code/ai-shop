package ai.neargo.commerce.core.catalog.product;

import ai.neargo.commerce.api.catalog.*;

/**
 * Small strict JSON syntax validator; policy remains an opaque versioned contract. No framework
 * dependency.
 */
final class JsonObject {
    private final String text;
    private int at;

    private JsonObject(String text) {
        this.text = text;
    }

    static void validate(String text) {
        try {
            var p = new JsonObject(text);
            p.ws();
            if (p.peek() != '{') throw new IllegalArgumentException();
            p.value(0);
            p.ws();
            if (p.at != text.length()) throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw new CatalogException(
                    CatalogErrorCode.INVALID_ARGUMENT, "refund policy must be a JSON object");
        }
    }

    private char peek() {
        if (at >= text.length()) throw new IllegalArgumentException();
        return text.charAt(at);
    }

    private void ws() {
        while (at < text.length() && " \n\r\t".indexOf(text.charAt(at)) >= 0) at++;
    }

    private boolean eat(char c) {
        ws();
        if (at < text.length() && text.charAt(at) == c) {
            at++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!eat(c)) throw new IllegalArgumentException();
    }

    private void value(int depth) {
        if (depth > 64) throw new IllegalArgumentException();
        ws();
        char c = peek();
        if (c == '{') {
            at++;
            if (eat('}')) return;
            do {
                ws();
                string();
                expect(':');
                value(depth + 1);
            } while (eat(','));
            expect('}');
        } else if (c == '[') {
            at++;
            if (eat(']')) return;
            do {
                value(depth + 1);
            } while (eat(','));
            expect(']');
        } else if (c == '"') string();
        else if (c == 't') literal("true");
        else if (c == 'f') literal("false");
        else if (c == 'n') literal("null");
        else number();
    }

    private void literal(String s) {
        if (!text.startsWith(s, at)) throw new IllegalArgumentException();
        at += s.length();
    }

    private void string() {
        if (peek() != '"') throw new IllegalArgumentException();
        at++;
        while (true) {
            char c = peek();
            at++;
            if (c == '"') return;
            if (c < 32) throw new IllegalArgumentException();
            if (c == '\\') {
                c = peek();
                at++;
                if (c == 'u') {
                    for (int i = 0; i < 4; i++) {
                        if (Character.digit(peek(), 16) < 0) throw new IllegalArgumentException();
                        at++;
                    }
                } else if ("\"\\/bfnrt".indexOf(c) < 0) throw new IllegalArgumentException();
            }
        }
    }

    private void number() {
        int start = at;
        if (peek() == '-') at++;
        if (peek() == '0') at++;
        else {
            if (peek() < '1' || peek() > '9') throw new IllegalArgumentException();
            digits();
        }
        if (at < text.length() && text.charAt(at) == '.') {
            at++;
            int before = at;
            digits();
            if (at == before) throw new IllegalArgumentException();
        }
        if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
            at++;
            if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) at++;
            int before = at;
            digits();
            if (at == before) throw new IllegalArgumentException();
        }
        if (start == at) throw new IllegalArgumentException();
    }

    private void digits() {
        while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
    }
}
