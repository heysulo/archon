import { safeNext } from "./safeNext";

describe("safeNext", () => {
  it("returns to pages on this site", () => {
    expect(safeNext("/users")).toBe("/users");
    expect(safeNext("/processes?q=ping")).toBe("/processes?q=ping");
  });

  // An attacker could send someone a sign-in link that lands them on a look-alike site after they sign in
  it("never leaves the site", () => {
    expect(safeNext("//evil.example")).toBe("/processes");
    expect(safeNext("https://evil.example")).toBe("/processes");
    expect(safeNext("/\\evil.example")).toBe("/processes");
    expect(safeNext(null)).toBe("/processes");
  });
});
