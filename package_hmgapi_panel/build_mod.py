#!/usr/bin/env python3
"""Build signed HMG-API split-screen panel mod (carrotbridge-panel.zip + carrotbridge-panel-<version>.zip)."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
REPO_ROOT = BASE_DIR.parent
BUILD_DIR = BASE_DIR / "build"
STAGE_DIR = BUILD_DIR / "stage"

# Persistent keys live outside the git repository so private keys are never committed or leaked.
KEYS_DIR = Path.home() / ".hmgapi_keys"
APK_KEY_PASS = "carrotbridge"


def _version_key(path: Path) -> tuple[int, ...]:
    nums = re.findall(r"\d+", path.name)
    return tuple(int(n) for n in nums) if nums else (0,)


def find_sdk_root() -> Path:
    for env_key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        val = os.environ.get(env_key, "")
        if val and os.path.isdir(val):
            return Path(val)
    default_sdk = Path.home() / "AppData" / "Local" / "Android" / "Sdk"
    if default_sdk.is_dir():
        return default_sdk
    for unix_cand in ("/usr/local/lib/android/sdk", "/usr/lib/android-sdk"):
        if os.path.isdir(unix_cand):
            return Path(unix_cand)
    raise RuntimeError("Android SDK not found (set ANDROID_HOME)")


def find_build_tool(bt_dir: Path, name: str) -> str:
    exts = (".exe", ".bat", "") if os.name == "nt" else ("",)
    for ext in exts:
        cand = bt_dir / f"{name}{ext}"
        if cand.is_file():
            return str(cand)
    found = shutil.which(name)
    if found:
        return found
    raise RuntimeError(f"Android build tool not found: {name} in {bt_dir}")


def find_jdk_tool(name: str) -> str:
    found = shutil.which(name)
    if found:
        return found
    suffix = ".exe" if os.name == "nt" else ""
    roots = [
        r"C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin",
        r"C:\Program Files\Android\Android Studio\jbr\bin",
    ]
    for r in roots:
        cand = Path(r) / f"{name}{suffix}"
        if cand.is_file():
            return str(cand)
    raise RuntimeError(f"JDK tool not found: {name}")


def resolve_signing_keys(require_keys: bool) -> tuple[Path, Path, str, Path | None]:
    """Resolve ECDSA author keypair and APK signing keystore (CI env vars take precedence over ~/.hmgapi_keys)."""
    env_author_key = os.environ.get("HMGAPI_AUTHOR_KEY", "").strip()
    env_author_pub = os.environ.get("HMGAPI_AUTHOR_PUB", "").strip()
    env_apk_b64 = os.environ.get("HMGAPI_APK_KEYSTORE_B64", "").strip()
    env_apk_pass = os.environ.get("HMGAPI_APK_KEY_PASS", "").strip() or APK_KEY_PASS

    if any((env_author_key, env_author_pub, env_apk_b64)):
        if not all((env_author_key, env_author_pub, env_apk_b64)):
            raise RuntimeError(
                "Partial CI signing secrets: all 3 of HMGAPI_AUTHOR_KEY, HMGAPI_AUTHOR_PUB, "
                "and HMGAPI_APK_KEYSTORE_B64 must be set together"
            )
        ci_dir = BUILD_DIR / "_ci_keys"
        if ci_dir.exists():
            shutil.rmtree(ci_dir, ignore_errors=True)
        ci_dir.mkdir(parents=True, exist_ok=True)
        author_key_path = ci_dir / "carrotbridge_author.key"
        author_pub_path = ci_dir / "carrotbridge_author.key.pub"
        apk_keystore_path = ci_dir / "carrotbridge_apk.p12"
        apk_keystore_path.write_bytes(base64.b64decode(env_apk_b64))
        author_key_path.write_bytes((env_author_key + "\n").encode("utf-8"))
        author_pub_path.write_bytes((env_author_pub + "\n").encode("utf-8"))
        if os.name != "nt":
            os.chmod(ci_dir, 0o700)
            for kf in (author_key_path, author_pub_path, apk_keystore_path):
                os.chmod(kf, 0o600)
        return author_key_path, apk_keystore_path, env_apk_pass, ci_dir

    author_key_path = KEYS_DIR / "carrotbridge_author.key"
    author_pub_path = KEYS_DIR / "carrotbridge_author.key.pub"
    apk_keystore_path = KEYS_DIR / "carrotbridge_apk.p12"
    if require_keys:
        missing = [str(p) for p in (author_key_path, author_pub_path, apk_keystore_path) if not p.is_file()]
        if missing:
            raise RuntimeError(
                "Required signing key(s) missing (--require-keys): " + ", ".join(missing)
            )
    else:
        KEYS_DIR.mkdir(parents=True, exist_ok=True)
    return author_key_path, apk_keystore_path, env_apk_pass, None


def build(require_keys: bool = False) -> None:
    mod_meta = json.loads((BASE_DIR / "mod.json").read_text(encoding="utf-8"))
    mod_id = mod_meta["id"]
    mod_ver = mod_meta["version"]
    rel_apk = mod_meta["apps"][0]["apk"]

    sdk_root = find_sdk_root()
    bt_dirs = sorted([p for p in (sdk_root / "build-tools").iterdir() if p.is_dir()], key=_version_key, reverse=True)
    if not bt_dirs:
        raise RuntimeError(f"No build-tools found under {sdk_root}")
    bt_dir = bt_dirs[0]

    platforms_dir = sdk_root / "platforms"
    android_jars = sorted(platforms_dir.glob("android-*/android.jar"), key=lambda p: _version_key(p.parent), reverse=True)
    if not android_jars:
        raise RuntimeError(f"No android.jar found under {platforms_dir}")
    android_jar = str(android_jars[0])

    aapt2 = find_build_tool(bt_dir, "aapt2")
    d8 = find_build_tool(bt_dir, "d8")
    zipalign = find_build_tool(bt_dir, "zipalign")
    apksigner = find_build_tool(bt_dir, "apksigner")
    javac = find_jdk_tool("javac")
    java = find_jdk_tool("java")
    keytool = find_jdk_tool("keytool")

    if BUILD_DIR.exists():
        shutil.rmtree(BUILD_DIR)
    gen_dir = BUILD_DIR / "gen"
    classes_dir = BUILD_DIR / "classes"
    dex_dir = BUILD_DIR / "dex"
    signer_classes = BUILD_DIR / "signer_classes"
    for d in (gen_dir, classes_dir, dex_dir, signer_classes, STAGE_DIR):
        d.mkdir(parents=True, exist_ok=True)

    ci_keys_dir: Path | None = None
    try:
        author_key_path, apk_keystore_path, apk_key_pass, ci_keys_dir = resolve_signing_keys(require_keys)

        # 1. Compile resources & link base APK for Android 4.4 (API 19)
        res_zip = BUILD_DIR / "res.zip"
        base_apk = BUILD_DIR / "base.apk"
        subprocess.run([aapt2, "compile", "--dir", str(BASE_DIR / "app" / "res"), "-o", str(res_zip)], check=True)
        subprocess.run([
            aapt2, "link",
            "-I", android_jar,
            "--manifest", str(BASE_DIR / "app" / "AndroidManifest.xml"),
            "--min-sdk-version", "19",
            "--target-sdk-version", "19",
            "--java", str(gen_dir),
            "-o", str(base_apk),
            str(res_zip),
        ], check=True)

        # 2. Compile Java sources + generated R.java -> DEX 035
        java_files = [str(p) for p in sorted((BASE_DIR / "app" / "java").rglob("*.java"))]
        gen_files = [str(p) for p in sorted(gen_dir.rglob("*.java"))]
        subprocess.run([
            javac, "--release", "8", "-encoding", "UTF-8",
            "-cp", android_jar, "-d", str(classes_dir),
            *java_files, *gen_files,
        ], check=True)

        class_files = [str(p) for p in sorted(classes_dir.rglob("*.class"))]
        subprocess.run([
            d8, "--release", "--min-api", "19", "--no-desugaring",
            "--lib", android_jar, "--output", str(dex_dir), *class_files,
        ], check=True)

        dex_bytes = (dex_dir / "classes.dex").read_bytes()
        if dex_bytes[:8] != b"dex\n035\x00":
            raise RuntimeError(f"Unexpected DEX magic: {dex_bytes[:8]!r} (expected DEX 035)")

        # 3. Merge classes.dex into base.apk, zipalign, and v1-sign (Android 4.4 requires v1 JAR signature)
        unsigned_apk = BUILD_DIR / "unsigned.apk"
        with zipfile.ZipFile(base_apk, "r") as zin, zipfile.ZipFile(unsigned_apk, "w") as zout:
            for item in zin.infolist():
                zi = zipfile.ZipInfo(item.filename, date_time=(2026, 1, 1, 0, 0, 0))
                zi.compress_type = item.compress_type
                zout.writestr(zi, zin.read(item.filename))
            dex_zi = zipfile.ZipInfo("classes.dex", date_time=(2026, 1, 1, 0, 0, 0))
            dex_zi.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(dex_zi, dex_bytes)

        aligned_apk = BUILD_DIR / "aligned.apk"
        subprocess.run([zipalign, "-f", "4", str(unsigned_apk), str(aligned_apk)], check=True)

        if not apk_keystore_path.is_file():
            if require_keys:
                raise RuntimeError(f"Missing APK keystore: {apk_keystore_path}")
            subprocess.run([
                keytool, "-genkeypair",
                "-keystore", str(apk_keystore_path), "-storetype", "PKCS12",
                "-storepass", apk_key_pass, "-keypass", apk_key_pass,
                "-alias", "carrotpanel", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "10000", "-dname", "CN=GRT47 CarrotNavi",
            ], check=True)

        stage_apk = STAGE_DIR / rel_apk
        stage_apk.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([
            apksigner, "sign",
            "--ks", str(apk_keystore_path),
            "--ks-type", "PKCS12",
            "--ks-pass", f"pass:{apk_key_pass}",
            "--min-sdk-version", "9",
            "--v2-signing-enabled", "false",
            "--v3-signing-enabled", "false",
            "--out", str(stage_apk),
            str(aligned_apk),
        ], check=True)
        subprocess.run([apksigner, "verify", "--min-sdk-version", "19", str(stage_apk)], check=True)

        # 4. Stage mod.json (normalized LF) & sign mod directory with HMG-API ECDSA P-256 key
        stage_mod_json = STAGE_DIR / "mod.json"
        stage_mod_json.write_bytes((BASE_DIR / "mod.json").read_bytes().replace(b"\r\n", b"\n"))

        subprocess.run([
            javac, "--release", "8", "-encoding", "UTF-8",
            "-d", str(signer_classes), str(BASE_DIR / "signer" / "HmgModSigner.java"),
        ], check=True)
        if not author_key_path.is_file():
            if require_keys:
                raise RuntimeError(f"Missing author key: {author_key_path}")
            subprocess.run([java, "-cp", str(signer_classes), "HmgModSigner", "keygen", str(author_key_path)], check=True)
        subprocess.run([java, "-cp", str(signer_classes), "HmgModSigner", "sign", str(STAGE_DIR), str(author_key_path)], check=True)
        subprocess.run([java, "-cp", str(signer_classes), "HmgModSigner", "verify", str(STAGE_DIR)], check=True)

        # 5. Package deterministic release zip + SHA-256 + local versioned copy for USB updates
        release_zip = BUILD_DIR / f"{mod_id}.zip"
        with zipfile.ZipFile(release_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            for fp in sorted(STAGE_DIR.rglob("*")):
                if fp.is_file():
                    arc = fp.relative_to(STAGE_DIR).as_posix()
                    zi = zipfile.ZipInfo(arc, date_time=(2026, 1, 1, 0, 0, 0))
                    zi.compress_type = zipfile.ZIP_DEFLATED
                    zf.writestr(zi, fp.read_bytes())

        zip_sha = hashlib.sha256(release_zip.read_bytes()).hexdigest()
        release_sha = BUILD_DIR / f"{mod_id}.zip.sha256"
        release_sha.write_bytes(f"{zip_sha}  {mod_id}.zip\n".encode("utf-8"))

        versioned_zip = REPO_ROOT / f"{mod_id}-{mod_ver}.zip"
        shutil.copy2(release_zip, versioned_zip)

        print(f"Built signed APK:      {stage_apk} ({stage_apk.stat().st_size} bytes)")
        print(f"Built release mod zip: {release_zip} ({release_zip.stat().st_size} bytes, sha256={zip_sha[:16]}...)")
        print(f"Built USB mod copy:    {versioned_zip} ({versioned_zip.stat().st_size} bytes)")
    finally:
        if ci_keys_dir is not None and ci_keys_dir.exists():
            shutil.rmtree(ci_keys_dir, ignore_errors=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Build signed HMG-API split-screen panel mod.")
    parser.add_argument(
        "--require-keys",
        action="store_true",
        help="Fail instead of generating new keys if ECDSA author keypair or APK keystore is missing.",
    )
    args = parser.parse_args()
    build(require_keys=args.require_keys)


if __name__ == "__main__":
    main()
