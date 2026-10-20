package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.entitlement.config.EntitlementAuthorizationProperties;
import ai.neargo.shop.trade.dto.AuthorizationCommand;
import ai.neargo.shop.trade.dto.AuthorizationView;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entitlement.entity.OrdEntAuthorization;
import ai.neargo.shop.trade.entitlement.EntitlementOrderSnapshotPolicy;
import ai.neargo.shop.trade.mapper.TradeMappers.EntitlementAuthorizationMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.service.EntitlementAuthorizationService;
import ai.neargo.shop.trade.service.EntitlementServiceAuthorizationService;
import org.springframework.beans.factory.ObjectProvider;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class EntitlementAuthorizationServiceImpl implements EntitlementAuthorizationService {
    private static final Duration MAX_INTENT_TTL = Duration.ofMinutes(10);
    private final EntitlementAuthorizationMapper authorizations;
    private final OrderMapper orders;
    private final SubOrderMapper subOrders;
    private final OrderItemMapper items;
    private final ObjectMapper json;
    private final EntitlementAuthorizationProperties properties;
    private final ObjectProvider<EntitlementServiceAuthorizationService> serviceAuthorizations;
    public EntitlementAuthorizationServiceImpl(EntitlementAuthorizationMapper authorizations, OrderMapper orders,
            SubOrderMapper subOrders, OrderItemMapper items, ObjectMapper json,
            EntitlementAuthorizationProperties properties,
            ObjectProvider<EntitlementServiceAuthorizationService> serviceAuthorizations) {
        this.authorizations = authorizations;
        this.orders = orders;
        this.subOrders = subOrders;
        this.items = items;
        this.json = json;
        this.properties = properties;
        this.serviceAuthorizations = serviceAuthorizations;
    }

    @Override
    @Transactional
    public void stageTrustedIntent(String tenantNo, String orderNo, SourceRef source, String quoteRef,
                                   String selectionDigest, long amountMinor, String currency,
                                   Instant quoteExpiresAt) {
        requireEnabled();
        if (source != null && "SERVICE_USE".equals(source.sourceType())) {
            serviceAuthorizations.getObject().stage(tenantNo, orderNo, source, quoteRef, selectionDigest,
                    amountMinor, currency, quoteExpiresAt);
            return;
        }
        validateId(tenantNo); validateId(orderNo); validateId(quoteRef);
        if (source == null || !source.sourceType().contains("USE") || amountMinor < 0
                || currency == null || !currency.matches("[A-Z]{3}") || !isHash(selectionDigest)
                || quoteExpiresAt == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        Instant now = Instant.now();
        Instant expires = quoteExpiresAt.isBefore(now.plus(MAX_INTENT_TTL)) ? quoteExpiresAt : now.plus(MAX_INTENT_TTL);
        if (!now.isBefore(expires)) throw BizException.of(ErrorCode.CONFLICT);
        var order = lockOrder(tenantNo, orderNo);
        if (!OrdOrder.WAIT_PAY.equals(order.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var orderSubOrders = loadSubOrders(tenantNo, orderNo);
        OrdSubOrder target = orderSubOrders.stream().filter(row -> source.sourceNo().equals(row.getSubOrderNo()))
                .findFirst().orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        if (!order.getUserNo().equals(target.getUserNo()) || target.getStoreNo() == null
                || target.getEntityNo() == null || order.getVersion() == null
                || order.getVersion() != source.sourceRevision() || !currency.equals(order.getCurrency())
                || order.getPayAmount() == null || amountMinor > order.getPayAmount()) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        String snapshotHash = snapshotHash(order, orderSubOrders, loadItems(tenantNo, orderNo));
        String payloadHash = digest(String.join("|", tenantNo, orderNo, source.useIntentKey(), quoteRef,
                selectionDigest, snapshotHash, Long.toString(amountMinor), currency));
        var existing = DataScopeContext.executeWithoutScope(() -> authorizations.selectOne(
                Wrappers.<OrdEntAuthorization>lambdaQuery().eq(OrdEntAuthorization::getTenantNo, tenantNo)
                        .eq(OrdEntAuthorization::getQuoteRef, quoteRef).last("limit 1")));
        if (existing != null) {
            if (!payloadHash.equals(existing.getPayloadHash())) throw BizException.of(ErrorCode.CONFLICT);
            return;
        }
        var row = new OrdEntAuthorization();
        row.setTenantNo(tenantNo);
        row.setIntentKey(BizKey.next(BizKey.ENTITLEMENT_INTENT));
        row.setPayloadHash(payloadHash);
        row.setSourceKey(source.useIntentKey());
        row.setSourceSnapshot(writeSource(source));
        row.setSourceRevision(source.sourceRevision());
        row.setOrderNo(orderNo);
        row.setUserNo(order.getUserNo());
        row.setEntityNo(target.getEntityNo());
        row.setStoreNo(target.getStoreNo());
        row.setQuoteRef(quoteRef);
        row.setSelectionDigest(selectionDigest);
        row.setOrderSnapshotHash(snapshotHash);
        row.setAmountMinor(amountMinor);
        row.setCurrency(currency);
        row.setStatus("PENDING");
        row.setExpiresAt(LocalDateTime.ofInstant(expires, ZoneOffset.UTC));
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        row.setUpdatedAt(row.getCreatedAt());
        row.setCreatedBy(order.getUserNo());
        row.setUpdatedBy(order.getUserNo());
        DataScopeContext.executeWithoutScope(() -> authorizations.insert(row));
    }

    @Override
    @Transactional
    public AuthorizationView confirm(String tenantNo, String userNo, String orderNo, AuthorizationCommand command) {
        requireEnabled();
        if (command == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        validateId(tenantNo); validateId(userNo); validateId(orderNo);
        var service = serviceAuthorizations.getIfAvailable();
        if (service != null && service.supports(tenantNo, orderNo)) return service.confirm(tenantNo, userNo, orderNo, command);
        var previous = DataScopeContext.executeWithoutScope(() -> authorizations.selectOne(
                Wrappers.<OrdEntAuthorization>lambdaQuery().eq(OrdEntAuthorization::getTenantNo, tenantNo)
                        .eq(OrdEntAuthorization::getUserNo, userNo)
                        .eq(OrdEntAuthorization::getRequestKey, command.requestKey()).last("limit 1")));
        if (previous != null) {
            if (!previous.getOrderNo().equals(orderNo) || !previous.getQuoteRef().equals(command.quoteRef())
                    || !previous.getSelectionDigest().equals(command.selectionDigest())
                    || previous.getSourceRevision() != command.orderRevision()) throw BizException.of(ErrorCode.CONFLICT);
            return view(previous);
        }
        var order = lockOrder(tenantNo, orderNo);
        if (!userNo.equals(order.getUserNo()) || !OrdOrder.WAIT_PAY.equals(order.getStatus())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        var row = DataScopeContext.executeWithoutScope(() -> authorizations.selectOne(
                Wrappers.<OrdEntAuthorization>lambdaQuery().eq(OrdEntAuthorization::getTenantNo, tenantNo)
                        .eq(OrdEntAuthorization::getOrderNo, orderNo)
                        .eq(OrdEntAuthorization::getQuoteRef, command.quoteRef()).last("FOR UPDATE")));
        if (row == null || !"PENDING".equals(row.getStatus()) || !userNo.equals(row.getUserNo())
                || row.getSourceRevision() != command.orderRevision()
                || !row.getSelectionDigest().equals(command.selectionDigest())) throw BizException.of(ErrorCode.CONFLICT);
        var source = readSource(row.getSourceSnapshot());
        var subOrderRows = loadSubOrders(tenantNo, orderNo);
        String currentSnapshot = snapshotHash(order, subOrderRows, loadItems(tenantNo, orderNo));
        if (!row.getOrderSnapshotHash().equals(currentSnapshot) || order.getVersion() != command.orderRevision()
                || !OrdOrder.WAIT_PAY.equals(order.getStatus())) throw BizException.of(ErrorCode.CONFLICT);
        Instant now = Instant.now();
        Instant expires = row.getExpiresAt().toInstant(ZoneOffset.UTC);
        if (!now.isBefore(expires)) throw BizException.of(ErrorCode.CONFLICT);
        String authorizationNo = BizKey.next(BizKey.ENTITLEMENT_AUTHORIZATION);
        row.setAuthorizationNo(authorizationNo);
        row.setRequestKey(command.requestKey());
        row.setStatus("AUTHORIZED");
        row.setAuthorizedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        row.setUpdatedAt(row.getAuthorizedAt());
        row.setUpdatedBy(userNo);
        DataScopeContext.executeWithoutScope(() -> authorizations.updateById(row));
        return new AuthorizationView(authorizationNo, "AUTHORIZED", expires);
    }

    private OrdOrder lockOrder(String tenantNo, String orderNo) {
        var row = DataScopeContext.executeWithoutScope(() -> orders.selectOne(
                Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getTenantNo, tenantNo)
                        .eq(OrdOrder::getOrderNo, orderNo).last("FOR UPDATE")));
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private List<OrdSubOrder> loadSubOrders(String tenantNo, String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> subOrders.selectList(
                Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getTenantNo, tenantNo)
                        .eq(OrdSubOrder::getOrderNo, orderNo).orderByAsc(OrdSubOrder::getId).last("FOR UPDATE")));
    }

    private List<OrdItem> loadItems(String tenantNo, String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> items.selectList(
                Wrappers.<OrdItem>lambdaQuery().eq(OrdItem::getTenantNo, tenantNo)
                        .eq(OrdItem::getOrderNo, orderNo).orderByAsc(OrdItem::getId).last("FOR UPDATE")));
    }

    private String snapshotHash(OrdOrder order, List<OrdSubOrder> subOrderRows, List<OrdItem> itemRows) {
        return EntitlementOrderSnapshotPolicy.hash(json, order, subOrderRows, itemRows);
    }

    private String writeSource(SourceRef source) {
        try { return json.writeValueAsString(source); }
        catch (Exception e) { throw new IllegalStateException("Could not serialize source reference", e); }
    }

    private SourceRef readSource(String snapshot) {
        try { return json.readValue(snapshot, SourceRef.class); }
        catch (Exception e) { throw new IllegalStateException("Stored source reference is invalid", e); }
    }

    private static AuthorizationView view(OrdEntAuthorization row) {
        return new AuthorizationView(row.getAuthorizationNo(), row.getStatus(),
                row.getExpiresAt().toInstant(ZoneOffset.UTC));
    }

    private static boolean isHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private void requireEnabled() {
        if (!properties.isEnabled()) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }
    private static void validateId(String value) {
        if (value == null || value.isBlank() || value.length() > 64 || !value.equals(value.strip())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
