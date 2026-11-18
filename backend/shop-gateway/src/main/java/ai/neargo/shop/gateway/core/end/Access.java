package ai.neargo.shop.gateway.core.end;

/** 一个地址的放行方式。M4 接入提前判权时会再加「需功能点」与「留给方法判」。 */
public enum Access {
    /** 不用登录。 */
    PUBLIC,
    /** 要登录。 */
    LOGIN
}
