-- The addresses of an account are ordered and the first one is the principal one. That order used to
-- live only in the collection of the entity, which is exactly what does not survive a restart: the
-- column stores it, and `@OrderColumn` keeps it in sync. The default covers the rows that existed
-- before the column did, so the ALTER cannot fail on a table with data.
ALTER TABLE addresses
    ADD COLUMN position INTEGER NOT NULL DEFAULT 0;