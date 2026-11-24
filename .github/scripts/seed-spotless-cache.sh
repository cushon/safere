#!/usr/bin/env bash
# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).
set -euo pipefail

# Spotless resolves the formatter dependencies while initializing its checks.
# The file pattern matches nothing so this job can warm the cache even if sources need formatting.
mvn -pl safere spotless:check '-DspotlessFiles=(?!)' --batch-mode --no-transfer-progress
