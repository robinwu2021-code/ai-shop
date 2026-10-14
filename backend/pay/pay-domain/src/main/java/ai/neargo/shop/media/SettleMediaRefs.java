package ai.neargo.shop.media;

import org.springframework.stereotype.Component;

import java.util.List;

import static ai.neargo.shop.media.MediaRefColumn.of;

/**
 * shop-settle 把图片地址放在哪些列里。
 *
 * <p>只有一列，但它是最不能删错的一类：<b>采购发票是记账凭证</b>，
 * 有留存期要求，删掉之后对不上账的时间点可能在一年以后。
 */
@Component
public class SettleMediaRefs implements MediaRefSource {

    @Override
    public List<MediaRefColumn> columns() {
        return List.of(
                of("stl_purchase_invoice", "image_url", "采购发票 · 影像", "invoice_no"));
    }
}
