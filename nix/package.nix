{
  lib,
  stdenv,
  fetchurl,
  fetchFromGitHub,
  kotlin,
  jdk17,
  makeWrapper,
  sqlite,
  procps,
  src,
}:

let
  asmVersion = "9.7.1";
  maven = "https://repo1.maven.org/maven2/org/ow2/asm";
in
stdenv.mkDerivation (finalAttrs: {
  pname = "lunar-vr";
  version = "0.2.0";
  inherit src;

  nativeBuildInputs = [
    kotlin
    jdk17
    makeWrapper
  ];

  # build.sh downloads these itself outside Nix; inside the sandbox they come pinned from here
  passthru.deps = {
    asm = fetchurl {
      url = "${maven}/asm/${asmVersion}/asm-${asmVersion}.jar";
      hash = "sha256-jK3UOsXrbQneBfrsyji5F6BAu5E5x+3rTMgcdAtxMoE=";
    };
    asmCommons = fetchurl {
      url = "${maven}/asm-commons/${asmVersion}/asm-commons-${asmVersion}.jar";
      hash = "sha256-mlebVNKSrZvhcdQxP9RznGNVksK1rDpFm70QSc3exqA=";
    };
    openvr = fetchFromGitHub {
      owner = "ValveSoftware";
      repo = "openvr";
      rev = "v2.15.6";
      hash = "sha256-xtCqro73fWQ6i0PiVmWYCK30DUSq1WeALoUolUjuWlE=";
    };
  };

  env = {
    ASM_JAR = finalAttrs.passthru.deps.asm;
    ASM_COMMONS_JAR = finalAttrs.passthru.deps.asmCommons;
    OPENVR_SDK = finalAttrs.passthru.deps.openvr;
    JAVA_HOME = jdk17.home;
    KOTLIN_STDLIB = "${kotlin}/lib/kotlin-stdlib.jar";
  };

  buildPhase = ''
    runHook preBuild
    export HOME=$TMPDIR
    bash ./build.sh
    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall
    install -Dm644 dist/lcvr.jar -t $out/share/lunar-vr
    install -Dm755 dist/liblcvr.so dist/libopenvr_api.so -t $out/share/lunar-vr
    install -Dm755 launch-lunar-vr.sh $out/libexec/lunar-vr/launch-lunar-vr.sh
    # LCVR_DIST points the launcher at the store copy; LUNAR_CMD (default: lunarclient on PATH)
    # and LCVR_OPTS still work as usual
    makeWrapper $out/libexec/lunar-vr/launch-lunar-vr.sh $out/bin/lunar-vr \
      --set-default LCVR_DIST $out/share/lunar-vr \
      --prefix PATH : ${
        lib.makeBinPath [
          sqlite
          procps
        ]
      }
    runHook postInstall
  '';

  meta = {
    description = "SteamVR stereo rendering and head tracking for Minecraft 1.8.9 in Lunar Client";
    homepage = "https://github.com/AfkaraLP/lunarclient-steamvr-patch";
    license = lib.licenses.mit;
    # the OpenVR SDK only ships x86_64 Linux binaries, and the bridge uses GLX
    platforms = [ "x86_64-linux" ];
    mainProgram = "lunar-vr";
  };
})
