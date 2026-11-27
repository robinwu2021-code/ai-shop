package ai.neargo.commerce.api.mall;

/** Number of shipped units being returned from one original order component. */
public record HardwareRefund(String orderComponentNo, int quantity) {}
