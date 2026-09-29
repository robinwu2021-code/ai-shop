package ai.neargo.shop.elec.service;

import ai.neargo.shop.elec.dto.SupplierDtos.RegisterReq;
import ai.neargo.shop.elec.dto.SupplierDtos.RenewResult;
import ai.neargo.shop.elec.dto.SupplierDtos.StockView;
import ai.neargo.shop.elec.dto.SupplierDtos.SupplierView;

import java.util.List;

/** 供应商：入驻、看自己的库存、续期。 */
public interface ElecSupplierService {

    /** @return 还没入驻为 null */
    SupplierView mine(String userNo);

    /**
     * 成为供应商：<b>一点就成</b>，不审核。唯一前提是绑过手机号（平台要联系他）。
     * req 里的字段都可以空，之后用 {@link #update} 补。提交后企业微信通知平台
     */
    SupplierView register(String userNo, RegisterReq req);

    /** 补资料。空字段 = 不改 */
    SupplierView update(String userNo, RegisterReq req);

    /**
     * @param filter ALL / EXPIRING（7 天内到期）/ EXPIRED
     */
    List<StockView> stocks(String userNo, String keyword, String filter, int page, int size);

    /** 「仍有货」：把在售库存全部续期 */
    RenewResult renew(String userNo);

    /**
     * 库存快到期的站内信（每天跑一次）。<b>只进站内信、不发订阅消息</b>；同一家<b>一周最多一条</b>。
     *
     * @return 这次提醒了几家
     */
    int remindExpiring();
}
