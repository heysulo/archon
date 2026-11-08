/** Matches the portal's rules, so mistakes show before the form is sent. The portal checks again. */
export const MIN_PASSWORD_LENGTH = 10;
export const MAX_PASSWORD_LENGTH = 256;
const USERNAME = /^[a-z0-9][a-z0-9._-]{1,63}$/;

export function passwordError(value: string): string | null {
  if (value.length < MIN_PASSWORD_LENGTH) return `At least ${MIN_PASSWORD_LENGTH} characters`;
  if (value.length > MAX_PASSWORD_LENGTH) return `At most ${MAX_PASSWORD_LENGTH} characters`;
  return null;
}

export function usernameError(value: string): string | null {
  return USERNAME.test(value.trim().toLowerCase())
    ? null
    : "2 to 64 lowercase letters, digits, dots, dashes or underscores";
}
