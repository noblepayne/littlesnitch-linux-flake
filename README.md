# Little Snitch for Linux Flake

This flake provides a Nix package and NixOS module for [Little Snitch for Linux](https://obdev.at/products/littlesnitch-linux/index.html).

## Usage

### NixOS Module

Add this flake to your inputs and import the module:

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

### Direct Build

```bash
nix build github:noblepayne/littlesnitch-linux-flake#littlesnitch --impure
```
Note: `--impure` is required because the package has an unfree license.

## Automation

This repository uses a Babashka script (`scripts/scrape_download_links.clj`) to automatically check for new versions of Little Snitch for Linux twice daily. If a new version is detected:
1. All architecture binaries are downloaded and hashed.
2. `pkgs/sources.nix` is updated.
3. The package is built and smoke-tested to ensure compatibility.
4. Changes are committed and pushed automatically.

## Development

The repository includes a Nix dev shell with all necessary tools:
```bash
nix develop --impure
```

Inside the shell, you can:
- `bb scripts/scrape_download_links.clj`: Manually run the update scraper.
- `clj-kondo --lint scripts/`: Lint the Clojure scripts.
- `cljfmt check scripts/`: Check Clojure code formatting.

## License

The code in this repository is licensed under **MIT**. 

The **Little Snitch for Linux** application and binaries are subject to the [Objective Development License](https://obdev.at/products/littlesnitch-linux/license.html). The open source components of the project can be found at [obdev/littlesnitch-linux](https://github.com/obdev/littlesnitch-linux).
