import { fireEvent, screen, waitFor } from "@testing-library/react";
import { mockPortal, renderWith } from "../../test/render";
import { JoinTokensModal } from "./JoinTokensModal";
import { joinEnvironment, tokenState, type JoinToken } from "./joinTokensApi";

const TARGET = { group: "Shop", name: "Cart", description: "", builtIn: false };
const FINGERPRINT = "ab".repeat(32);
const TOKEN: JoinToken = { id: 7, group: "Shop", name: "Cart", description: "Staging", reusable: false,
  useCount: 0, createdBy: "admin", createdAt: "2026-11-09T10:00:00Z" };

describe("JoinTokensModal", () => {
  it("creates a token and shows it once with what to start instances with", async () => {
    const calls = mockPortal({
      "GET /api/tls": { body: { mode: "optional", fingerprint: FINGERPRINT } },
      "GET /api/applications/Shop/Cart/tokens": { body: [TOKEN] },
      "POST /api/applications/Shop/Cart/tokens": { status: 201,
        body: { token: "ajt_secret", record: { ...TOKEN, id: 8 } } },
    });
    renderWith(<JoinTokensModal target={TARGET} onClose={() => {}} />);
    expect(await screen.findByText("Staging")).toBeInTheDocument();
    expect(await screen.findByText(FINGERPRINT)).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("Reusable"));
    fireEvent.click(screen.getByRole("button", { name: "Create token" }));
    expect(await screen.findByText(/ARCHON_JOIN_TOKEN=ajt_secret/)).toBeInTheDocument();
    await waitFor(() => expect(calls.find((call) => call.method === "POST")?.body)
      .toEqual({ description: "", reusable: true, expiresInHours: 24 }));
  });

  it("revokes a token", async () => {
    const calls = mockPortal({
      "GET /api/tls": { body: { mode: "required", fingerprint: FINGERPRINT } },
      "GET /api/applications/Shop/Cart/tokens": { body: [TOKEN] },
      "DELETE /api/applications/Shop/Cart/tokens/7": { status: 204 },
    });
    renderWith(<JoinTokensModal target={TARGET} onClose={() => {}} />);
    fireEvent.click(await screen.findByLabelText("Revoke Staging"));
    await waitFor(() => expect(calls.some((call) => call.method === "DELETE")).toBe(true));
  });
});

describe("join tokens", () => {
  it("writes the environment an instance joins with", () => {
    expect(joinEnvironment("required", "ff", "ajt_x"))
      .toBe("ARCHON_TLS=required\nARCHON_CA_FINGERPRINT=ff\nARCHON_JOIN_TOKEN=ajt_x");
  });

  it("tells active, used and expired tokens apart", () => {
    const now = new Date("2026-11-09T12:00:00Z");
    expect(tokenState(TOKEN, now).label).toBe("Active");
    expect(tokenState({ ...TOKEN, usedAt: "2026-11-09T11:00:00Z" }, now).label).toBe("Used");
    expect(tokenState({ ...TOKEN, reusable: true, usedAt: "2026-11-09T11:00:00Z" }, now).label).toBe("Active");
    expect(tokenState({ ...TOKEN, expiresAt: "2026-11-09T11:00:00Z" }, now).label).toBe("Expired");
  });
});
