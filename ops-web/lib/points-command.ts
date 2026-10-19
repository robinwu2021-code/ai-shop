/** Reuse the same intent ID after a lost response; edits start a distinct intent. */
export function pointsCommand<T extends object>(draft: T, previous?: { body: string; requestId: string }) {
  const body = JSON.stringify(draft);
  const intent = previous?.body === body ? previous : { body, requestId: crypto.randomUUID() };
  return { intent, command: { ...draft, requestId: intent.requestId } };
}
