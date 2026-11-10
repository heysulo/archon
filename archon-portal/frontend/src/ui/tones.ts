/** What each colour means, the same on every page. */
export const TONE = {
  ok: "green",
  warn: "yellow",
  /** Close to a limit, but still working. */
  strained: "orange",
  danger: "red",
  idle: "gray",
} as const;

export type Tone = (typeof TONE)[keyof typeof TONE];
