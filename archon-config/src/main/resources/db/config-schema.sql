-- archon-config's tables in the archon schema. archon-config runs this at every start; it only creates what is
-- missing and never changes existing rows. Written to run on MySQL 8 and, for tests, H2 in MySQL mode.
-- Timestamps are UTC, set by archon-config.

-- level is GLOBAL, GROUP or APPLICATION. app_group is empty at the global level; app_name is empty unless the
-- level is APPLICATION. value_type is STRING, INTEGER, DECIMAL or BOOLEAN, and config_value holds its canonical form.
-- Deleted configurations are kept, marked deleted, so every replica notices the deletion through its revision.
CREATE TABLE IF NOT EXISTS configurations (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    level       VARCHAR(16)   NOT NULL,
    app_group   VARCHAR(128)  NOT NULL,
    app_name    VARCHAR(128)  NOT NULL,
    name        VARCHAR(128)  NOT NULL,
    value_type  VARCHAR(16)   NOT NULL,
    config_value TEXT         NOT NULL,
    read_only   BOOLEAN       NOT NULL,
    description VARCHAR(512)  NOT NULL,
    deleted     BOOLEAN       NOT NULL,
    revision    BIGINT        NOT NULL,
    created_at  DATETIME(3)   NOT NULL,
    updated_at  DATETIME(3)   NOT NULL,
    updated_by  VARCHAR(128)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (level, app_group, app_name, name)
);

-- One row: the revision of the newest change. Every write raises it in the same transaction and stamps the
-- configuration with it, so readers can ask for everything changed since the revision they last saw.
CREATE TABLE IF NOT EXISTS configuration_revision (
    id       INT    NOT NULL,
    revision BIGINT NOT NULL,
    PRIMARY KEY (id)
);

INSERT IGNORE INTO configuration_revision (id, revision) VALUES (1, 0);

-- archon-config serves its own service through the registry, which re-reads services every 10 seconds. The row is
-- set on every start, so it matches this release. Port offset 0 is archon-config's: offsets are unique across all
-- services.
INSERT INTO services (id, name, port_offset, app_group, app_name, mode)
VALUES ('archon-config', 'Archon configuration', 0, 'Archon', 'Config', 'PRIMARY_MIRROR')
ON DUPLICATE KEY UPDATE name = 'Archon configuration', port_offset = 0, app_group = 'Archon', app_name = 'Config',
                        mode = 'PRIMARY_MIRROR';
