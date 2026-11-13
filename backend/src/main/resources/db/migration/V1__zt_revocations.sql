-- ELFALL Zero Trust: perzistentní revokace (SQLite / PostgreSQL kompatibilní).
-- Souborový backend (FileRevocationStore) používá stejný datový model: kind, id, ts.
CREATE TABLE IF NOT EXISTS zt_revocations (
    kind TEXT    NOT NULL CHECK (kind IN ('CREDENTIAL', 'DEVICE', 'JTI')),
    id   TEXT    NOT NULL CHECK (length(id) BETWEEN 1 AND 512),
    ts   BIGINT  NOT NULL,
    PRIMARY KEY (kind, id)
);
-- Úklid prošlých DPoP jti (revokace CREDENTIAL/DEVICE se nemažou).
CREATE INDEX IF NOT EXISTS zt_revocations_jti_ts ON zt_revocations (ts) WHERE kind = 'JTI';
