# Little Snitch for Linux Flake

[![Auto Update](https://github.com/noblepayne/littlesnitch-linux-flake/actions/workflows/auto-update.yml/badge.svg)](https://github.com/noblepayne/littlesnitch-linux-flake/actions/workflows/auto-update.yml)

This flake provides a Nix package and NixOS module for [Little Snitch for Linux](https://obdev.at/products/littlesnitch-linux/index.html) — an eBPF-based network monitor from Objective Development.

**Current version:** 1.0.9-1

## Supported Architectures

- `x86_64-linux`
- `aarch64-linux`
- `ppc64le-linux`
- `riscv64-linux`

## Usage

### NixOS Module

Add this flake to your inputs and enable the service:

```nix
{
  inputs.littlesnitch.url = "github:noblepayne/littlesnitch-linux-flake";

  outputs = { self, nixpkgs, littlesnitch }: {
    nixosConfigurations.yourhost = nixpkgs.lib.nixosSystem {
      modules = [
        littlesnitch.nixosModules.default
        {
          services.littlesnitch.enable = true;
        }
      ];
    };
  };
}
```

The module configures a hardened systemd service with:
- `Type=notify` — daemon signals readiness after eBPF hooks are active
- `StateDirectory=littlesnitch` — persistent storage at `/var/lib/littlesnitch`
- `Before=network-pre.target` — eBPF hooks start before networking
- Capability bounding: `CAP_BPF`, `CAP_DAC_READ_SEARCH`, `CAP_PERFMON`, `CAP_SYS_ADMIN`, `CAP_SYS_RESOURCE`
- Full sandbox hardening (`ProtectSystem=full`, `NoNewPrivileges`, `MemoryDenyWriteExecute`, etc.)

### Flake Overlay

Add to your nixpkgs overlays to make `pkgs.littlesnitch` available:

```nix
{
  inputs.littlesnitch.url = "github:noblepayne/littlesnitch-linux-flake";

  outputs = { self, nixpkgs, littlesnitch }: {
    nixosConfigurations.yourhost = nixpkgs.lib.nixosSystem {
      specialArgs = { inherit inputs; };
      modules = [
        {
          nixpkgs.overlays = [ littlesnitch.overlays.default ];
          services.littlesnitch.enable = true;
        }
      ];
    };
  };
}
```

### Direct Build

```bash
nix build github:noblepayne/littlesnitch-linux-flake#littlesnitch --impure
```

> `--impure` is required because the package has an unfree license.

### Home Manager

```nix
{ inputs, ... }: {
  home.packages = [ inputs.littlesnitch.packages.${pkgs.system}.littlesnitch ];
}
```

## Configuration

Data is stored in `/var/lib/littlesnitch/`:

| Path | Purpose |
|------|---------|
| `connections.sqlite` | Connection history |
| `rules.sqlite` | User-defined rules |
| `override/config/` | Config overrides (TOML) |
| `auto-generated-tls-*.pem` | Web UI TLS certificates |

The web UI is available at `https://localhost:8443` (default) after the service starts.

## Automation

This repository automatically checks for new versions twice daily via GitHub Actions. When a new version is detected:

1. The download page is scraped for the latest version and manifest hash
2. All architecture binaries are downloaded and hashed
3. `pkgs/sources.nix` is updated with new version and hashes
4. The package is built and smoke-tested to verify compatibility
5. Changes are committed and pushed automatically

The scraper uses [Babashka](https://babashka.org/) with a vendored Hickory HTML parser — no Node.js or Python required.

## Development

Enter the dev shell:

```bash
nix develop --impure
```

Available tools:

| Command | Purpose |
|---------|---------|
| `bb scripts/scrape_download_links.clj` | Run the update scraper manually |
| `clj-kondo --lint scripts/` | Lint Clojure/Babashka scripts |
| `cljfmt check scripts/` | Check Clojure formatting |
| `cljfmt -i scripts/` | Format Clojure files in-place |
| `git-cliff --unreleased` | Generate changelog from unreleased commits |
| `alejandra --check .` | Check Nix formatting |
| `alejandra .` | Format Nix files in-place |

## Changelog

Generated from git history using [git-cliff](https://git-cliff.org/):

```bash
nix develop --impure --command git-cliff --unreleased    # unreleased changes
nix develop --impure --command git-cliff -t 1.0.9-1       # since last tag
nix develop --impure --command git-cliff                    # full changelog
```

## License

The code in this repository is licensed under **MIT**.

The **Little Snitch for Linux** application and binaries are subject to the [Objective Development License](https://obdev.at/products/littlesnitch-linux/license.html). The open source components of the upstream project can be found at [obdev/littlesnitch-linux](https://github.com/obdev/littlesnitch-linux).
