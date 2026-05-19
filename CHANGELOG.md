# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [v1.0.9-1] - 2026-05-18

Initial release of the littlesnitch-linux-flake packaging project.

### Breaking Changes

- **NixOS module `Type` changed from `exec` to `notify`** — matches upstream service file. Systems with custom overrides may need adjustment.
- **Capability set changed** — replaced `CAP_NET_ADMIN` with `CAP_SYS_ADMIN` and `CAP_DAC_READ_SEARCH` to match upstream requirements. Five capabilities are now granted: `CAP_SYS_ADMIN`, `CAP_DAC_READ_SEARCH`, `CAP_NET_RAW`, `CAP_NET_ADMIN`, `CAP_BPF`.
- **`AssertCapability` directives removed** — the module no longer hard-fails on systems missing capabilities; the service fails gracefully instead.
- **`Wants=network-pre.target` removed** — `Before=network-pre.target` is sufficient for ordering.

### Changed

- **Package layout** — upstream archive format changed: no longer wraps files in a single top-level directory. Added `sourceRoot = "."` to handle this.
- **Binary path** — moved from `bin/littlesnitch` to `usr/bin/littlesnitch` inside the archive.
- **Dependencies** — removed dead build inputs (`libcap_ng`, `audit`, `zlib`). Runtime dependencies are `linux-pam` and `sqlite`.
- **CI** — upgraded `actions/checkout` from v4 to v5. Added `id-token: write` permission for secure OIDC authentication.

### Added

- **`StateDirectory = "littlesnitch"`** — managed state directory for the service.
- **Copyright and metainfo files** — installed alongside the binary from upstream archive.
- **Automated version scraper** — Babashka script that fetches download page, parses links, computes hashes, and validates the build. Runs twice daily via GitHub Actions.
- **NixOS module and Home Manager overlay** — full systemd service configuration with capability bounding set, hardening, and user-configurable options.
- **Multi-architecture support** — aarch64-linux, ppc64le-linux, riscv64-linux, x86-64-linux.
- **Changelog tooling** — git-cliff configured for non-conventional commits with regex parsers.

### Previous Versions

- **1.0.4-1** — basic package and module, single architecture (x86-64)
- **1.0.3-1** — initial packaging
