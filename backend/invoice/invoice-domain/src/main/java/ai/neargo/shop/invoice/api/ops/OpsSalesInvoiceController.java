package ai.neargo.shop.invoice.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.NumberRangeVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsInvoiceQuery;
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsSellerQuery;
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsSellerVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsTransmissionQuery;
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsTransmissionVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderCheckVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderRangeReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderVerificationVO;
import ai.neargo.shop.invoice.service.InvoiceOpsService;
import ai.neargo.shop.invoice.service.InvoiceService;
import ai.neargo.shop.invoice.service.InvoiceSetupService;
import ai.neargo.shop.invoice.service.InvoiceTransmissionService;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端 · 电子发票（TDD-发票模块 §18）：跨商家看配置、号码余量、上传积压与任务，代商家核验账号、领号段、
 * 重传、向服务商查单。
 *
 * <p><b>权限复用 {@code finance:invoice:*}</b>：经办的是开票申请那同一批财务人员；看用 read，动服务商、
 * 改任务状态用 verify。处置动作与 B 端走同一套服务 —— 运营只是替商家点那一下，规则一字不差。
 *
 * <p><b>数据域</b>：按运营会话的商家维度收窄（{@link OpsEntityScope}）。列表只出范围内的主体；
 * 代处置动作对范围外的主体 / 发票按「不存在」回（26001 / 26005），不泄露它存在与否。
 *
 * <p>与 {@code /ops/invoice-requests}（平台人工开票申请，trade 域）是两件事：那边是事后申请、人工回填票号，
 * 这边是门店当场开的法定电子发票。
 */
@Profile("ops")
@RestController
@Validated
public class OpsSalesInvoiceController {

    private final InvoiceOpsService ops;
    private final InvoiceService invoices;
    private final InvoiceSetupService setup;
    private final InvoiceTransmissionService transmissions;

    public OpsSalesInvoiceController(InvoiceOpsService ops, InvoiceService invoices, InvoiceSetupService setup,
            InvoiceTransmissionService transmissions) {
        this.ops = ops;
        this.invoices = invoices;
        this.setup = setup;
        this.transmissions = transmissions;
    }

    @GetMapping("/ops/sales-invoice-sellers")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_READ + "')")
    public PageData<OpsSellerVO> sellers(@Valid @ModelAttribute OpsSellerQuery query) {
        return ops.sellers(tenant(), OpsEntityScope.current(), query);
    }

    /** 代商家核验加值中心账号；通过即解除认证失败造成的上传暂停（与 B 端同一规则）。 */
    @PostMapping("/ops/sales-invoice-sellers/{entityNo}/provider-verifications")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_VERIFY + "')")
    public ProviderVerificationVO verifyProvider(@PathVariable String entityNo) {
        return setup.verifyProvider(entityScope(entityNo));
    }

    /** 代商家向加值中心领号段。 */
    @PostMapping("/ops/sales-invoice-sellers/{entityNo}/provider-ranges")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_VERIFY + "')")
    public NumberRangeVO fetchProviderRange(@PathVariable String entityNo,
            @Valid @RequestBody(required = false) ProviderRangeReq req) {
        return setup.fetchProviderRange(entityScope(entityNo), req == null ? null : req.periodCode());
    }

    @GetMapping("/ops/sales-invoice-transmissions")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_READ + "')")
    public PageData<OpsTransmissionVO> transmissions(@Valid @ModelAttribute OpsTransmissionQuery query) {
        return ops.transmissions(tenant(), OpsEntityScope.current(), query);
    }

    @GetMapping("/ops/sales-invoices")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_READ + "')")
    public PageData<InvoiceVO> invoices(@Valid @ModelAttribute OpsInvoiceQuery query) {
        return invoices.listForOps(tenant(), OpsEntityScope.current(), query);
    }

    /** 代商家重传待人工处理的上传（报文不变）。 */
    @PostMapping("/ops/sales-invoices/{invoiceNo}/retransmissions")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_VERIFY + "')")
    public InvoiceVO retransmit(@PathVariable String invoiceNo) {
        var scope = invoiceScope(invoiceNo);
        transmissions.retry(scope, invoiceNo);
        return invoices.get(scope, invoiceNo);
    }

    /** 代商家向服务商查单并对账。 */
    @PostMapping("/ops/sales-invoices/{invoiceNo}/provider-checks")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_VERIFY + "')")
    public ProviderCheckVO checkWithProvider(@PathVariable String invoiceNo) {
        return transmissions.checkWithProvider(invoiceScope(invoiceNo), invoiceNo);
    }

    private static String tenant() {
        return SecurityUtils.requireUser().tenantNo();
    }

    private static Scope entityScope(String entityNo) {
        if (!OpsEntityScope.allows(OpsEntityScope.current(), entityNo))
            throw BizException.of(ErrorCode.INVOICE_REGIME_NOT_ENABLED);   // 与「该主体没开通」同一个回答
        var user = SecurityUtils.requireUser();
        return new Scope(user.tenantNo(), entityNo, "*", user.userNo());
    }

    private Scope invoiceScope(String invoiceNo) {
        var user = SecurityUtils.requireUser();
        var scope = invoices.scopeOf(user.tenantNo(), invoiceNo, user.userNo());
        if (!OpsEntityScope.allows(OpsEntityScope.current(), scope.entityNo()))
            throw BizException.of(ErrorCode.INVOICE_NOT_FOUND);           // 与「没有这张票」同一个回答
        return scope;
    }
}
