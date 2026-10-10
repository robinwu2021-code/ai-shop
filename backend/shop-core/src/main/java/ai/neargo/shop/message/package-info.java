/**
 * 订阅消息 · 推送 · 站内信 · 客服工单。
 *
 * <p><b>模块边界</b>（TDD-backend §4.2）：本模块不得依赖任何其它 {@code shop-svc-*}。
 * 需要别的域的数据时，走 {@code shop-spi} 的 Port（同步）或 Event（异步），由 ArchUnit 强制。
 *
 * <p>分层：{@code entity/ mapper/ service/ dto/}；Controller 一律放 {@code shop-app/portal/**}。
 */
package ai.neargo.shop.message;
