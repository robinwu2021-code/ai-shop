package ai.neargo.shop.elec.svc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 通知点开后落到的小程序页面。<b>路由名以前端 elec-app 的 pages.json 为准</b>，全部收在这一处。
 *
 * <p>前缀随发布形态变（见 TDD-元器件-前端独立与通知矩阵 §1.2）：
 * <ul>
 *   <li>并进 c-app 测试（现在）：{@code c-app/src/pkg-elec → elec-app/src}，页面在 {@code pkg-elec/pages/…}</li>
 *   <li>独立小程序（目标）：页面在 {@code pages/…}</li>
 * </ul>
 * 切形态时改 {@code ELEC_PAGE_PREFIX}，不改代码。此前三处各写各的（{@code pkg-elec/rfq/index}、
 * {@code pkg-elec/supplier-rfqs/index}），两种形态下都不存在 —— 点开通知落到空页，零报错。
 */
@Component
public class ElecPages {

    private final String prefix;

    public ElecPages(@Value("${elec.page-prefix:pkg-elec/pages/}") String prefix) {
        String p = prefix == null ? "" : prefix.trim();
        this.prefix = p.isEmpty() || p.endsWith("/") ? p : p + "/";
    }

    /** 买家的询价详情 */
    public String rfq(String rfqNo) {
        return prefix + "rfq/index?rfqNo=" + rfqNo;
    }

    /** 供应商收到的求购列表。@param status 为空 = 全部 */
    public String dispatches(String status) {
        return prefix + "dispatches/index" + (status == null ? "" : "?status=" + status);
    }

    /** 供应商的库存。@param filter ALL / EXPIRING / EXPIRED */
    public String stocks(String filter) {
        return prefix + "stocks/index?filter=" + filter;
    }
}
