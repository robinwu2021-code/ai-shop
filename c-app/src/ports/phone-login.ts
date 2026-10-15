/** Native phone authorization is a user-click capability, never launched by page lifecycle or an async callback. */
function supportsNativePhoneAuthorization() {
  let native = false;
  // #ifdef MP-WEIXIN
  native = true;
  // #endif
  return native;
}
export const nativePhoneAuthorization = supportsNativePhoneAuthorization();
