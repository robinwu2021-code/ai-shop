package ai.neargo.shop.pay.checkout.service;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.checkout.entity.PayQrCode;
import java.util.List;

/**
 * 静态码（F45 / F57）：商家给本店生成、停用；运营可风控停用。码值随机 ≥ 16 字节（安全规范 §2.5），不可枚举。
 * 限额由生成方定，但不得超过平台上限（{@code shop.pay.qr.max-single-minor} / {@code max-daily-minor}）。
 */
public interface QrCodeService {

    List<PayQrCode> ofStore(PayCaller caller, Long storeId);

    PayQrCode create(PayCaller caller, Long storeId, CreateQrCmd cmd);

    /** 停用；只能停调用方门店范围内的码（别家的码一律「不存在」） */
    void disable(PayCaller caller, String qrCode);

    /** @param kind STORE / TABLE；@param tableRef 桌台（TABLE 时必填） */
    record CreateQrCmd(String kind, String tableRef, long singleLimitMinor, long dailyLimitMinor) {
    }
}
