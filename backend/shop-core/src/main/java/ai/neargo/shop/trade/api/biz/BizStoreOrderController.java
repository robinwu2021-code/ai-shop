package ai.neargo.shop.trade.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.spi.trade.OfflineReceiptPort.MinorAmountDeserializer;
import ai.neargo.shop.trade.service.StoreOrderService;
import ai.neargo.shop.trade.service.StoreOrderService.AdjustmentCmd;
import ai.neargo.shop.trade.service.StoreOrderService.BuyerCmd;
import ai.neargo.shop.trade.service.StoreOrderService.CreateCmd;
import ai.neargo.shop.trade.service.StoreOrderService.FillCmd;
import ai.neargo.shop.trade.service.StoreOrderService.FillLineCmd;
import ai.neargo.shop.trade.service.StoreOrderService.LineCmd;
import ai.neargo.shop.trade.service.StoreOrderService.Scope;
import ai.neargo.shop.trade.service.StoreOrderService.StoreSaleView;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 门店零售单（TDD-统一订单与开票资料 P1 / P2）：POS 建单、看单与开票准备度、补开票资料、取消未付款的单。
 * 收款不在这里：现金走 {@code POST /biz/payment/offline-records}（{@code orderNo} = 子单号），收款码 / 付款链接走
 * {@code POST /biz/payment/collection}（{@code bizNo} = 子单号），加总付清后本单转已付（P2）。
 *
 * <p><b>入参不收 storeNo / merchantNo / operator</b>：门店与主体取 {@link BizContext}，操作人取登录态 ——
 * 与 {@code BizOfflineRecordController} 同一条规矩。改价与零税率 / 免税两项附加权限在这里从当前门店角色读出，
 * 交给服务按「这一行要不要」判，不在端点上一刀切（成交价等于商品价的单不需要改价权限）。
 *
 * <p>补开票资料用 {@code biz:invoice:issue}：它是开票前的一步（结账页或事后开票页「补完再开」），
 * 能开票的人就该能补；店员默认有。取消用建单码：收银员自己建错的未付款单自己撤。
 */
@Profile("api")
@RestController
@Validated
public class BizStoreOrderController {
    private static final String TAX_TYPE = "TAXABLE|ZERO_RATED|EXEMPT";
    private static final String BUYER_TYPE = "CONSUMER|COMPANY|CARRIER|DONATE";

    private final StoreOrderService orders;

    public BizStoreOrderController(StoreOrderService orders) {
        this.orders = orders;
    }

    /** 建单；端上单号门店内唯一，离线补传同号返回原单。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_CREATE + "')")
    @PostMapping("/biz/store-orders")
    public StoreSaleView create(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @RequestBody @Valid CreateBody body) {
        return orders.create(scope(), new CreateCmd(key, body.clientOrderNo(), body.channel(), body.currency(),
                body.lines().stream().map(l -> new LineCmd(l.kind(), l.skuNo(), l.quantity(), l.unitPriceMinor(),
                        l.name(), l.taxType(), l.storeItemNo())).toList(),
                body.adjustments() == null ? List.of() : body.adjustments().stream()
                        .map(a -> new AdjustmentCmd(a.kind(), a.name(), a.amountMinor(), a.activityNo())).toList(),
                body.invoice() == null || body.invoice().buyer() == null ? null : buyer(body.invoice().buyer()),
                body.invoice() == null ? null : body.invoice().timing(), body.activityFactsVersion(),
                body.member() == null ? null : body.member().memberNo()));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    @GetMapping("/biz/store-orders/{orderNo}")
    public StoreSaleView get(@PathVariable @Size(max = 64) String orderNo) {
        return orders.get(scope(), orderNo);
    }

    /** 补开票资料：只能补空着的项目，补过即冻结，开票后锁死。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_ISSUE + "')")
    @PutMapping("/biz/store-orders/{orderNo}/invoice-data")
    public StoreSaleView fillInvoiceData(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @PathVariable @Size(max = 64) String orderNo, @RequestBody @Valid FillBody body) {
        return orders.fillInvoiceData(scope(), orderNo, new FillCmd(key,
                body.lines() == null ? List.of() : body.lines().stream()
                        .map(l -> new FillLineCmd(l.lineNo(), l.name(), l.taxType(), l.storeItemNo())).toList(),
                body.buyer() == null ? null : buyer(body.buyer()), body.reason()));
    }

    /** 取消未付款的单。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_CREATE + "')")
    @PostMapping("/biz/store-orders/{orderNo}/cancel")
    public StoreSaleView cancel(@PathVariable @Size(max = 64) String orderNo,
            @RequestBody(required = false) @Valid CancelBody body) {
        return orders.cancel(scope(), orderNo, body == null ? null : body.reason());
    }

    private static Scope scope() {
        var context = BizContext.current();
        return new Scope(BizContext.requireMerchantNo(), BizContext.requireStoreNo(), SecurityUtils.currentUserNo(),
                context.can(BizPerms.ORDER_REPRICE), context.can(BizPerms.INVOICE_TAX_OVERRIDE));
    }

    private static BuyerCmd buyer(BuyerBody b) {
        return new BuyerCmd(b.type(), b.taxId(), b.name(), b.carrierType(), b.carrierId(), b.donateCode());
    }

    /**
     * 建单请求体（与 POS 契约同形，TDD §2）。{@code member}（会员载具，P3）本期收下不处理；
     * {@code invoice.timing} 存在订单上，覆盖门店开票时机（P2）。
     */
    public record CreateBody(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_.:-]{1,64}") String clientOrderNo,
            @NotNull @Pattern(regexp = "POS") String channel,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotEmpty @Size(max = 200) List<@NotNull @Valid LineBody> lines,
            @Size(max = 20) List<@NotNull @Valid AdjustmentBody> adjustments,
            @Valid MemberBody member,
            @Valid InvoiceBody invoice, @Min(1) @Max(1) Integer activityFactsVersion) {}

    /**
     * SKU 行要 skuNo，品名 / 课税别以商品资料为准（送了也不用）；自订行不收 skuNo，品名 / 课税别可空（= 待补，
     * 课税别先取门店默认）。{@code storeItemNo}（门店常用品项）只给自订行，且不与 name / taxType 同送。
     */
    public record LineBody(
            @NotNull @Pattern(regexp = "SKU|CUSTOM") String kind,
            @Size(max = 64) String skuNo,
            @Min(1) @Max(9999) int quantity,
            @JsonProperty(required = true) @JsonDeserialize(using = MinorAmountDeserializer.class)
                    @PositiveOrZero @Max(99_999_999_999L) long unitPriceMinor,
            @Size(max = 256) String name,
            @Pattern(regexp = TAX_TYPE) String taxType,
            @Size(max = 64) String storeItemNo) {
        @AssertTrue(message = "SKU line requires skuNo; custom line takes none")
        public boolean isShapeValid() {
            return "SKU".equals(kind) ? skuNo != null && !skuNo.isBlank() : skuNo == null;
        }

        @AssertTrue(message = "storeItemNo is for custom lines and excludes name / taxType")
        public boolean isStoreItemValid() {
            return storeItemNo == null || "CUSTOM".equals(kind) && name == null && taxType == null;
        }
    }

    public record AdjustmentBody(
            @NotNull @Pattern(regexp = "DISCOUNT|ACTIVITY") String kind,
            @Size(max = 64) String name,
            @JsonProperty(required = true) @Max(0) @Min(-99_999_999_999L) long amountMinor,
            @Size(min = 1, max = 64) String activityNo) {
        @AssertTrue public boolean isActivityValid() {
            return "ACTIVITY".equals(kind) ? activityNo != null && !activityNo.isBlank() && amountMinor < 0 : activityNo == null;
        }
    }

    public record MemberBody(@Size(max = 64) String memberNo) {}

    public record InvoiceBody(@Valid BuyerBody buyer, @Pattern(regexp = "ON_PAID|MANUAL|LATER") String timing) {}

    public record BuyerBody(
            @NotNull @Pattern(regexp = BUYER_TYPE) String type,
            @Size(max = 32) String taxId,
            @Size(max = 60) String name,
            @Size(max = 16) String carrierType,
            @Size(max = 64) String carrierId,
            @Size(max = 16) String donateCode) {}

    public record FillBody(
            @Size(max = 200) List<@NotNull @Valid FillLineBody> lines,
            @Valid BuyerBody buyer,
            @Size(max = 100) String reason) {}

    /** {@code storeItemNo}：用门店常用品项补品名与课税别，不与 name / taxType 同送。 */
    public record FillLineBody(
            @Min(1) int lineNo,
            @Size(min = 1, max = 256) String name,
            @Pattern(regexp = TAX_TYPE) String taxType,
            @Size(max = 32) String storeItemNo) {
        @AssertTrue(message = "storeItemNo excludes name / taxType")
        public boolean isStoreItemValid() {
            return storeItemNo == null || name == null && taxType == null;
        }
    }

    public record CancelBody(@Size(max = 100) String reason) {}
}
