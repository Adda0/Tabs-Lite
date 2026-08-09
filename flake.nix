{
  description = "Tabs Lite Android development environment";

  inputs = {
    devenv-root = {
      url = "file+file:///dev/null";
      flake = false;
    };
    nixpkgs.url = "github:cachix/devenv-nixpkgs/rolling";
    flake-parts.url = "github:hercules-ci/flake-parts";
    flake-parts.inputs.nixpkgs-lib.follows = "nixpkgs";
    devenv.url = "github:cachix/devenv";
    nix2container.url = "github:nlewo/nix2container";
    nix2container.inputs.nixpkgs.follows = "nixpkgs";
    mk-shell-bin.url = "github:rrbutani/nix-mk-shell-bin";
  };

  nixConfig = {
    extra-trusted-public-keys = "devenv.cachix.org-1:w1cLUi8dv3hnoSPGAuibQv+f9TZLr6cv/Hm9XgU50cw=";
    extra-substituters = "https://devenv.cachix.org";
  };

  outputs = inputs@{ flake-parts, devenv-root, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      imports = [
        inputs.devenv.flakeModule
      ];
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];

      perSystem = { config, self', inputs', pkgs, system, ... }: {
        # Android SDK components are unfree-licensed. flake-parts' auto-supplied `pkgs`
        # (derived from the `nixpkgs` input) doesn't allow unfree by default, and that's the
        # same `pkgs` devenv's `android` module below builds from - so override it here.
        _module.args.pkgs = import inputs.nixpkgs {
          inherit system;
          config.allowUnfree = true;
        };

        devenv.shells.default = {
          name = "tabs-lite";

          # Tabs Lite targets JVM 21 (see app/build.gradle.kts jvmTarget) with
          # compileSdk/targetSdk 36 and AGP 9.0.1 (see gradle/libs.versions.toml).
          languages.java = {
            enable = true;
            jdk.package = pkgs.jdk21;
          };

          android = {
            enable = true;
            platforms.version = [ "36" ];
            buildTools.version = [ "36.0.0" ];

            # No native/C++ code in this project - keep the closure small.
            ndk.enable = false;
            cmake.version = [ ];
            systemImages.enable = false;
            sources.enable = false;
            emulator.enable = false;
            googleAPIs.enable = false;
            googleTVAddOns.enable = false;
          };

          packages = [ pkgs.git ];

          enterShell = ''
            chmod +x ./gradlew 2>/dev/null || true
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            echo "JAVA_HOME: $JAVA_HOME"
            echo "ANDROID_HOME: $ANDROID_HOME"
          '';
        };

      };
      flake = {
        # The usual flake attributes can be defined here, including system-
        # agnostic ones like nixosModule and system-enumerating ones, although
        # those are more easily expressed in perSystem.

      };
    };
}
