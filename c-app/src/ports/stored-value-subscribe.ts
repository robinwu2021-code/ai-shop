// Invoke within the tap stack; awaiting before this port loses WeChat authorization.
export function requestStoredValueSubscription(options: Parameters<typeof uni.requestSubscribeMessage>[0]): boolean {
  // #ifdef MP-WEIXIN
  uni.requestSubscribeMessage(options);
  return true;
  // #endif
  // eslint-disable-next-line no-unreachable
  return false;
}
