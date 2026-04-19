{
  description = "Little Snitch for Linux Flake";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs?ref=nixos-unstable";
  };

  outputs = {
    self,
    nixpkgs,
  }: let
    supportedSystems = ["x86_64-linux"];
    pkgsBySystem = nixpkgs.lib.getAttrs supportedSystems nixpkgs.legacyPackages;
    forAllPkgs = fn: nixpkgs.lib.mapAttrs (system: pkgs: (fn system pkgs)) pkgsBySystem;
  in {
    formatter = forAllPkgs (system: pkgs: pkgs.alejandra);

    packages = forAllPkgs (system: pkgs: {
      default = self.packages.${system}.littlesnitch;
      littlesnitch = pkgs.callPackage ./pkgs/littlesnitch.nix {};
    });

    nixosModules = {
      default = import ./modules/littlesnitch.nix;
      littlesnitch = import ./modules/littlesnitch.nix;
    };

    overlays.default = final: prev: {
      littlesnitch = final.callPackage ./pkgs/littlesnitch.nix {};
    };

    devShells = forAllPkgs (system: pkgs: {
      default = pkgs.mkShell {
        packages = [pkgs.nix-update];
      };
    });
  };
}
