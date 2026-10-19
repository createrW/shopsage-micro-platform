# Sample data

Small datasets that are safe to commit (code_standard.md `data/samples/`). Large or generated data belongs in
`data/raw/` and `data/processed/`, which are gitignored.

TODO(Day 3): an inventory seed CSV (SKU, name, unit price, currency, initial stock) loaded by inventory-service in
local development, sized so the Day 7 load tests hit a realistic mix of hot and cold SKUs.

Rules: synthetic data only (no real customer or payment data), a few hundred KB at most, and one line in this file per
dataset describing what it is and which service reads it.
