/** Keep internal references intact. The backend resolves exact orders and stored payment associations. */
export function orderNoOf(raw: string | undefined | null): string {
  return raw || "";
}
