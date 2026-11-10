import { Card } from "@mantine/core";
import type { ReactNode } from "react";

/** The bordered card every table sits in. Narrow screens scroll the table sideways instead of squeezing it. */
export function TableCard({ children, footer }: { children: ReactNode; footer?: ReactNode }) {
  return (
    <Card withBorder padding={0} radius="md">
      <div style={{ overflowX: "auto" }}>{children}</div>
      {footer}
    </Card>
  );
}
