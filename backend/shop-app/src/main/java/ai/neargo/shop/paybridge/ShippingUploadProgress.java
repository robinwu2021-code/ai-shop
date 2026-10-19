package ai.neargo.shop.paybridge;

import ai.neargo.shop.spi.logistics.ShipmentSourcePort.UploadState;

import java.util.List;
import java.util.Map;

public interface ShippingUploadProgress {
    Map<String, UploadState> statesOf(List<String> subOrderNos);
}
