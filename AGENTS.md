# AGENTS.md — littlesnitch-linux-flake

## Project Overview

Nix flake packaging for [LittleSnitch Linux](https://www.obdev.at/products/littlesnitch-linux/index.html) — an eBPF-based network monitor from Objective Development. Provides a NixOS module, Home Manager overlay, and automated version scraper.

**Current version:** 1.0.9-1 (app version 1.0.9, `-1` is package revision)
**Supported architectures:** aarch64-linux, ppc64le-linux, riscv64-linux, x86-64-linux
**License:** unfree (GPL-2.0-only AND LicenseRef-proprietary)

## Quick Reference

```bash
# Build (always needs these flags)
NIXPKGS_ALLOW_UNFREE=1 nix build .#littlesnitch --impure

# Run binary
./result/bin/littlesnitch --version

# Format all Nix files
nix develop -c alejandra .

# Generate changelog
nix develop -c git-cliff --tag v1.0.9-1

# Run scraper manually (fetches latest version, updates sources.nix, builds)
nix develop --impure --command ./scripts/update.sh
```

## Package Structure

| File | Purpose |
|------|---------|
| `flake.nix` | Flake outputs: packages, nixosModules, overlays.default, devShell, formatter |
| `pkgs/sources.nix` | Per-arch version + SRI hashes (auto-updated by scraper) |
| `pkgs/littlesnitch.nix` | Derivation: unpacks, installs binary + copyright + metainfo |
| `modules/littlesnitch.nix` | NixOS module: systemd service, capabilities, StateDirectory |
| `scripts/scrape_download_links.clj` | Babashka scraper: fetches, parses, hashes, build-tests |
| `scripts/hickory_bundle.clj` | Vendored HTML parser (1405 lines, do not edit) |
| `scripts/update.sh` | Shell wrapper that runs the scraper |
| `cliff.toml` | git-cliff config: non-conventional commits, CHANGELOG skip filter |
| `.github/workflows/auto-update.yml` | CI: runs scraper twice daily (00:00 and 12:00 UTC) |

## Critical Build Details

### Binary
- Located at `usr/bin/littlesnitch` inside the archive (NOT `bin/`)
- Dynamically linked ELF, ~16 MB, Rust-based
- Runtime deps: `linux-pam`, `sqlite` (plus glibc/gcc runtime libs)
- Native build deps: `patchelf`, `zstd`

### Archive Format
- `.pkg.tar.zst` from `https://obdev.at/products/littlesnitch-linux/download.html`
- Extracts to multiple top-level dirs (`usr/`, `etc/`, `.PKGINFO`) — no wrapping directory
- **`sourceRoot = "."`** is required because of this

### Unfree License
- The package has `meta.license = licenses.unfree`
- Nix refuses to evaluate it without explicit permission
- **Always use:** `NIXPKGS_ALLOW_UNFREE=1 nix build .#littlesnitch --impure`
- CI sets `NIXPKGS_ALLOW_UNFREE: 1` and passes `--impure` to nix develop

## Troubleshooting

### Upstream changes archive format again (no wrapping directory)
If `nix build` fails with "no such file or directory" during unpack:
1. Check what the archive extracts to: `tar -tf download.pkg.tar.zst | head -20`
2. If files are at root level (no single top-level dir), ensure `sourceRoot = "."`
3. If a new top-level dir appears, remove `sourceRoot = "."` or set it to the dir name

### Binary path changes
If build fails during install phase with "no such file":
1. Check archive: `tar -tf download.pkg.tar.zst | grep littlesnitch`
2. Update the path in `pkgs/littlesnitch.nix` installPhase (currently `usr/bin/littlesnitch`)
3. Also update the CI smoke test path if it references the binary directly

### New or removed dependencies
The binary dynamically links against: `libpam.so.0`, `libsqlite3.so.0`, plus glibc/gcc libs.
1. Run `ldd ./result/bin/littlesnitch` after a successful build
2. Compare against `buildInputs` in `pkgs/littlesnitch.nix`
3. Add missing deps, remove dead ones

### Capabilities change
The NixOS module grants: `CAP_SYS_ADMIN`, `CAP_DAC_READ_SEARCH`, `CAP_NET_RAW`, `CAP_NET_ADMIN`, `CAP_BPF`
1. Check upstream `.service` file in the archive: `tar -xf download.pkg.tar.zst etc/systemd/system/littlesnitch.service`
2. Compare `CapabilityBoundingSet` and `ExecStart` flags
3. Update `modules/littlesnitch.nix` to match

### Scraper fails to parse download page
The scraper uses a vendored hickory HTML parser (`scripts/hickory_bundle.clj`).
1. The scraper looks for `.download-table` class with `.pkg.tar.zst` links
2. If upstream changes the page structure, update the CSS selector in `scrape_download_links.clj`
3. Architecture mapping is in the `arch-map` — verify keys match link text

### Scraper fails to build after updating sources
If `nix build .#littlesnitch` fails after scraper updates hashes:
1. The hash might be wrong — re-download and manually verify: `nix-prefetch-url <url>`
2. The package layout might have changed — see troubleshooting above
3. Check if a new architecture was added or removed

## NixOS Module Details

The module creates a systemd service that matches the upstream `.service` file with intentional deviations:

| Setting | Value | Notes |
|---------|-------|-------|
| `Type` | `notify` | Matches upstream |
| `ExecStart` | `--daemon --use-cap-sys-admin` | Matches upstream |
| `CapabilityBoundingSet` | 5 caps | Matches upstream |
| `Before` | `network-pre.target` | Matches upstream |
| `StateDirectory` | `littlesnitch` | Added by module (upstream doesn't set this) |
| `Wants=network-pre.target` | **Omitted** | Not needed; `Before=` is sufficient |
| `AssertCapability` (x5) | **Omitted** | Would hard-fail on systems without caps |

User-configurable options: `services.littlesnitch.enable`, `services.littlesnitch.package`

## Changelog Strategy

**v1.0.9-1 was hand-written** — the first release had too many breaking changes to capture via commit messages alone. The CHANGELOG.md for this release is curated by hand.

**Future releases** — git-cliff is configured in `cliff.toml` for non-conventional commits. Going forward, commits should have descriptive messages (the agent writes these). Then:

```bash
nix develop -c git-cliff --tag vNEW-VERSION > CHANGELOG.md
```

The config:
- `skip = true` for any commit containing "CHANGELOG" (prevents meta commits)
- Body preprocessor strips commit bodies: `'\n[\s\S]*'`
- Groups: Features, Bug Fixes, Version Updates, Cleanup, Chores, CI, Documentation, Initial Commit, Changes (catch-all)
- `sort_commits = "newest"`
- `conventional_commits = false` — repo doesn't use strict conventional commits

If git-cliff output is inadequate for a future release, hand-write the section instead. The tool is there for convenience, not as a requirement.

## CI Auto-Update Workflow

Runs twice daily via GitHub Actions:
1. Checks out repo with OIDC token (`id-token: write`)
2. Runs `nix develop --impure --command ./scripts/update.sh`
3. Scraper fetches download page, parses links, computes hashes
4. If version changed: updates `pkgs/sources.nix`, runs `nix build` smoke test
5. If build succeeds: commits changes, creates PR
6. If build fails: logs error, no PR created

The scraper only creates a PR if the manifest hash changed AND the build passes.

## Dev Tools

Available in `nix develop`:
- `babashka` — runtime for the scraper
- `clj-kondo` — Clojure/Babashka linter
- `cljfmt` — Clojure formatter
- `alejandra` — Nix formatter (also the flake formatter)
- `git-cliff` — changelog generator

## File Hash Verification

When manually checking a hash:
```bash
# Get SRI hash for a URL
nix-prefetch-url --sri <url>

# Or download and hash manually
curl -L <url> | sha256sum
# Then convert to SRI: base64 of the hex digest
```

## Important Notes

- **No `checks` output in flake** — the scraper already validates the build. Cross-arch checks would be expensive and redundant.
- **Vendored hickory** — `scripts/hickory_bundle.clj` is a 1405-line vendored HTML parser. Do not edit unless upstream changes require a different parsing approach.
- **`.littlesnitch-url-hash`** — stores the last-seen manifest hash to avoid unnecessary scraper runs.
- **`result` symlink** — created by `nix build`, points to the built package in `/nix/store`. Safe to ignore in `.gitignore`.
