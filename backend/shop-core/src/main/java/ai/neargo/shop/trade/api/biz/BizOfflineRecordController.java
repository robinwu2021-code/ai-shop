package ai.neargo.shop.trade.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.OfflineReceiptPort.MinorAmountDeserializer;
import ai.neargo.shop.trade.service.OfflineRecordService;
import ai.neargo.shop.trade.service.OfflineRecordService.CorrectCmd;
import ai.neargo.shop.trade.service.OfflineRecordService.ListQuery;
import ai.neargo.shop.trade.service.OfflineRecordService.OfflineRecordView;
import ai.neargo.shop.trade.service.OfflineRecordService.RecordCmd;
import ai.neargo.shop.trade.service.OfflineRecordService.RecordPage;
import ai.neargo.shop.trade.service.OfflineRecordService.Scope;
import ai.neargo.shop.trade.service.OfflineRecordService.Summary;
import ai.neargo.shop.trade.service.OfflineRecordService.VoidCmd;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * App 外收款记录（TDD-商户App外部记账）：现金、外部刷卡机、银行转账在 App 里记一笔、看、更正、作废。
 *
 * <p><b>只共用 {@code /biz/payment} 这个前缀，实现不进支付域</b>：这里的钱平台不经手，
 * 不进拨款、不进通道对账、不能退款；{@code /biz/payment/orders}、{@code /statement}、{@code /refund} 都看不到它。
 *
 * <p>门店、主体、操作人一律取 {@link BizContext}，请求体与查询串不收；请求体还单独拒未知字段（全局没开），
 * 防的是客户端以为传了 {@code storeNo}、{@code recordedBy} 或旧字段 {@code requestId} 起了作用。
 */
@Profile("api")
@RestController
@Validated
public class BizOfflineRecordController {
    private static final String METHODS = "CASH|BANK_TRANSFER|EXTERNAL_POS|OTHER";

    private final OfflineRecordService records;

    /** 只依赖 trade 域的记账服务，不碰任何支付端口。 */
    public BizOfflineRecordController(OfflineRecordService records) {
        this.records = records;
    }

    /** 记一笔；App 每打开一次记账表单生成一个幂等键，重送沿用。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_OFFLINE_RECORD + "')")
    @PostMapping("/biz/payment/offline-records")
    public OfflineRecordView record(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @RequestBody @Valid RecordBody body) {
        return records.record(scope(), new RecordCmd(key, body.amountMinor(), body.currency(), body.method(),
                body.orderNo(), body.note(), body.receivedAt() == null ? null : body.receivedAt().toInstant()));
    }

    /** 本店记录，按实收时间倒序、游标翻页；读复用收款查看码。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_ORDER_VIEW + "')")
    @GetMapping("/biz/payment/offline-records")
    public RecordPage list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "method", required = false) List<@Pattern(regexp = METHODS) String> methods,
            @RequestParam(name = "status", required = false) List<@Pattern(regexp = "RECORDED|VOIDED") String> statuses,
            @RequestParam(required = false) @Size(min = 1, max = 32) String q,
            @RequestParam(required = false) @Size(min = 1, max = 64) String orderNo,
            @RequestParam(required = false) @Size(max = 128) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return records.list(scope(), new ListQuery(from, to, methods, statuses, q, orderNo, cursor, size));
    }

    /** 区间内未作废记录的笔数与金额：报表单列「外部記帳（商家自報）」，不并入渠道营业额（已拍板 D6-B）。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_ORDER_VIEW + "')")
    @GetMapping("/biz/payment/offline-records/summary")
    public Summary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return records.summary(scope(), from, to);
    }

    /** 更正金额、方式、实收时间；原因必填，旧值留痕。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_OFFLINE_MANAGE + "')")
    @PostMapping("/biz/payment/offline-records/{recordNo}/correct")
    public OfflineRecordView correct(
            @PathVariable @Size(max = 64) String recordNo, @RequestBody @Valid CorrectBody body) {
        return records.correct(scope(), recordNo, new CorrectCmd(body.amountMinor(), body.currency(), body.method(),
                body.receivedAt() == null ? null : body.receivedAt().toInstant(), body.reason(),
                body.expectedVersion()));
    }

    /** 作废：置为已作废，行不删。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_OFFLINE_MANAGE + "')")
    @PostMapping("/biz/payment/offline-records/{recordNo}/void")
    public OfflineRecordView voidRecord(
            @PathVariable @Size(max = 64) String recordNo, @RequestBody @Valid VoidBody body) {
        return records.voidRecord(scope(), recordNo, new VoidCmd(body.reason(), body.expectedVersion()));
    }

    private static Scope scope() {
        return new Scope(BizContext.current().requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo());
    }

    /** 记一笔的请求体。金额只收 JSON 整数（不把 480.5 截成 480）。 */
    public record RecordBody(
            @JsonProperty(required = true) @JsonDeserialize(using = MinorAmountDeserializer.class)
                    @Positive @Max(99_999_999_999L) long amountMinor,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull @Pattern(regexp = METHODS) String method,
            @Size(min = 1, max = 64) String orderNo,
            @Size(max = 100) String note,
            OffsetDateTime receivedAt) {
        /** 未知字段（含 storeNo、recordedBy、旧字段 requestId）一律 10400。 */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    /** 更正：币种必须与原记录相同；receivedAt 不传 = 不改。 */
    public record CorrectBody(
            @JsonProperty(required = true) @JsonDeserialize(using = MinorAmountDeserializer.class)
                    @Positive @Max(99_999_999_999L) long amountMinor,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull @Pattern(regexp = METHODS) String method,
            OffsetDateTime receivedAt,
            @NotBlank @Size(max = 100) String reason,
            @NotNull @Positive Long expectedVersion) {
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    /** 作废：只要原因与版本。 */
    public record VoidBody(@NotBlank @Size(max = 100) String reason, @NotNull @Positive Long expectedVersion) {
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
