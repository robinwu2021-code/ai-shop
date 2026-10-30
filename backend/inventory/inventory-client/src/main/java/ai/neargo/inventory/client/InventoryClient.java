package ai.neargo.inventory.client;

import ai.neargo.inventory.client.InventoryHostApi.Balance;
import ai.neargo.inventory.client.InventoryHostApi.BalancesQuery;
import ai.neargo.inventory.client.InventoryHostApi.Claim;
import ai.neargo.inventory.client.InventoryHostApi.ClaimRequest;
import ai.neargo.inventory.client.InventoryHostApi.Created;
import ai.neargo.inventory.client.InventoryHostApi.InTransit;
import ai.neargo.inventory.client.InventoryHostApi.InboundCreate;
import ai.neargo.inventory.client.InventoryHostApi.ItemSpec;
import ai.neargo.inventory.client.InventoryHostApi.OutboundAppend;
import ai.neargo.inventory.client.InventoryHostApi.OutboundCreate;
import ai.neargo.inventory.client.InventoryHostApi.OwnerBalances;
import ai.neargo.inventory.client.InventoryHostApi.OwnerKey;
import ai.neargo.inventory.client.InventoryHostApi.Reason;
import ai.neargo.inventory.client.InventoryHostApi.Receipt;
import ai.neargo.inventory.client.InventoryHostApi.Receive;
import ai.neargo.inventory.client.InventoryHostApi.Serial;
import ai.neargo.inventory.client.InventoryHostApi.TargetSync;
import ai.neargo.inventory.client.InventoryHostApi.TransferCreate;
import ai.neargo.inventory.client.InventoryHostApi.Warehouse;
import ai.neargo.inventory.client.InventoryHostApi.WarehouseCreate;
import ai.neargo.svc.client.ServiceClientSpec;
import ai.neargo.svc.client.ServiceClients;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 库存服务的统一客户端（TDD §2.23）：电商与 ShareHub 两种宿主、两种凭证用同一个类。
 *
 * <ul>
 *   <li><b>账本</b>：系统凭证（电商）用 {@link #owner(String, String, String)} 指定本系统的哪本账；
 *       账本凭证（ShareHub）用 {@link #self(String)}，账本由凭证决定；</li>
 *   <li><b>幂等</b>：写操作的第一个参数是调用方给的<b>稳定</b>命令键（如「业务单号 + 动作」）。
 *       回包丢失时用同一个键重发或 {@link Session#command(String)} 查结果，服务回放原结果、不重复记账；</li>
 *   <li><b>错误</b>：业务错误原样回 {@link ai.neargo.inventory.api.InventoryException}；
 *       连不上、超时、5xx、熔断中一律 {@code UNAVAILABLE}。不自动重试，与预留共用一个熔断器；</li>
 *   <li><b>标识</b>：物料、库位都可以给宿主编号（{@code itemRef} / {@code locationRef}），不必维护内部号对照表。</li>
 * </ul>
 */
public final class InventoryClient {

    private final InventoryHostApi api;
    private final InventoryReservationClient reservations;

    private InventoryClient(InventoryHostApi api, InventoryReservationClient reservations) {
        this.api = api;
        this.reservations = reservations;
    }

    /**
     * @param configPrefix 宿主自己的配置前缀（如 {@code shop.inventory.service}），只用于报「哪个配置没填」
     */
    public static InventoryClient create(Supplier<Optional<String>> baseUrl, String appKey, String appSecret,
                                         Duration connectTimeout, Duration readTimeout, int breakerThreshold,
                                         Duration breakerOpenFor, String configPrefix) {
        ServiceClientSpec spec = InventoryReservationClient.spec(baseUrl, appKey, appSecret, connectTimeout,
                readTimeout, configPrefix);
        return new InventoryClient(ServiceClients.create(InventoryHostApi.class, spec),
                InventoryReservationClient.create(spec,
                        new CircuitBreaker(breakerThreshold, breakerOpenFor, Clock.systemUTC())));
    }

    /** 测试与自定义传输用 */
    public static InventoryClient of(InventoryHostApi api, InventoryReservationClient reservations) {
        return new InventoryClient(api, reservations);
    }

    /** 系统凭证：本系统里的哪本账。{@code ownerType} 只在同号多本（门店与连锁撞号）时需要 */
    public Session owner(String ownerRef, String ownerType, String actor) {
        return new Session(ownerRef, ownerType, actor);
    }

    /** 账本凭证：账本由凭证决定 */
    public Session self(String actor) {
        return new Session(null, null, actor);
    }

    /** 预留（电商下单）：与本客户端共用连接与熔断 */
    public InventoryReservationClient reservations() {
        return reservations;
    }

    /** 系统级：多账本批量余额（只认系统凭证）；每本账单独判，出错的那一项带 {@code error} */
    public List<OwnerBalances> balancesAcross(List<OwnerKey> owners, Set<String> itemRefs) {
        return call(() -> api.balancesAcross(new BalancesQuery(owners, itemRefs)));
    }

    public boolean breakerOpen() {
        return reservations.breakerOpen();
    }

    private <T> T call(Supplier<T> invocation) {
        return reservations.invoke(invocation);
    }

    /** 一本账上的一个操作人。不可变，可以缓存。 */
    public final class Session {

        private final String ownerRef;
        private final String ownerType;
        private final String actor;

        private Session(String ownerRef, String ownerType, String actor) {
            this.ownerRef = ownerRef;
            this.ownerType = ownerType;
            this.actor = actor;
        }

        // ── 主数据 ────────────────────────────────────────────────

        public String upsertItem(String key, String itemRef, ItemSpec spec) {
            return call(() -> api.upsertItem(ownerRef, ownerType, key, actor, itemRef, spec)).itemId();
        }

        public JsonNode syncTarget(String key, String type, String ref, TargetSync sync) {
            return call(() -> api.syncTarget(ownerRef, ownerType, key, actor, type, ref, sync));
        }

        public List<JsonNode> targets(String type, String parentRef) {
            return call(() -> api.targets(ownerRef, ownerType, type, parentRef));
        }

        public List<Warehouse> warehouses(String holderRef) {
            return call(() -> api.warehouses(ownerRef, ownerType, holderRef));
        }

        public String createWarehouse(String key, WarehouseCreate body) {
            Created created = call(() -> api.createWarehouse(ownerRef, ownerType, key, actor, body));
            return created.locationId();
        }

        // ── 入库 ──────────────────────────────────────────────────

        public String createInbound(String key, InboundCreate body) {
            return call(() -> api.createInbound(ownerRef, ownerType, key, actor, body)).docNo();
        }

        public void postInbound(String key, String inboundNo) {
            call(() -> api.postInbound(ownerRef, ownerType, key, actor, inboundNo));
        }

        public void cancelInbound(String key, String inboundNo) {
            call(() -> api.cancelInbound(ownerRef, ownerType, key, actor, inboundNo));
        }

        /** 成对入库单确认收货：物料可给宿主编号（与发货方同一个） */
        public Receipt receive(String key, String inboundNo, Receive body) {
            return call(() -> api.receive(ownerRef, ownerType, key, actor, inboundNo, body));
        }

        public JsonNode inbound(String inboundNo) {
            return call(() -> api.inbound(ownerRef, ownerType, inboundNo));
        }

        // ── 出库 ──────────────────────────────────────────────────

        public String createOutbound(String key, OutboundCreate body) {
            return call(() -> api.createOutbound(ownerRef, ownerType, key, actor, body)).docNo();
        }

        public void appendOutbound(String key, String outboundNo, OutboundAppend body) {
            call(() -> api.appendOutbound(ownerRef, ownerType, key, actor, outboundNo, body));
        }

        public void postOutbound(String key, String outboundNo) {
            call(() -> api.postOutbound(ownerRef, ownerType, key, actor, outboundNo));
        }

        public void cancelOutbound(String key, String outboundNo) {
            call(() -> api.cancelOutbound(ownerRef, ownerType, key, actor, outboundNo));
        }

        public JsonNode outbound(String outboundNo) {
            return call(() -> api.outbound(ownerRef, ownerType, outboundNo));
        }

        // ── 账内调拨 ──────────────────────────────────────────────

        public String createTransfer(String key, TransferCreate body) {
            return call(() -> api.createTransfer(ownerRef, ownerType, key, actor, body)).docNo();
        }

        public void shipTransfer(String key, String transferNo) {
            call(() -> api.transferAction(ownerRef, ownerType, key, actor, transferNo, "ship"));
        }

        public void receiveTransfer(String key, String transferNo) {
            call(() -> api.transferAction(ownerRef, ownerType, key, actor, transferNo, "receive"));
        }

        public void cancelTransfer(String key, String transferNo) {
            call(() -> api.transferAction(ownerRef, ownerType, key, actor, transferNo, "cancel"));
        }

        public JsonNode transfer(String transferNo) {
            return call(() -> api.transfer(ownerRef, ownerType, transferNo));
        }

        // ── 成对单据、出库池 ─────────────────────────────────────

        /** @param direction {@code OUT} 本账本发出的 / {@code IN} 本账本收的 */
        public List<JsonNode> pairedTransfers(String direction, String status) {
            return call(() -> api.pairedTransfers(ownerRef, ownerType, direction, status));
        }

        public List<JsonNode> dispatchPool(List<String> status, List<String> rootTargetRef, String serialNo) {
            return call(() -> api.dispatchPool(ownerRef, ownerType, status, rootTargetRef, serialNo));
        }

        public Claim claim(String key, List<String> dispatchIds, String businessRef) {
            return call(() -> api.claim(ownerRef, ownerType, key, actor, new ClaimRequest(dispatchIds, businessRef)));
        }

        public String revoke(String key, String dispatchId, String reason) {
            return call(() -> api.revoke(ownerRef, ownerType, key, actor, dispatchId, new Reason(reason))).docNo();
        }

        // ── 查询、事件、命令 ─────────────────────────────────────

        public List<Balance> balances(String itemRef, String locationRef) {
            return call(() -> api.balances(ownerRef, ownerType, itemRef, locationRef));
        }

        public List<Serial> serials(String itemRef, String locationRef) {
            return call(() -> api.serials(ownerRef, ownerType, itemRef, locationRef));
        }

        public List<InTransit> inTransit() {
            return call(() -> api.inTransit(ownerRef, ownerType));
        }

        public JsonNode events(Long afterSeq, Integer limit) {
            return call(() -> api.events(ownerRef, ownerType, afterSeq, limit));
        }

        public void ackEvents(long seq) {
            call(() -> api.ackEvents(ownerRef, ownerType, new InventoryHostApi.Ack(seq)));
        }

        /** 按原命令键查结果（回包丢失、进程崩溃后的恢复） */
        public JsonNode command(String key) {
            return call(() -> api.command(ownerRef, ownerType, key));
        }
    }
}
