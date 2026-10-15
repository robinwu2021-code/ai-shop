package ai.neargo.shop.paybridge;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.ShippingUploadMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class MybatisShippingUploadQueue implements ShippingUploadQueue {
    private final ShippingUploadMapper mapper;
    private final PaymentMapper payments;

    public MybatisShippingUploadQueue(ShippingUploadMapper mapper, PaymentMapper payments) {
        this.mapper = mapper;
        this.payments = payments;
    }

    @Override
    public void lockPayment(String orderNo) {
        DataScopeContext.executeWithoutScope(() -> payments.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getOrderNo, orderNo).last("LIMIT 1 FOR UPDATE")));
    }

    @Override
    public TrdShippingUpload byOrder(String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectOne(Wrappers.<TrdShippingUpload>lambdaQuery()
                .eq(TrdShippingUpload::getOrderNo, orderNo).last("LIMIT 1")));
    }

    @Override
    public TrdShippingUpload byOrderLocked(String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectOne(Wrappers.<TrdShippingUpload>lambdaQuery()
                .eq(TrdShippingUpload::getOrderNo, orderNo).last("LIMIT 1 FOR UPDATE")));
    }

    @Override
    public void insert(TrdShippingUpload row) {
        DataScopeContext.executeWithoutScope(() -> mapper.insert(row));
    }

    @Override
    public boolean refresh(TrdShippingUpload row, String snapshot) {
        boolean manual = WxShippingUploadService.hardCorrectionFailure(row);
        int attempts = manual && row.getAttempts() != null ? row.getAttempts() : 0;
        return DataScopeContext.executeWithoutScope(() -> mapper.update(null, revision(row)
                .set("source_snapshot", snapshot == null ? null : snapshot.getBytes(StandardCharsets.UTF_8))
                .set("status", manual ? TrdShippingUpload.FAILED : TrdShippingUpload.PENDING).set("attempts", attempts)
                .set("err_code", manual ? row.getErrCode() : null)
                .set("err_msg", manual ? row.getErrMsg() : null).set("uploaded_at", null))) == 1;
    }

    @Override
    public boolean finish(TrdShippingUpload row, WxShippingUploadService.Outcome outcome,
                          Integer errorCode, String errorMessage) {
        UpdateWrapper<TrdShippingUpload> change = revision(row).eq("status", TrdShippingUpload.PENDING)
                .set("attempts", outcome.attempts()).set("status", outcome.status())
                .set("err_code", errorCode).set("err_msg", clip(errorMessage));
        if (TrdShippingUpload.SUCCESS.equals(outcome.status())) { change.set("uploaded_at", LocalDateTime.now()); }
        return DataScopeContext.executeWithoutScope(() -> mapper.update(null, change)) == 1;
    }

    @Override
    public void blocked(TrdShippingUpload row, String reason) {
        DataScopeContext.executeWithoutScope(() -> mapper.update(null, revision(row)
                .eq("status", TrdShippingUpload.PENDING).set("err_code", 0).set("err_msg", clip(reason))));
    }

    @Override
    public List<TrdShippingUpload> pending(int limit) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectList(Wrappers.<TrdShippingUpload>lambdaQuery()
                .eq(TrdShippingUpload::getStatus, TrdShippingUpload.PENDING)
                .orderByAsc(TrdShippingUpload::getId).last("LIMIT " + limit)));
    }

    @Override
    public String statusOf(Long id) {
        TrdShippingUpload row = DataScopeContext.executeWithoutScope(() -> mapper.selectById(id));
        return row == null ? null : row.getStatus();
    }

    private static UpdateWrapper<TrdShippingUpload> revision(TrdShippingUpload row) {
        return Wrappers.<TrdShippingUpload>update().eq("id", row.getId())
                .eq("version", row.getVersion() == null ? 0L : row.getVersion())
                .setSql("version = version + 1").set("updated_at", LocalDateTime.now());
    }

    private static String clip(String message) {
        return message == null || message.length() <= 500 ? message : message.substring(0, 500);
    }
}
