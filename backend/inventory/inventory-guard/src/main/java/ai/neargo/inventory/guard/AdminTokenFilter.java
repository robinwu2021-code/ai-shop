package ai.neargo.inventory.guard;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 平台运维入口 {@code /api/inventory/admin/v1/**}：开通业主、签发凭证、改档案、翻转写入方。
 *
 * <p>每套部署一把运维令牌（环境变量 {@code INVENTORY_ADMIN_TOKEN}），宿主拿不到。
 * <b>没配令牌 = 运维入口关闭</b>（一律 404），而不是「没配就不校验」。
 */
public class AdminTokenFilter extends OncePerRequestFilter {

    public static final String PATH_PREFIX = "/api/inventory/admin/v1/";

    private final String token;

    public AdminTokenFilter(String token) {
        this.token = token == null ? "" : token.strip();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (token.isEmpty()) {
            ErrorBodies.write(response, InventoryException.of(InventoryError.NOT_FOUND));
            return;
        }
        String given = request.getHeader("X-Inventory-Admin-Token");
        if (given == null || !MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            ErrorBodies.write(response, InventoryException.of(InventoryError.UNAUTHORIZED));
            return;
        }
        chain.doFilter(request, response);
    }
}
