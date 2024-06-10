{
  description = "virtual environments";

  inputs.devshell.url = "github:numtide/devshell";
  inputs.flake-utils.url = "github:numtide/flake-utils";

  inputs.flake-compat = {
    url = "github:edolstra/flake-compat";
    flake = false;
  };

  inputs.makes.url = "github:fluidattacks/makes/7e4ac2ba9fc3df505c76dea1678bf92e7b531399";

  outputs = { self, flake-utils, devshell, nixpkgs, makes,... }:
    flake-utils.lib.eachDefaultSystem (system: {
      devShells.default =
        let
          makes-overlay = prev: final: {
            makes = makes.packages.x86_64-linux.default;
          };
          pkgs = import nixpkgs {
            inherit system;

            overlays = [ devshell.overlays.default makes-overlay];
          };
        in
        pkgs.devshell.mkShell {
          imports = [ (pkgs.devshell.importTOML ./devshell.toml) ];
        };
    });
}
