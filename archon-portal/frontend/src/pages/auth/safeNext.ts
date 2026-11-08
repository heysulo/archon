/**
 * Where to go after signing in. Only paths on this site: "//evil.example" or "https://..." would send the user
 * somewhere else straight after they typed their password.
 */
export function safeNext(next: string | null): string {
  if (!next || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) {
    return "/processes";
  }
  return next;
}
