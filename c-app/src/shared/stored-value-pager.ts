import { shallowReactive } from "vue";
interface CursorPage<T> { records: T[]; nextCursor: string | null; hasMore: boolean }
/** 每次换条件推进代次；旧首屏、旧翻页及其 finally 都不能覆盖新状态。 */
export function createStoredValuePager<T, Q, P extends CursorPage<T>>(
  fetch: (query: Q, cursor?: string) => Promise<P>,
) {
  const state = shallowReactive({ records: [] as T[], page: null as P | null,
    cursor: null as string | null, hasMore: false, loading: false, moreLoading: false,
    loaded: false, failed: false, moreFailed: false, errorCode: 0 });
  let generation = 0;
  let query: Q;
  async function reset(next: Q) {
    const request = ++generation;
    query = next;
    Object.assign(state, { records: [], page: null, cursor: null, hasMore: false,
      loading: true, moreLoading: false, loaded: false, failed: false, moreFailed: false, errorCode: 0 });
    try {
      const page = await fetch(next);
      if (request !== generation) return;
      Object.assign(state, { page, records: page.records, cursor: page.nextCursor, hasMore: page.hasMore });
    } catch (error) {
      if (request !== generation) return;
      state.failed = true;
      state.errorCode = Number((error as { code?: number }).code || 0);
    } finally {
      if (request === generation) { state.loading = false; state.loaded = true; }
    }
  }
  async function more() {
    if (state.loading || state.moreLoading || !state.hasMore || !state.cursor) return;
    const request = generation;
    state.moreLoading = true; state.moreFailed = false;
    try {
      const page = await fetch(query, state.cursor);
      if (request !== generation) return;
      Object.assign(state, { page, records: [...state.records, ...page.records],
        cursor: page.nextCursor, hasMore: page.hasMore });
    } catch {
      if (request === generation) state.moreFailed = true;
    } finally {
      if (request === generation) state.moreLoading = false;
    }
  }
  function invalidate() { ++generation; }
  return { state, reset, more, invalidate };
}
