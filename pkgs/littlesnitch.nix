{
  stdenv,
  lib,
  fetchurl,
  patchelf,
  zstd,
  linux-pam,
  sqlite,
}: let
  sources = import ./sources.nix;
in
  stdenv.mkDerivation {
    pname = "littlesnitch";
    version = sources.version;

    src = fetchurl {
      url = sources.${stdenv.hostPlatform.system}.url;
      hash = sources.${stdenv.hostPlatform.system}.hash;
    };

    nativeBuildInputs = [patchelf zstd];

    sourceRoot = ".";

    dontBuild = true;
    dontStrip = true;
    dontPatchELF = true;

    installPhase = ''
      runHook preInstall
      install -Dm755 usr/bin/littlesnitch $out/bin/littlesnitch

      install -Dm644 usr/share/doc/littlesnitch/copyright \
        $out/share/doc/littlesnitch/copyright

      install -Dm644 usr/share/metainfo/at.obdev.littlesnitch.metainfo.xml \
        $out/share/metainfo/at.obdev.littlesnitch.metainfo.xml

      libs="${lib.makeLibraryPath [
        stdenv.cc.libc
        stdenv.cc.cc.lib
        linux-pam
        sqlite
      ]}"

      patchelf --set-interpreter "$(cat $NIX_CC/nix-support/dynamic-linker)" \
             --set-rpath "$libs" \
             $out/bin/littlesnitch
      runHook postInstall
    '';

    meta = with lib; {
      description = "Little Snitch for Linux (eBPF-based network monitor)";
      homepage = "https://github.com/obdev/littlesnitch-linux";
      license = licenses.unfree;
      platforms = ["x86_64-linux" "aarch64-linux" "ppc64le-linux" "riscv64-linux"];
      mainProgram = "littlesnitch";
    };
  }
