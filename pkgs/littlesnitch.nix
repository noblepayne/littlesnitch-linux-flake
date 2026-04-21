{
  stdenv,
  lib,
  fetchurl,
  patchelf,
  zstd,
  linux-pam,
  sqlite,
  libcap_ng,
  audit,
  zlib,
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

    dontBuild = true;
    dontStrip = true;
    dontPatchELF = true;

    installPhase = ''
      runHook preInstall
      install -Dm755 bin/littlesnitch $out/bin/littlesnitch

      libs="${lib.makeLibraryPath [
        stdenv.cc.libc
        stdenv.cc.cc.lib
        linux-pam
        sqlite
        libcap_ng
        audit
        zlib
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
