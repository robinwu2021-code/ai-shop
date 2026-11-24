package ai.neargo.shop.invoice.service.impl;

import static ai.neargo.shop.common.otp.service.OtpSendService.Channel.EMAIL;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.SecretRef;
import ai.neargo.secret.api.SecretVault;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Masks;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.otp.OtpEntries;
import ai.neargo.shop.common.otp.OtpKeys;
import ai.neargo.shop.common.otp.OtpScenes;
import ai.neargo.shop.common.otp.service.OtpSendService;
import ai.neargo.shop.common.otp.service.OtpSendService.OtpContext;
import ai.neargo.shop.common.otp.service.OtpSendService.SendCommand;
import ai.neargo.shop.common.otp.service.OtpSendService.VerifyCommand;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.entity.IvcInvoiceDelivery;
import ai.neargo.shop.invoice.mapper.InvoiceDeliveryMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.InvoiceMapper;
import ai.neargo.shop.invoice.service.InvoiceDeliveryService;
import ai.neargo.shop.print.api.ExportSourceType;
import ai.neargo.shop.print.api.PrintActor;
import ai.neargo.shop.print.api.PrintExportFacade;
import ai.neargo.shop.print.api.PrintFailure;
import ai.neargo.shop.print.api.PrintHashes;
import ai.neargo.shop.print.api.PrintQueryService;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import ai.neargo.shop.spi.notify.MailPort;
import ai.neargo.shop.spi.notify.MailPort.Attachment;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "shop.invoice.delivery.enabled", havingValue = "true")
public class InvoiceDeliveryServiceImpl implements InvoiceDeliveryService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String OWNER_TYPE = "INVOICE_DELIVERY";
    private static final String PROOF_LINK = "INVOICE_PROOF_LINK_V1";
    private static final String DETAIL_LINK = "INVOICE_DETAIL_LINK_V1";
    private static final String PROOF_ATTACHMENT = "INVOICE_PROOF_ATTACHMENT_V1";
    private static final String DETAIL_ATTACHMENT = "INVOICE_DETAIL_ATTACHMENT_V1";
    private static final String TO_MAIL_LOG = "INVOICE_DELIVERY";
    private static final String PUBLIC_PATH = "/public/invoice-deliveries/open";

    private final InvoiceMapper invoices;
    private final InvoiceDeliveryMapper deliveries;
    private final SecretVault vault;
    private final PrintQueryService printing;
    private final PrintExportFacade exports;
    private final ObjectProvider<MailPort> mailProvider;
    private final OtpSendService otp;
    private final String origin;
    private final boolean mailStub;

    public InvoiceDeliveryServiceImpl(InvoiceMapper invoices, InvoiceDeliveryMapper deliveries, SecretVault vault,
            PrintQueryService printing, PrintExportFacade exports, ObjectProvider<MailPort> mailProvider, OtpSendService otp,
            @Value("${shop.invoice.delivery.public-origin}") String origin,
            @Value("${shop.mail.stub:true}") boolean mailStub) {
        URI uri = URI.create(origin);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("shop.invoice.delivery.public-origin requires a public HTTPS API base");
        }
        this.origin = origin.replaceAll("/+$", "");
        this.invoices = invoices;
        this.deliveries = deliveries;
        this.vault = vault;
        this.printing = printing;
        this.exports = exports;
        this.mailProvider = mailProvider;
        this.otp = otp;
        this.mailStub = mailStub;
    }

    @Override
    public PageData<Delivery> list(Scope scope, String invoiceNo, int page, int size) {
        invoice(scope, invoiceNo, false);
        var slice = deliveries.selectPage(new Page<>(page, size), scoped(scope, invoiceNo).orderByDesc("id"));
        return PageData.of(slice.getRecords().stream().map(InvoiceDeliveryServiceImpl::view).toList(),
                slice.getTotal(), slice.getCurrent(), slice.getSize());
    }

    @Override
    @Transactional
    public Delivery enqueue(Scope scope, String invoiceNo, String exportNo, String email, String reason, String key) {
        var invoice = invoice(scope, invoiceNo, true);
        String address = OtpKeys.normalize(EMAIL, email);
        String requestHash = PrintHashes.sha256("SEND\n" + exportNo + "\n" + address + "\n" + reason);
        var prior = replay(scope, invoiceNo, key, requestHash);
        if (prior != null) return view(prior);
        readyInvoice(invoice);
        if (deliveries.selectCount(scoped(scope, invoiceNo).isNull("parent_delivery_no")) != 0) throw badState();
        var template = attachmentTemplate(InvoiceDocumentServiceImpl.sourceType(invoice));
        var row = create(scope, invoice, exportNo, template, null, Masks.email(address), reason, key, requestHash);
        row.setRecipientSecretRef(vault.seal(new KeyOwner(SecretNamespace.PLATFORM, OWNER_TYPE, row.getId()),
                address.getBytes(StandardCharsets.UTF_8)).blobId());
        deliveries.updateById(row);
        return view(row);
    }

    @Override
    @Transactional
    public Delivery resend(Scope scope, String invoiceNo, String deliveryNo, String reason,
            boolean acknowledgeUnknown, String key) {
        var invoice = invoice(scope, invoiceNo, true);
        String requestHash = PrintHashes.sha256("RESEND\n" + deliveryNo + "\n" + acknowledgeUnknown + "\n" + reason);
        var prior = replay(scope, invoiceNo, key, requestHash);
        if (prior != null) return view(prior);
        readyInvoice(invoice);
        var parent = deliveries.selectOne(scoped(scope, invoiceNo).eq("delivery_no", deliveryNo));
        if (parent == null || !Set.of("ACCEPTED", "FAILED", "UNKNOWN").contains(parent.getStatus())
                || ("UNKNOWN".equals(parent.getStatus()) && !acknowledgeUnknown)
                || deliveries.selectCount(scoped(scope, invoiceNo).in("status", "PENDING", "SENDING")) != 0) {
            throw badState();
        }
        var template = attachmentTemplate(documentType(parent.getTemplateVersion()));
        var row = create(scope, invoice, parent.getExportNo(), template, parent.getDeliveryNo(),
                parent.getRecipientMask(), reason, key, requestHash);
        row.setRecipientSecretRef(parent.getRecipientSecretRef());
        deliveries.updateById(row);
        return view(row);
    }

    private IvcInvoiceDelivery create(Scope scope, IvcInvoice invoice, String exportNo, String template, String parentNo,
            String mask, String reason, String key, String requestHash) {
        if (documentType(template) != InvoiceDocumentServiceImpl.sourceType(invoice)) throw badState();
        LocalDateTime expiry;
        try {
            expiry = LocalDateTime.ofInstant(documentExpiry(invoice.getInvoiceNo(), exportNo, template, scope), ZoneOffset.UTC);
        } catch (PrintFailure failure) {
            throw InvoiceDocumentServiceImpl.exportFailure(failure);
        }
        var row = new IvcInvoiceDelivery();
        row.setTenantNo(scope.tenantNo());
        row.setEntityNo(scope.entityNo());
        row.setStoreNo(scope.storeNo());
        row.setDeliveryNo(BizKey.next(BizKey.INVOICE_DELIVERY));
        row.setInvoiceNo(invoice.getInvoiceNo());
        row.setApplicationNo(invoice.getApplicationNo());
        row.setApplicationRevisionNo(invoice.getApplicationRevisionNo());
        row.setExportNo(exportNo);
        row.setParentDeliveryNo(parentNo);
        row.setRecipientMask(mask);
        row.setActorNo(scope.actorNo());
        row.setStatus("PENDING");
        row.setReason(reason);
        row.setIdempotencyHash(PrintHashes.sha256(key));
        row.setRequestHash(requestHash);
        row.setTemplateVersion(template);
        row.setCreatedAt(now());
        row.setExpiresAt(expiry);
        deliveries.insert(row);
        return row;
    }

    @Override
    public void dispatchDue() {
        var time = now();
        deliveries.update(null, new UpdateWrapper<IvcInvoiceDelivery>().eq("status", "SENDING")
                .lt("started_at", time.minusMinutes(10)).set("status", "UNKNOWN")
                .set("finished_at", time).set("failure_code", "SMTP_RESULT_UNKNOWN"));
        var pending = deliveries.selectList(new QueryWrapper<IvcInvoiceDelivery>().eq("status", "PENDING")
                .orderByAsc("id").last("LIMIT 20"));
        for (var row : pending) dispatch(row);
    }

    private void dispatch(IvcInvoiceDelivery row) {
        if (deliveries.update(null, state(row, "PENDING").set("status", "SENDING").set("started_at", now())) != 1) return;
        var scope = scopeOf(row);
        IvcInvoice invoice;
        try {
            invoice = invoice(scope, row.getInvoiceNo(), false);
            readyInvoice(invoice);
            documentExpiry(row.getInvoiceNo(), row.getExportNo(), row.getTemplateVersion(), scope);
        } catch (BizException | PrintFailure unavailable) {
            finish(row, "CANCELLED", "INVOICE_OR_PDF_UNAVAILABLE", null);
            return;
        }
        var mail = mailStub ? null : mailProvider.getIfAvailable();
        if (mail == null) {
            finish(row, "FAILED", "MAIL_CHANNEL_DISABLED", null);
            return;
        }
        Draft draft;
        try {
            draft = draft(row, scope, invoice);
        } catch (PrintFailure unavailable) {
            finish(row, "CANCELLED", "INVOICE_OR_PDF_UNAVAILABLE", null);
            return;
        }
        if (deliveries.update(null, state(row, "SENDING").set("token_hash", draft.tokenHash())
                .set("content_hash", draft.contentHash())) != 1) return;
        String recipient = recipient(row);
        try {
            var result = draft.attachment() == null
                    ? mail.send(recipient, draft.subject(), draft.body(), TO_MAIL_LOG, row.getActorNo())
                    : mail.sendAttachment(recipient, draft.subject(), draft.body(), draft.attachment(),
                            TO_MAIL_LOG, row.getActorNo());
            if (result.providerMsgId() == null) finish(row, "UNKNOWN", "SMTP_RECEIPT_MISSING", null);
            else finish(row, "ACCEPTED", null, result.providerMsgId());
        } catch (MailPort.MailException responseUnknown) {
            finish(row, "UNKNOWN", "SMTP_RESULT_UNKNOWN", null);
        }
    }

    private Draft draft(IvcInvoiceDelivery row, Scope scope, IvcInvoice invoice) {
        var template = row.getTemplateVersion();
        boolean detail = documentType(template) == ExportSourceType.INVOICE_DETAIL;
        String subject = detail ? "NearGo 電子發票明細" : "NearGo 電子發票證明聯";
        if (template.equals(PROOF_ATTACHMENT) || template.equals(DETAIL_ATTACHMENT)) {
            byte[] pdf = exports.readVerified(row.getExportNo(), printActor(scope));
            String fiscalNumber = invoice.getFiscalNumber().replaceAll("[^A-Za-z0-9_-]", "_");
            String filename = fiscalNumber + (detail ? "-detail.pdf" : "-proof.pdf");
            String body = detail ? "附件為電子發票交易明細。發票已存入手機條碼載具，交易明細僅供查閱。"
                    : "附件為電子發票證明聯，請下載 PDF 查閱。";
            String hash = PrintHashes.sha256(subject + "\n" + body + "\n" + filename + "\n" + PrintHashes.sha256(pdf));
            return new Draft(subject, body, new Attachment(filename, "application/pdf", pdf), null, hash);
        }
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String intro = detail ? "請開啟下方連結，完成電子郵件驗證後下載發票明細。此檔案僅供查閱，不是電子發票證明聯。"
                : "請開啟下方連結，完成電子郵件驗證後下載發票。";
        String body = intro + "\n\n"
                + origin + PUBLIC_PATH + "#" + token + "\n\n連結有效至發票 PDF 保存期限。";
        return new Draft(subject, body, null, PrintHashes.sha256(token), PrintHashes.sha256(subject + "\n" + body));
    }

    private record Draft(String subject, String body, Attachment attachment, String tokenHash, String contentHash) {}

    private void finish(IvcInvoiceDelivery row, String status, String failureCode, String messageId) {
        deliveries.update(null, state(row, "SENDING").set("status", status)
                .set("failure_code", failureCode).set("provider_message_id", messageId).set("finished_at", now()));
    }

    @Override
    public void challenge(String token, String ip) {
        var row = verifiedLink(token);
        otp.send(new SendCommand(OtpScenes.INVOICE_ARTIFACT_ACCESS, EMAIL, recipient(row), row.getDeliveryNo(),
                "zh-Hant", Map.of(), null), new OtpContext(OtpEntries.INVOICE_ARTIFACT, ip, null));
    }

    @Override
    @Transactional
    public String download(String token, String code, String ip) {
        var row = verifiedLink(token);
        if (!otp.verifyAndConsume(new VerifyCommand(OtpScenes.INVOICE_ARTIFACT_ACCESS, EMAIL, recipient(row),
                row.getDeliveryNo(), code), new OtpContext(OtpEntries.INVOICE_ARTIFACT, ip, null))) {
            throw BizException.of(ErrorCode.OTP_INVALID);
        }
        var scope = scopeOf(row);
        readyInvoice(invoice(scope, row.getInvoiceNo(), true));
        var url = exports.download(row.getExportNo(), printActor(scope)).url();
        deliveries.update(null, new UpdateWrapper<IvcInvoiceDelivery>().eq("id", row.getId())
                .isNull("opened_at").set("opened_at", now()));
        return url.isAbsolute() ? url.toASCIIString() : origin + url;
    }

    private IvcInvoiceDelivery verifiedLink(String token) {
        var row = deliveries.selectOne(new QueryWrapper<IvcInvoiceDelivery>().eq("token_hash", PrintHashes.sha256(token))
                .in("status", "ACCEPTED", "UNKNOWN").gt("expires_at", now()));
        if (row == null) throw badState();
        var scope = scopeOf(row);
        readyInvoice(invoice(scope, row.getInvoiceNo(), false));
        try {
            documentExpiry(row.getInvoiceNo(), row.getExportNo(), row.getTemplateVersion(), scope);
        } catch (PrintFailure failure) {
            throw badState();
        }
        return row;
    }

    private Instant documentExpiry(String invoiceNo, String exportNo, String template, Scope scope) {
        var source = documentType(template);
        return source == ExportSourceType.INVOICE_PROOF
                ? printing.originalInvoiceProofExpiry(invoiceNo, exportNo, printActor(scope))
                : printing.invoiceDeliveryDocumentExpiry(invoiceNo, exportNo, source, printActor(scope));
    }

    private static ExportSourceType documentType(String template) {
        return switch (template) {
            case PROOF_LINK, PROOF_ATTACHMENT -> ExportSourceType.INVOICE_PROOF;
            case DETAIL_LINK, DETAIL_ATTACHMENT -> ExportSourceType.INVOICE_DETAIL;
            default -> throw badState();
        };
    }

    private static String attachmentTemplate(ExportSourceType source) {
        return source == ExportSourceType.INVOICE_DETAIL ? DETAIL_ATTACHMENT : PROOF_ATTACHMENT;
    }

    private IvcInvoiceDelivery replay(Scope scope, String invoiceNo, String key, String hash) {
        var existing = deliveries.selectOne(scoped(scope, invoiceNo).eq("idempotency_hash", PrintHashes.sha256(key)));
        if (existing != null && !existing.getRequestHash().equals(hash)) throw badState();
        return existing;
    }

    private IvcInvoice invoice(Scope scope, String invoiceNo, boolean lock) {
        return InvoiceDocumentServiceImpl.scopedInvoice(invoices, scope, invoiceNo, lock);
    }

    private static void readyInvoice(IvcInvoice invoice) {
        if (!"ISSUED".equals(invoice.getStatus())) throw badState();
    }

    private String recipient(IvcInvoiceDelivery row) {
        return new String(vault.open(SecretNamespace.PLATFORM, new SecretRef(row.getRecipientSecretRef())),
                StandardCharsets.UTF_8);
    }

    private static QueryWrapper<IvcInvoiceDelivery> scoped(Scope scope, String invoiceNo) {
        return new QueryWrapper<IvcInvoiceDelivery>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo()).eq("invoice_no", invoiceNo);
    }

    private static UpdateWrapper<IvcInvoiceDelivery> state(IvcInvoiceDelivery row, String status) {
        return new UpdateWrapper<IvcInvoiceDelivery>().eq("id", row.getId()).eq("status", status);
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS); }

    private static Scope scopeOf(IvcInvoiceDelivery row) {
        return new Scope(row.getTenantNo(), row.getEntityNo(), row.getStoreNo(), row.getActorNo());
    }

    private static PrintActor printActor(Scope scope) {
        return new PrintActor(scope.tenantNo(), scope.entityNo(), scope.storeNo(), "SYSTEM", scope.actorNo(),
                null, Set.of("SOURCE_INVOICE_READ", "biz:print:export"));
    }

    private static BizException badState() { return BizException.of(ErrorCode.INVOICE_STATE_INVALID); }

    private static Delivery view(IvcInvoiceDelivery row) {
        return new Delivery(row.getDeliveryNo(), row.getExportNo(), row.getParentDeliveryNo(), row.getApplicationNo(),
                row.getApplicationRevisionNo(), row.getRecipientMask(), row.getStatus(), row.getProviderMessageId(),
                row.getReason(), row.getActorNo(), row.getFailureCode(), row.getCreatedAt(), row.getStartedAt(),
                row.getFinishedAt(), row.getOpenedAt(), documentType(row.getTemplateVersion()).name());
    }
}
