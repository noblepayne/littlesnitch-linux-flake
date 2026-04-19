{
  stdenv,
  lib,
  fetchurl,
  patchelf,
  zstd,
  linux-pam,
  sqlite,
  zlib,
  audit,
  libcap_ng,
  pipewire,
}:
stdenv.mkDerivation rec {
  pname = "littlesnitch";
  version = "1.0.3-1";

  src = fetchurl {
    url = "https://obdev.at/downloads/littlesnitch-linux/littlesnitch-${version}-x86_64.pkg.tar.zst";
    hash = "sha256-ZmR7GSZobRA0uTMH5hfqxW+L2EOYefpjrOgOCWQYaG0=";
  };

  nativeBuildInputs = [patchelf zstd];

  # The tar.zst contains a usr/bin/littlesnitch structure
  # Nix sets sourceRoot to "usr" automatically
  dontBuild = true;
  dontStrip = true;
  dontPatchELF = true;

  installPhase = ''
    runHook preInstall
    install -Dm755 bin/littlesnitch $out/bin/littlesnitch

    # Construct RPATH from dependencies
    libs="${lib.makeLibraryPath [
      stdenv.cc.libc
      stdenv.cc.cc.lib
      linux-pam
      sqlite
      zlib
      audit
      libcap_ng
      pipewire
    ]}"

    patchelf \
      --set-interpreter "$(cat $NIX_CC/nix-support/dynamic-linker)" \
      --set-rpath "$libs" \
      $out/bin/littlesnitch
    runHook postInstall
  '';

  meta = with lib; {
    description = "Little Snitch for Linux (eBPF-based network monitor)";
    homepage = "https://github.com/obdev/littlesnitch-linux";
    license = licenses.unfree;
    platforms = ["x86_64-linux"];
    mainProgram = "littlesnitch";
  };
}
