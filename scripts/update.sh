#!/usr/bin/env bash
set -euo pipefail

echo "Checking for Little Snitch updates..."

# Run the BB scraper to check for updates
bb scripts/scrape_download_links.clj

echo "Update check complete."