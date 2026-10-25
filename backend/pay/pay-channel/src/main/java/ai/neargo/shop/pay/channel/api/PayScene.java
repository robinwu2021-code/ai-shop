package ai.neargo.shop.pay.channel.api;

/** 支付场景（渠道接入统一封装设计 §2.1）。{@link #ANY} 用于与场景无关的操作：查单、关单、退款、对账、进件 */
public enum PayScene {
    /** 跳转收银台：蓝新 MPG、LINE Pay、PayBy 收银台 */
    ONLINE_REDIRECT,
    /** 端上 SDK：微信 JSAPI / APP、支付宝 APP */
    ONLINE_SDK,
    /** 线上二维码：微信 NATIVE */
    ONLINE_QR,
    /** 刷卡：读卡套件加密数据 */
    IN_STORE_CARD,
    /** 扫顾客付款码 */
    IN_STORE_SCAN_CUSTOMER,
    /** 终端屏显码 */
    IN_STORE_SHOW_QR,
    ANY
}
