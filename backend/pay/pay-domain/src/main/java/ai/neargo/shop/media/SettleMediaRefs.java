package ai.neargo.shop.media;

import static ai.neargo.shop.media.MediaRefColumn.of;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * shop-settle 把图片地址放在哪些列里。
 *
 * <p>采购发票与正式线下收款凭证均是不可随业务页面删除的记账引用。
 */
@Component
public class SettleMediaRefs implements MediaRefSource {

    @Override
    public List<MediaRefColumn> columns() {
        return List.of(
                of("stl_purchase_invoice", "image_url", "采购发票 · 影像", "invoice_no"),
                of("stl_payment_operation", "evidence_ref", "线下收款 · 凭证", "operation_no"));
    }
}
