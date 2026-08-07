// 端能力：扫码 —— B 端自提点核销台用（ADR-004 后由入驻商家承接，不再是团长）。
export function scanCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.scanCode({
      onlyFromCamera: false,
      success: (res) => resolve(res.result || ""),
      fail: (e) => reject(new Error(e.errMsg || "扫码取消")),
    });
  });
}
