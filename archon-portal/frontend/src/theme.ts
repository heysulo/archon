import { Badge, createTheme, Table, type CSSVariablesResolver } from "@mantine/core";

export const theme = createTheme({
  primaryColor: "indigo",
  defaultRadius: "md",
  fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
  fontFamilyMonospace: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  components: {
    // One badge everywhere: as written rather than shouted, and never cut short to "GLO…" in a narrow column
    Badge: Badge.extend({
      defaultProps: { radius: "sm", variant: "light", tt: "none" },
      styles: { root: { overflow: "visible" }, label: { overflow: "visible" } },
    }),
    Table: Table.extend({ defaultProps: { verticalSpacing: "sm", highlightOnHover: true } }),
  },
});

/**
 * Mantine's secondary text and placeholders are too faint on white for small sizes (about 3.3:1). These reach the
 * 4.5:1 that 12 px descriptions need; the dark scheme's defaults already do.
 */
export const cssVariables: CSSVariablesResolver = (mantine) => ({
  variables: {},
  light: {
    "--mantine-color-dimmed": mantine.colors.gray[7],
    "--mantine-color-placeholder": "#6c757d",
  },
  dark: {},
});
