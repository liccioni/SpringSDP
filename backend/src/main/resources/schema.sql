CREATE TABLE IF NOT EXISTS trades (
    id        VARCHAR(36) PRIMARY KEY,
    symbol    VARCHAR(16) NOT NULL,
    side      VARCHAR(4)  NOT NULL,
    price     NUMERIC     NOT NULL,
    quantity  NUMERIC     NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_events (
    id         VARCHAR(36)  PRIMARY KEY,
    session_id VARCHAR(64),
    username   VARCHAR(64)  NOT NULL,
    event_type VARCHAR(32)  NOT NULL,
    detail     VARCHAR(256) NOT NULL,
    timestamp  TIMESTAMPTZ  NOT NULL
);
