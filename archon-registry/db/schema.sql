-- Archon's schema. Safe to run again: it only creates what is missing and never overwrites a service.
-- Run as admin. There is no MySQL client on the workstation, so run one in the cluster:
--
--   kubectl run mysql-client --rm -i --restart=Never --image=mysql:8.4 --env=MYSQL_PWD="$MYSQL_PWD" -- \
--     mysql -h mysql.heysulo.internal -u admin < archon-registry/db/schema.sql
--
-- The registry's read-only user is created by hand, because it involves a secret. See SCHEMA.md.

-- utf8mb4_bin keeps comparisons case sensitive: service ids are matched exactly, so 'ping' and 'Ping' differ.
CREATE DATABASE IF NOT EXISTS archon CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

USE archon;

-- Services applications can start and look up. Registries read this table when they start and again every 10
-- seconds, so changes, such as those made in the portal, take effect without a restart.
CREATE TABLE IF NOT EXISTS services (
    -- What applications start and look the service up by. Case sensitive.
    id          VARCHAR(128)                            NOT NULL,
    name        VARCHAR(255)                            NOT NULL,
    -- Added to the starting instance's ARCHON_BASE_PORT to give the service's port
    port_offset INT UNSIGNED                            NOT NULL,
    -- The application definition allowed to start the service, whatever an instance's rank.
    -- Prefixed because GROUP is a reserved word.
    app_group   VARCHAR(128)                            NOT NULL,
    app_name    VARCHAR(128)                            NOT NULL,
    mode        ENUM ('PRIMARY_MIRROR', 'LOAD_BALANCE') NOT NULL,
    -- Audit only. The registry ignores these.
    created_at  TIMESTAMP                               NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP                               NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- Two services of one application with the same offset would bind the same port on every instance
    UNIQUE KEY uq_services_app_port (app_group, app_name, port_offset),
    CONSTRAINT chk_services_id_not_blank CHECK (CHAR_LENGTH(TRIM(id)) > 0),
    CONSTRAINT chk_services_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_services_app_group_not_blank CHECK (CHAR_LENGTH(TRIM(app_group)) > 0),
    CONSTRAINT chk_services_app_name_not_blank CHECK (CHAR_LENGTH(TRIM(app_name)) > 0),
    CONSTRAINT chk_services_port_offset_range CHECK (port_offset <= 65534)
) ENGINE = InnoDB;
