CREATE TABLE IF NOT EXISTS trades (
    id        VARCHAR(36) PRIMARY KEY,
    symbol    VARCHAR(16) NOT NULL,
    side      VARCHAR(4)  NOT NULL,
    price     NUMERIC     NOT NULL,
    quantity  NUMERIC     NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL
);
