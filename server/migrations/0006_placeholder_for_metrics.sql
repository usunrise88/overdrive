-- PLACEHOLDER, REMOVE WHEN THE METRICS STAGE (P3-1) IS MERGED: migration 0006 belongs to the metrics engine, which is built in
-- parallel to the wellness stage (0007). Migrations must be numbered 1, 2, 3, ... with no gap, so the wellness branch carries this
-- empty file to be testable on its own. Merging the metrics stage brings the real 0006_<name>.sql: delete this file then, or the
-- numbering check at start (src/migrate.ts) fails on two files for version 6.
SELECT 1;
