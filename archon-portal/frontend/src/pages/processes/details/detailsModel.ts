import type { Config, ConfigInput } from "../../configs/configsApi";

/** Where a configuration an application sees comes from, as `Global`, `Group Shop` or `Application`. */
export function sourceLabel(config: Pick<Config, "level" | "group">): string {
  switch (config.level) {
    case "GLOBAL":
      return "Global";
    case "GROUP":
      return `Group ${config.group}`;
    case "APPLICATION":
      return "Application";
  }
}

/** A new application-level configuration of the same name, starting from the value it overrides. */
export function overridePreset(config: Config, group: string, application: string): ConfigInput {
  return { level: "APPLICATION", group, application, name: config.name, type: config.type, value: config.value,
    readOnly: config.readOnly, description: config.description };
}

/** What editing a configuration reaches beyond the application being looked at, if anything. */
export function editNotice(config: Config, app: string): string | undefined {
  switch (config.level) {
    case "GLOBAL":
      return `This is the global value: changing it changes it for every application without its own, not only ${
        app}. To change it for ${app} alone, override it instead.`;
    case "GROUP":
      return `This is group ${config.group}'s value: changing it changes it for every application in the group. To `
        + `change it for ${app} alone, override it instead.`;
    case "APPLICATION":
      return undefined;
  }
}
