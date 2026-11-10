-- Baseline migration: database extensions only.
-- Tables are added by the milestone that needs them (see docs/database-design.md).
CREATE EXTENSION IF NOT EXISTS vector;
