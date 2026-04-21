{
  description = "Little Snitch for Linux Flake";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs?ref=nixos-unstable";
  };

  outputs = {
    self,
    nixpkgs,
  }: let
    supportedSystems = ["x86_64-linux" "aarch64-linux" "ppc64le-linux" "riscv64-linux"];
    pkgsBySystem = nixpkgs.lib.getAttrs supportedSystems nixpkgs.legacyPackages;
    forAllPkgs = fn: nixpkgs.lib.mapAttrs (system: pkgs: (fn system pkgs)) pkgsBySystem;
  in {
    formatter = forAllPkgs (system: pkgs: pkgs.alejandra);

    packages = forAllPkgs (system: pkgs: {
      default = self.packages.${system}.littlesnitch;
      littlesnitch = pkgs.callPackage ./pkgs/littlesnitch.nix {};
    });

    nixosModules = {
      default = self.nixosModules.littlesnitch;
      littlesnitch = {
        options,
        config,
        pkgs,
        ...
      }: {
        imports = [./modules/littlesnitch.nix];
        config.services.littlesnitch.package = self.packages.${pkgs.stdenv.hostPlatform.system}.default;
      };
    };

    overlays.default = final: prev: {
      littlesnitch = final.callPackage ./pkgs/littlesnitch.nix {};
    };

    devShells = forAllPkgs (system: pkgs: {
      default = pkgs.mkShell {
        packages = [
          pkgs.babashka
          pkgs.clj-kondo
          pkgs.cljfmt
        ];
      };
    });
  };
}
