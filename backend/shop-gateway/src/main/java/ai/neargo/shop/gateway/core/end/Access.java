package ai.neargo.shop.gateway.core.end;

/** 一个地址的放行方式。 */
public enum Access {
    /** 不用登录。 */
    PUBLIC,
    /** 要登录，不要求权限码。 */
    LOGIN,
    /** 要登录，且要满足权限式。 */
    POINT,
    /** 权限表达式用到了方法参数之类提前判不了的东西：提前只要求登录，留给方法上的注解判。 */
    DEFER
}
