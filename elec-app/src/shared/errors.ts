// 元器件的几个业务码，端上**按码分流**（TDD-元器件-接口总览与双角色 §三「错误码在两面的处理」）。
// 只有 toast 的话，「没绑手机号」「你还不是供应商」这种有明确下一步的错，人会对着一行字不知道该干嘛。
import { ApiError } from "@shared/net/http-client";
import { ROUTES, withQuery } from "./routes";
import { errMsg, toast } from "@/api";

/** 没绑手机号：询价与成为供应商都要 */
export const E_NEED_PHONE = 90001;
/** 不是供应商（供应商面） */
export const E_NOT_SUPPLIER = 90002;
/** 供应商被暂停：只关供应商面，买家面照常 */
export const E_SUSPENDED = 90004;

export function codeOf(e: unknown): number | null {
  return e instanceof ApiError ? e.code : null;
}

/** 按码给出下一步；认不出的码就是一行提示 */
export function handleElecError(e: unknown): void {
  switch (codeOf(e)) {
    case E_NEED_PHONE:
      uni.navigateTo({ url: withQuery(ROUTES.login, { need: "phone" }) });
      return;
    case E_NOT_SUPPLIER:
      uni.redirectTo({ url: ROUTES.supplierJoin });
      return;
    case E_SUSPENDED:
      toast("你的供应商身份已被暂停，这一步做不了。有疑问联系平台");
      return;
    default:
      toast(errMsg(e));
  }
}
