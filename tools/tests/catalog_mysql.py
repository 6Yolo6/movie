#!/usr/bin/env python3
"""Offline catalog SQL regression. Reuses the existing isolated MySQL fixture runner."""
from query_boundaries_mysql import main, REQUIRED_TESTS
REQUIRED_TESTS.clear()
REQUIRED_TESTS.update(CatalogAiMySqlTest=8)
if __name__ == '__main__':
    raise SystemExit(main())
