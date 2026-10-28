package ai.neargo.shop.pay.terminal.service;

import ai.neargo.shop.common.ErrorCode;

/**
 * 店长授权码（pay-guard 开发计划 M6.4；代码清单 §4.2）：终端撤销 / 退款要店长当场输入的码。商家端设置，库里只存
 * 加盐慢哈希（PBKDF2-HMAC-SHA256）；一家店连续输错 {@value #MAX_FAILURES} 次锁 {@code 15} 分钟 —— 锁的是这家店的全部授权码，
 * 不让人在终端上逐个店长去试。授权码是业务输入、不是调用方身份，所以在业务层验，不在守卫里。
 */
public interface ManagerCodeService {

    /** 设置 / 重置：6–12 位数字。重置即覆盖旧码，并解除本店的锁定 */
    void set(Long storeId, Long managerAccountId, String code);

    /** 验码：对上返回店长账号 id；对不上 / 锁定中 → {@link ErrorCode#FORBIDDEN}（不说是哪种） */
    Long verify(Long storeId, String code);
}
