package ai.neargo.inventory.guard;

/**
 * 一次调用是谁发起的：哪个系统、动哪本账、哪个人。
 *
 * <p>{@code systemCode} 与 {@code ownerId} 来自凭证，**不信请求体**；{@code actor} 由宿主透传，只记审计、不授权。
 */
public record InventoryPrincipal(String systemCode, String ownerId, String actor) {
}
