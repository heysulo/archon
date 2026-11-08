-- The portal's tables in the archon schema. The portal runs this at every start; it only creates what is missing
-- and never changes existing rows. Written to run on MySQL 8 and, for tests, H2 in MySQL mode.
-- Timestamps are UTC, set by the portal.

-- Roles group permissions. Permissions are named in the portal's code, such as processes:view; '*' grants every
-- permission, including ones added later.
CREATE TABLE IF NOT EXISTS portal_roles (
    id          VARCHAR(64)  NOT NULL,
    description VARCHAR(255) NOT NULL,
    created_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS portal_role_permissions (
    role_id    VARCHAR(64)  NOT NULL,
    permission VARCHAR(128) NOT NULL,
    PRIMARY KEY (role_id, permission),
    FOREIGN KEY (role_id) REFERENCES portal_roles (id) ON DELETE CASCADE
);

-- Usernames are stored lowercase. password_hash holds the algorithm, its parameters, the salt and the hash.
CREATE TABLE IF NOT EXISTS portal_users (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    username            VARCHAR(64)  NOT NULL,
    display_name        VARCHAR(128) NOT NULL,
    password_hash       VARCHAR(255) NOT NULL,
    enabled             BOOLEAN      NOT NULL,
    created_at          DATETIME(3)  NOT NULL,
    updated_at          DATETIME(3)  NOT NULL,
    password_changed_at DATETIME(3)  NOT NULL,
    last_login_at       DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS portal_user_roles (
    user_id BIGINT      NOT NULL,
    role_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES portal_users (id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES portal_roles (id)
);

-- Only a SHA-256 hash of each session token is stored, so the table cannot be used to sign in
CREATE TABLE IF NOT EXISTS portal_sessions (
    token_hash   CHAR(64)    NOT NULL,
    user_id      BIGINT      NOT NULL,
    created_at   DATETIME(3) NOT NULL,
    last_seen_at DATETIME(3) NOT NULL,
    expires_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (token_hash),
    FOREIGN KEY (user_id) REFERENCES portal_users (id) ON DELETE CASCADE
);

-- One-time tokens that allow creating the first admin while no users exist. Each portal replica adds its own.
CREATE TABLE IF NOT EXISTS portal_setup_tokens (
    token_hash CHAR(64)    NOT NULL,
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (token_hash)
);

INSERT IGNORE INTO portal_roles (id, description, created_at) VALUES ('admin', 'Every permission', CURRENT_TIMESTAMP);
INSERT IGNORE INTO portal_role_permissions (role_id, permission) VALUES ('admin', '*');
