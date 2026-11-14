package ai.neargo.inventory.guard;

import ai.neargo.inventory.api.InventoryException;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 统一错误体 {@code {code, message, data}}（TDD §2.13）。过滤器里没有控制器的异常处理可用，在这里直接写。
 */
public final class ErrorBodies {

    private ErrorBodies() {
    }

    public static void write(HttpServletResponse response, InventoryException e) throws IOException {
        response.setStatus(e.error().httpStatus());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body(e));
    }

    public static String body(InventoryException e) {
        StringBuilder args = new StringBuilder("[");
        Object[] values = e.args() == null ? new Object[0] : e.args();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                args.append(',');
            }
            args.append(quote(String.valueOf(values[i])));
        }
        args.append(']');
        return "{\"code\":" + quote(e.error().key()) + ",\"message\":" + quote(e.error().name())
                + ",\"data\":{\"args\":" + args + "}}";
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
