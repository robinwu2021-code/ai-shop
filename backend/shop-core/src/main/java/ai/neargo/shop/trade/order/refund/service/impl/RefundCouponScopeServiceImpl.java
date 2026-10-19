package ai.neargo.shop.trade.order.refund.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.mapper.RefundObligationMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.refund.service.RefundCouponScopeService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class RefundCouponScopeServiceImpl implements RefundCouponScopeService {
    private final RefundObligationMapper obligations;
    private final StoreOrderMapper children;
    private final ObjectMapper json;
    public RefundCouponScopeServiceImpl(RefundObligationMapper obligations,StoreOrderMapper children,ObjectMapper json) {
        this.obligations=obligations; this.children=children; this.json=json;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void freeze(String orderNo,String couponNo,CouponPort.Allocation allocation) {
        DataScopeContext.executeWithoutScope(() -> {
            var all=children.selectList(Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getOrderNo,orderNo));
            boolean used=couponNo!=null && !couponNo.isBlank();
            List<String> scope=used ? all.stream().filter(child -> allocation.shares().stream().anyMatch(share ->
                share.amount()>0 && share.merchantNo().equals(child.getEntityNo())
                && Objects.equals(share.storeNo(),child.getStoreNo())))
                .map(OrdSubOrder::getSubOrderNo).sorted().toList() : List.of();
            // Legacy allocation without an exact store binding must not be guessed.
            if(used && (allocation.totalDiscount()<=0 || scope.isEmpty())) return null;
            var frozen=new Scope(orderNo,used?couponNo:null,scope);
            String payload=json.writeValueAsString(frozen);
            if(obligations.freezeScope(orderNo,frozen.couponNo(),payload,hash(payload))!=1)
                throw new IllegalStateException("REFUND_COUPON_SCOPE_CONFLICT");
            return null;
        });
    }
    public record Scope(String orderNo,String couponNo,List<String> subOrderNos) { }
    public static String hash(String payload) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException failure) {throw new IllegalStateException(failure);}
    }
}
