# Little Snitch for Linux Flake

This flake provides a Nix package and NixOS module for [Little Snitch for Linux](https://github.com/obdev/littlesnitch-linux).

## Usage

### NixOS Module

Add this flake to your inputs and import the module:

```nix
{
  inputs.littlesnitch.url = "github:youruser/littlesnitch-linux-flake";

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
nix build .#littlesnitch --impure
```
Note: `--impure` is required because the package has an unfree license.

## License

This package is subject to the Little Snitch for Linux license. See the [official website](https://obdev.at/littlesnitch-linux) for details.
