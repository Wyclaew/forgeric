#!/usr/bin/env python3
"""Generate a Forgeric version profile by querying the upstream metadata servers.

Adding support for a new Minecraft version should not mean editing Java. This script
looks up what NeoForge and Fabric have published for a given Minecraft version, checks
the compatibility assumptions Forgeric relies on, and writes profiles/<version>.json.

Usage:
    python3 tools/new_profile.py 26.3
    python3 tools/new_profile.py 26.3 --write
    python3 tools/new_profile.py --latest --write

Without --write the profile is printed instead of saved, so it can be reviewed first.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
import zipfile
from io import BytesIO
from pathlib import Path

MOJANG_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
NEOFORGE_METADATA = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
NEOFORGE_MAVEN = "https://maven.neoforged.net/releases"
FABRIC_META = "https://meta.fabricmc.net/v2"
FABRIC_MAVEN = "https://maven.fabricmc.net/"

TIMEOUT = 60


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=TIMEOUT) as response:
        return response.read()


def fetch_json(url: str):
    return json.loads(fetch(url))


def mojang_versions() -> dict:
    return fetch_json(MOJANG_MANIFEST)


def resolve_minecraft(manifest: dict, version: str | None) -> tuple[str, str]:
    """Return (version_id, version_json_url), defaulting to the latest release."""
    if version is None:
        version = manifest["latest"]["release"]
    for entry in manifest["versions"]:
        if entry["id"] == version:
            return version, entry["url"]
    raise SystemExit(f"Minecraft {version} is not in Mojang's manifest")


def is_obfuscated(version_json: dict) -> bool:
    """26.x ships deobfuscated and publishes no mappings; 1.21.x and earlier do.

    The presence of a client_mappings download is the reliable signal: it is exactly the
    ProGuard mapping file that exists only when the jar needs deobfuscating.
    """
    return "client_mappings" in version_json.get("downloads", {})


def java_major(version_json: dict) -> int:
    return int(version_json.get("javaVersion", {}).get("majorVersion", 21))


def neoforge_versions() -> list[str]:
    xml = fetch(NEOFORGE_METADATA).decode("utf-8", "replace")
    return re.findall(r"<version>([^<]+)</version>", xml)


def neoforge_prefix(minecraft: str) -> str:
    """Map a Minecraft version onto the NeoForge version prefix that targets it.

    NeoForge drops Minecraft's leading "1.", so 1.21.1 becomes 21.1.x and 1.21 becomes 21.0.x.
    Since Mojang moved to year-based versions there is nothing to drop: 26.2 is 26.2.x.
    """
    parts = minecraft.split(".")
    if parts[0] == "1" and len(parts) >= 2:
        major = parts[1]
        minor = parts[2] if len(parts) >= 3 else "0"
        return f"{major}.{minor}."
    return minecraft + "."


def pick_neoforge(versions: list[str], minecraft: str) -> str | None:
    """Stable builds win over betas; among equals the highest build number wins."""
    prefix = neoforge_prefix(minecraft)
    candidates = [v for v in versions if v.startswith(prefix)]
    if not candidates:
        return None

    def sort_key(version: str):
        beta = "-beta" in version
        numbers = [int(n) for n in re.findall(r"\d+", version)]
        return (not beta, numbers)

    return max(candidates, key=sort_key)


def fabric_supports(minecraft: str) -> bool:
    try:
        games = fetch_json(f"{FABRIC_META}/versions/game")
    except urllib.error.URLError as e:
        raise SystemExit(f"Could not reach Fabric meta: {e}")
    return any(entry["version"] == minecraft for entry in games)


def fabric_loader_latest() -> str:
    loaders = fetch_json(f"{FABRIC_META}/versions/loader")
    for entry in loaders:
        if entry.get("stable"):
            return entry["version"]
    return loaders[0]["version"]


def fabric_intermediary(minecraft: str) -> str | None:
    """Returns the intermediary version, or None when the game needs no mappings.

    Fabric reports 0.0.0 for deobfuscated versions, which means "nothing to map".
    """
    try:
        entries = fetch_json(f"{FABRIC_META}/versions/intermediary/{minecraft}")
    except urllib.error.HTTPError:
        return None
    if not entries:
        return None
    version = entries[0].get("version")
    return None if version in (None, "0.0.0") else version


def jar_dependency_versions(url: str, artifacts: list[str]) -> dict[str, str]:
    """Read a launcher/version json out of a jar and pull specific library versions from it."""
    data = fetch(url)
    with zipfile.ZipFile(BytesIO(data)) as jar:
        names = [n for n in jar.namelist() if n == "version.json"]
        if not names:
            return {}
        version_json = json.loads(jar.read(names[0]))

    found: dict[str, str] = {}
    for library in version_json.get("libraries", []):
        name = library.get("name", "")
        for artifact in artifacts:
            if name.startswith(artifact + ":"):
                found[artifact] = name.split(":")[2]
    return found


def fabric_library_versions(minecraft: str, loader: str, artifacts: list[str]) -> dict[str, str]:
    profile = fetch_json(f"{FABRIC_META}/versions/loader/{minecraft}/{loader}/profile/json")
    found: dict[str, str] = {}
    for library in profile.get("libraries", []):
        name = library.get("name", "")
        for artifact in artifacts:
            if name.startswith(artifact + ":"):
                found[artifact] = name.split(":")[2]
    return found


def build_profile(minecraft: str, forgeric_version: str) -> dict:
    manifest = mojang_versions()
    minecraft, version_url = resolve_minecraft(manifest, minecraft)
    version_json = fetch_json(version_url)

    obfuscated = is_obfuscated(version_json)
    java = java_major(version_json)

    neoforge = pick_neoforge(neoforge_versions(), minecraft)
    if neoforge is None:
        raise SystemExit(f"NeoForge has no build for Minecraft {minecraft} yet")

    if not fabric_supports(minecraft):
        raise SystemExit(f"Fabric does not support Minecraft {minecraft} yet")
    loader = fabric_loader_latest()

    # Forgeric only avoids shading Mixin because both loaders agree on its version.
    shared = ["net.fabricmc:sponge-mixin", "org.ow2.asm:asm"]
    neo_libs = jar_dependency_versions(
        f"{NEOFORGE_MAVEN}/net/neoforged/neoforge/{neoforge}/neoforge-{neoforge}-installer.jar", shared)
    fabric_libs = fabric_library_versions(minecraft, loader, shared)

    warnings = []
    if obfuscated:
        warnings.append(
            "This Minecraft version is obfuscated. Forgeric has no runtime remapper, so the "
            "profile is written with obfuscated=true and the installer will refuse it.")
    for artifact in shared:
        neo_version = neo_libs.get(artifact)
        fabric_version = fabric_libs.get(artifact)
        if neo_version and fabric_version and neo_version != fabric_version:
            warnings.append(
                f"{artifact} differs: NeoForge has {neo_version}, Fabric has {fabric_version}. "
                "The bridge assumes one shared runtime for these.")

    profile = {
        "$schema": "./schema.json",
        "profileVersion": 1,
        "minecraft": minecraft,
        "javaMajor": java,
        "obfuscated": obfuscated,
        "neoforge": {
            "version": neoforge,
            "maven": NEOFORGE_MAVEN,
            "installerArtifact": f"net.neoforged:neoforge:{neoforge}:installer",
            "mainClass": "net.neoforged.fml.startup.Client",
        },
        "fabric": {
            "loader": loader,
            "maven": FABRIC_MAVEN,
            "meta": FABRIC_META,
            "intermediary": fabric_intermediary(minecraft),
        },
        "compat": {
            "mixin": neo_libs.get("net.fabricmc:sponge-mixin", "unknown"),
            "asm": neo_libs.get("org.ow2.asm:asm", "unknown"),
        },
        "forgeric": {
            "version": forgeric_version,
            "loaderArtifact": f"dev.forgeric:forgeric-loader:{forgeric_version}",
        },
        "supported": {
            "status": "untested",
            "fabricApi": False,
        },
    }
    return profile, warnings


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate a Forgeric version profile")
    parser.add_argument("minecraft", nargs="?", help="Minecraft version (default: latest release)")
    parser.add_argument("--latest", action="store_true", help="use the latest Minecraft release")
    parser.add_argument("--write", action="store_true", help="save to profiles/<version>.json")
    parser.add_argument("--forgeric-version", default="0.1.0")
    args = parser.parse_args()

    if not args.minecraft and not args.latest:
        parser.error("give a Minecraft version, or pass --latest")

    profile, warnings = build_profile(None if args.latest else args.minecraft, args.forgeric_version)
    rendered = json.dumps(profile, indent=2) + "\n"

    for warning in warnings:
        print(f"WARNING: {warning}", file=sys.stderr)

    if args.write:
        target = Path(__file__).resolve().parent.parent / "profiles" / f"{profile['minecraft']}.json"
        target.write_text(rendered, encoding="utf-8")
        print(f"Wrote {target}")
        print("Next: align gradle/libs.versions.toml with this profile, then rebuild.")
    else:
        print(rendered, end="")
        print("(not saved - pass --write to save)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
