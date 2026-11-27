import { onHide, onShow } from "@dcloudio/uni-app";
import { onScopeDispose, watch, type WatchStopHandle } from "vue";

/** Load using the restored store on first entry, and refresh on return or store switch. */
export function useMallOrderStore(storeNo: () => string, load: () => Promise<void>) {
  let stop: WatchStopHandle | undefined;
  function detach() { stop?.(); stop = undefined; }
  onShow(() => {
    detach();
    stop = watch(storeNo, store => { if (store) void load(); }, { immediate: true });
  });
  onHide(detach);
  onScopeDispose(detach);
}
