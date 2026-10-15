package ai.neargo.shop.paybridge;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort.UploadState;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import ai.neargo.shop.trade.order.mapper.ShippingUploadMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Batch projection exposes only queue status and source references, never payer or financial material. */
@Component
public class MybatisShippingUploadProgress implements ShippingUploadProgress {
    private final StoreOrderMapper orders;
    private final ShippingUploadMapper uploads;
    private final ObjectMapper json;

    public MybatisShippingUploadProgress(StoreOrderMapper orders, ShippingUploadMapper uploads, ObjectMapper json) {
        this.orders = orders;
        this.uploads = uploads;
        this.json = json;
    }

    @Override
    public Map<String, UploadState> statesOf(List<String> subOrderNos) {
        if (subOrderNos.isEmpty()) { return Map.of(); }
        return DataScopeContext.executeWithoutScope(() -> capture(subOrderNos));
    }

    private Map<String, UploadState> capture(List<String> subOrderNos) {
        List<String> paymentNos = orders.selectList(Wrappers.<OrdSubOrder>query()
                .select("sub_order_no", "order_no").in("sub_order_no", subOrderNos)).stream()
                .map(OrdSubOrder::getOrderNo).distinct().toList();
        if (paymentNos.isEmpty()) { return Map.of(); }
        Map<String, UploadState> result = new LinkedHashMap<>();
        for (TrdShippingUpload row : uploads.selectList(Wrappers.<TrdShippingUpload>query()
                .select("order_no", "source_snapshot", "status", "err_code", "err_msg")
                .in("order_no", paymentNos))) {
            ShippingUploadSnapshot snapshot = ShippingUploadSnapshot.parse(row.getSourceSnapshot(), json);
            if (snapshot == null) { continue; }
            for (var source : snapshot.shipments()) {
                if (!subOrderNos.contains(source.subOrderNo())) { continue; }
                String error = row.getErrMsg() == null ? null : row.getErrCode() + ": " + row.getErrMsg();
                result.put(source.subOrderNo(), new UploadState(source.sourceShipmentNo(),
                        source.sourceShipmentVersion(), source.expressCompany(), source.trackingNo(),
                        row.getStatus(), error));
            }
        }
        return result;
    }
}
