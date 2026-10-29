-- The guestbook (MariaDB): the same table, columns and nullability as the PostgreSQL script, so a backup moves
-- between the two. TIMESTAMP(6) holds an instant (the session time zone is pinned to UTC).
CREATE SEQUENCE runway_demo_database__entry_seq
-- runway:next
CREATE TABLE runway_demo_database__entry (
    id         BIGINT        PRIMARY KEY,
    version    INT           NOT NULL DEFAULT 0,
    name       VARCHAR(80)   NOT NULL,
    message    VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP(6)  NOT NULL,
    updated_at TIMESTAMP(6)  NOT NULL,
    updater    VARCHAR(80)   NOT NULL
) ENGINE=InnoDB
-- runway:next
CREATE INDEX runway_demo_database__entry_created ON runway_demo_database__entry (created_at)
