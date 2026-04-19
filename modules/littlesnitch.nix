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
    systemd.services.littlesnitch = {
      description = "Little Snitch for Linux Daemon";
      after = ["network.target"];
      wantedBy = ["multi-user.target"];

      serviceConfig = {
        ExecStart = "${cfg.package}/bin/littlesnitch";
        Restart = "always";
        RestartSec = "5";

        # Little Snitch needs eBPF capabilities
        CapabilityBoundingSet = [
          "CAP_SYS_ADMIN"
          "CAP_NET_ADMIN"
          "CAP_NET_RAW"
          "CAP_BPF"
          "CAP_PERFMON"
        ];
        AmbientCapabilities = [
          "CAP_SYS_ADMIN"
          "CAP_NET_ADMIN"
          "CAP_NET_RAW"
          "CAP_BPF"
          "CAP_PERFMON"
        ];

        # Hardening (Some of these might need tuning based on binary needs)
        ProtectSystem = "full";
        ProtectHome = "read-only";
        NoNewPrivileges = true;
      };
    };
  };
}
