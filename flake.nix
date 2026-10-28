{
  description = "SteamVR stereo rendering and head tracking for Minecraft 1.8.9 in Lunar Client";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      # the OpenVR SDK only ships x86_64 Linux binaries
      system = "x86_64-linux";
      pkgs = nixpkgs.legacyPackages.${system};
      lunar-vr = pkgs.callPackage ./nix/package.nix { src = self; };
    in
    {
      packages.${system} = {
        inherit lunar-vr;
        default = lunar-vr;
      };

      # `nix run`: put the agent into Lunar's 1.8 profile and start Lunar
      apps.${system}.default = {
        type = "app";
        program = nixpkgs.lib.getExe lunar-vr;
        meta.description = lunar-vr.meta.description;
      };

      overlays.default = final: _prev: {
        lunar-vr = final.callPackage ./nix/package.nix { src = self; };
      };

      # `nix develop`: ./build.sh then works offline, using the same pinned dependencies
      devShells.${system}.default = pkgs.mkShell {
        inputsFrom = [ lunar-vr ];
        packages = [ pkgs.sqlite ];
        env = {
          ASM_JAR = lunar-vr.deps.asm;
          ASM_COMMONS_JAR = lunar-vr.deps.asmCommons;
          OPENVR_SDK = lunar-vr.deps.openvr;
          JAVA_HOME = pkgs.jdk17.home;
          KOTLIN_STDLIB = "${pkgs.kotlin}/lib/kotlin-stdlib.jar";
        };
      };

      checks.${system}.build = lunar-vr;

      formatter.${system} = pkgs.nixfmt;
    };
}
