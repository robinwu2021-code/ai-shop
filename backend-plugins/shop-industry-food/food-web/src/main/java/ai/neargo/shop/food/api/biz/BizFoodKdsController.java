package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.api.stream.FoodKdsStreamTransport;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.FoodKdsContracts.DeviceStationsReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.DeviceStatusReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.RegisterDeviceReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.StoreDeviceVO;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Keeps the BizFoodKdsController protocol scoped to original restaurant facts and lifecycle
 * authority.
 */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
@Validated
public class BizFoodKdsController {
    private final FoodKdsService service;
    private final FoodKdsStreamTransport streams;
    private final FoodBizStoreScope scope;

    public BizFoodKdsController(
            FoodKdsService service,
            FoodKdsStreamTransport streams,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        this.service = service;
        this.streams = streams;
        scope = new FoodBizStoreScope(stores, validator);
    }

    /** 登记门店设备（厨显、叫号屏、点餐屏、离线收银）；登记即在用（TDD-餐饮域迁移新库 §2.8 D-5）。 */
    @PostMapping("/biz/x/food/devices")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    public StoreDeviceVO register(
            @Valid @RequestBody RegisterDeviceReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        var c = scope.current(request);
        return service.register(
                c.merchantNo(), c.currentStoreNo(), SecurityUtils.currentUserNo(), req, key);
    }

    @GetMapping("/biz/x/food/devices")
    @PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')")
    public List<StoreDeviceVO> list(HttpServletRequest request) {
        var c = scope.current(request);
        return service.list(c.merchantNo(), c.currentStoreNo());
    }

    /** 停用或恢复；停用的屏立即连不上。 */
    @PostMapping("/biz/x/food/devices/{deviceNo}:status")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    public StoreDeviceVO changeStatus(
            @PathVariable @NotBlank @Size(max = 32) String deviceNo,
            @Valid @RequestBody DeviceStatusReq req,
            HttpServletRequest request) {
        var c = scope.current(request);
        return service.changeStatus(
                c.merchantNo(), c.currentStoreNo(), SecurityUtils.currentUserNo(), deviceNo, req);
    }

    /** 设定厨显看哪些工位。 */
    @PutMapping("/biz/x/food/devices/{deviceNo}/stations")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    public StoreDeviceVO assignStations(
            @PathVariable @NotBlank @Size(max = 32) String deviceNo,
            @Valid @RequestBody DeviceStationsReq req,
            HttpServletRequest request) {
        var c = scope.current(request);
        return service.assignStations(
                c.merchantNo(), c.currentStoreNo(), SecurityUtils.currentUserNo(), deviceNo, req);
    }

    /**
     * 屏的刷新通知流（TDD-餐饮域迁移新库 §2.8 D-3、D-5）：店员在屏上登录，带上这台屏的设备号。
     * 只推「来刷新」：厨显收后厨与工位的变化，叫号屏收排号的变化；连上即推一次全量，每 30 秒兜底并重新核验。
     * 厨显收到后按工位拉后厨任务列表，叫号屏拉排号列表。
     */
    @GetMapping(value = "/biz/x/food/kitchen-stream", produces = "text/event-stream")
    @PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')")
    public SseEmitter stream(
            @RequestParam @NotBlank @Size(max = 32) String deviceNo,
            HttpServletRequest request) {
        var c = scope.current(request);
        return streams.subscribe(
                c.merchantNo(), c.currentStoreNo(), SecurityUtils.currentUserNo(), deviceNo);
    }
}
