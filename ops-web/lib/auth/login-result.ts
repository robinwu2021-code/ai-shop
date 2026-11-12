import type { LoginResp } from "@/lib/types";

/** The first factor can only enter challenge state; only a complete session can be committed. */
export function acceptLoginResult(
  result: LoginResp,
  onChallenge: (challenge: string, sentTo: string) => void,
  onAuthenticated: (identity: LoginResp) => void,
): void {
  if (result.challenge) {
    onChallenge(result.challenge, result.challengeTo ?? "");
    return;
  }
  if (!result.token?.trim() || !result.username) throw new Error("Login did not return a complete session");
  onAuthenticated(result);
}
