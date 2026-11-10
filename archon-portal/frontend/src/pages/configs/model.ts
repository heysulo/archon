import type { Config, ConfigLevel, ConfigType } from "./configsApi";

/** Who sees a configuration, in words. */
export function scopeOf(config: Pick<Config, "level" | "group" | "application">): string {
  switch (config.level) {
    case "GLOBAL":
      return "Everyone";
    case "GROUP":
      return config.group;
    case "APPLICATION":
      return `${config.group} / ${config.application}`;
  }
}

/** Each level's colour, the same wherever a configuration's level shows. */
export const LEVEL_COLORS: Record<ConfigLevel, string> = { GLOBAL: "indigo", GROUP: "teal", APPLICATION: "orange" };

export const LEVEL_LABELS: Record<ConfigLevel, string> = {
  GLOBAL: "Global",
  GROUP: "Group",
  APPLICATION: "Application",
};

/** Each type as people say it, wherever a configuration's type shows. */
export const TYPE_LABELS: Record<ConfigType, string> = {
  STRING: "Text",
  INTEGER: "Whole number",
  DECIMAL: "Decimal",
  BOOLEAN: "True or false",
};

/** Checks a value against its type as archon-config will, so mistakes show before saving. */
export function valueError(type: ConfigType, value: string): string | null {
  const trimmed = value.trim();
  switch (type) {
    case "STRING":
      return null;
    case "INTEGER":
      return /^[-+]?\d+$/.test(trimmed) ? null : "A whole number";
    case "DECIMAL":
      return trimmed !== "" && !Number.isNaN(Number(trimmed)) ? null : "A number";
    case "BOOLEAN":
      return trimmed === "true" || trimmed === "false" ? null : "true or false";
  }
}

const IDENTIFIER = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

export function identifierError(value: string): string | null {
  return IDENTIFIER.test(value.trim()) ? null : "Letters, digits, dots, dashes or underscores";
}
