package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * 门店设备与厨显工位（TDD-餐饮域迁移新库 §2.8 D-5）：屏用店员账号登录，所在设备须在册、在用、类型对得上。
 */
public final class FoodKdsContracts {
    private FoodKdsContracts() {}

    /** 设备类型（TDD-餐饮域迁移新库 §2.8 D-5）：屏类设备与离线收银；打印机在打印域、支付终端在支付库。 */
    public static final List<String> DEVICE_TYPES = List.of("ORDER_SCREEN", "KDS", "CALL_SCREEN", "OFFLINE_POS");

    /** 登记一台门店设备；登记即在用（屏用店员账号登录，不走配对）。 */
    public record RegisterDeviceReq(
            @NotBlank @Size(max = 16) String deviceType,
            @NotBlank @Size(max = 64) String displayName,
            @Size(max = 32) String serialNo) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    /** 停用（SUSPENDED）或恢复（ACTIVE）一台设备；停用后屏立即连不上、事件流在下次核验时断开。 */
    public record DeviceStatusReq(
            @JsonProperty(value = "version", required = true)
                    @JsonDeserialize(using = CatalogRevisionDeserializer.class)
                    @Min(0)
                    long version,
            @NotBlank @Size(max = 10) String status) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    /** 设定一台厨显看哪些工位；{@code version} 为订阅的版本，首次设定为 0。 */
    public record DeviceStationsReq(
            @JsonProperty(value = "version", required = true)
                    @JsonDeserialize(using = CatalogRevisionDeserializer.class)
                    @Min(0)
                    long version,
            @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 32) String> stationNos) {
        public DeviceStationsReq {
            if (stationNos != null) {
                stationNos = List.copyOf(stationNos);
            }
        }

        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    /**
     * 一台门店设备。{@code stationNos} 只对厨显有意义（未设定为空）；{@code stationVersion} 为工位设定的版本，
     * 未设定为 0。
     */
    public record StoreDeviceVO(
            String deviceNo,
            String entityNo,
            String storeNo,
            String deviceType,
            String displayName,
            String serialNo,
            String status,
            long version,
            List<String> stationNos,
            long stationVersion) {
        public StoreDeviceVO {
            stationNos = stationNos == null ? List.of() : List.copyOf(stationNos);
        }
    }

    /**
     * 事件打印的来源：由原始业务事件（接单、厨房动作、改单、账单变化）投影出的一份内容，打印时据此定格单据。
     * 不再落厨显事件表（TDD-餐饮域迁移新库 §2.8 D-3）。Payload is a selected business projection, never raw
     * order/customer rows or credentials.
     */
    public record EventDraft(
            String eventNo,
            String entityNo,
            String storeNo,
            String stationNo,
            String eventType,
            String sourceNo,
            long sourceVersion,
            String payload,
            String occurredAt) {}
}
