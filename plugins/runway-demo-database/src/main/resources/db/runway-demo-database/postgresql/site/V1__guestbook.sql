-- The guestbook (PostgreSQL). Every object carries the plugin's prefix, runway_demo_database__.
-- Ids come from a sequence, never an identity column; the entity columns follow the house convention.
CREATE SEQUENCE runway_demo_database__entry_seq
-- runway:next
CREATE TABLE runway_demo_database__entry (
    id         BIGINT       PRIMARY KEY,
    version    INTEGER      NOT NULL DEFAULT 0,
    name       VARCHAR(80)  NOT NULL,
    message    VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    updater    VARCHAR(80)  NOT NULL
)
-- runway:next
CREATE INDEX runway_demo_database__entry_created ON runway_demo_database__entry (created_at)
