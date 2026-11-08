package dev.heysulo.archon.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;

/** A configuration as stored, with whether it was deleted and the revision of its last change. */
record StoredConfig(ConfigEntry entry, boolean deleted, long revision) {
}
