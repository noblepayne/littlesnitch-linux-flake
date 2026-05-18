{
  config,
  lib,
  pkgs,
  ...
}:
with lib; let
  cfg = config.services.littlesnitch;
in {
  options.services.littlesnitch = {
    enable = mkEnableOption "Little Snitch for Linux";
    package = mkOption {
      type = types.package;
      default = pkgs.littlesnitch;
      description = "The Little Snitch package to use.";
    };
  };

  config = mkIf cfg.enable {
    environment.systemPackages = [
      cfg.package
    ];

    systemd.tmpfiles.rules = [
      "d /var/lib/littlesnitch 0755 root root -"
    ];

    systemd.services.littlesnitch = {
      description = "Little Snitch network monitor daemon";
      after = ["sysinit.target"];
      before = ["network-pre.target"];
      wants = ["network-pre.target"];
      wantedBy = ["multi-user.target"];

      unitConfig = {
        AssertCapability = [
          "CAP_BPF"
          "CAP_DAC_READ_SEARCH"
          "CAP_PERFMON"
          "CAP_SYS_ADMIN"
          "CAP_SYS_RESOURCE"
        ];
      };

      serviceConfig = {
        Type = "notify";
        ExecStart = "${cfg.package}/bin/littlesnitch --daemon --use-cap-sys-admin";
        Restart = "on-failure";
        RestartSec = "5s";
        StateDirectory = "littlesnitch";

        CapabilityBoundingSet = [
          "CAP_BPF"
          "CAP_DAC_READ_SEARCH"
          "CAP_PERFMON"
          "CAP_SYS_ADMIN"
          "CAP_SYS_RESOURCE"
        ];
        MemoryDenyWriteExecute = true;
        NoNewPrivileges = true;
        PrivateDevices = true;
        ProtectClock = true;
        ProtectControlGroups = true;
        ProtectKernelLogs = true;
        ProtectKernelModules = true;
        ProtectProc = "noaccess";
        ProtectSystem = "full";
        RestrictAddressFamilies = [
          "AF_UNIX"
          "AF_INET"
          "AF_INET6"
        ];

        StandardOutput = "null";
        StandardError = "journal";
      };
    };
  };
}
