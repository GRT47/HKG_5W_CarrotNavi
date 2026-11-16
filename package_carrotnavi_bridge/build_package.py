#!/usr/bin/env python3
"""Build NEMESIS install package, DEX jar, version.json, and USB bundle for HKG_5W_CarrotNavi."""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import struct
import subprocess
import zipfile
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
REPO_ROOT = BASE_DIR.parent
BUILD_DIR = BASE_DIR / "build"
STUB_CLASSES_DIR = BUILD_DIR / "stub_classes"
CLASSES_DIR = BUILD_DIR / "classes"
DEX_DIR = BUILD_DIR / "dex"
PKG_STAGE_DIR = BUILD_DIR / "pkg_stage"
USB_ROOT_DIR = BUILD_DIR / "usb_root"

OUTER_ZIP_PATH = REPO_ROOT / "HKG_5W_CarrotNavi_USB.zip"
VERSION_JSON_PATH = REPO_ROOT / "version.json"

BRIDGE_VERSION = "1.3.4"
GITHUB_OWNER = "GRT47"
GITHUB_REPO = "HKG_5W_CarrotNavi"
GITHUB_BRANCH = "main"


def find_javac() -> str:
    javac = shutil.which("javac")
    if javac:
        return javac
    candidates = [
        r"C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\javac.exe",
        r"C:\Program Files\Android\Android Studio\jbr\bin\javac.exe",
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    raise RuntimeError("javac not found in PATH or standard locations")


def find_java() -> str:
    java = shutil.which("java")
    if java:
        return java
    javac = find_javac()
    return str(Path(javac).with_name("java.exe" if os.name == "nt" else "java"))


def find_d8() -> str:
    d8 = shutil.which("d8") or shutil.which("d8.bat")
    if d8:
        return d8
    sdk_roots = [
        os.environ.get("ANDROID_HOME", ""),
        os.environ.get("ANDROID_SDK_ROOT", ""),
        os.path.expanduser(r"~\AppData\Local\Android\Sdk"),
        "/usr/local/lib/android/sdk",
        "/usr/lib/android-sdk",
    ]
    for root in sdk_roots:
        if not root or not os.path.isdir(root):
            continue
        bt_dir = Path(root) / "build-tools"
        if bt_dir.is_dir():
            for ver_dir in sorted(bt_dir.iterdir(), reverse=True):
                for name in ("d8.bat", "d8"):
                    cand = ver_dir / name
                    if cand.is_file():
                        return str(cand)
    raise RuntimeError("Android SDK d8 not found")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def normalize_lf(path: Path) -> bytes:
    raw = path.read_bytes().replace(b"\r\n", b"\n")
    path.write_bytes(raw)
    return raw


def _parse_uleb128(data: bytes, off: int) -> tuple[int, int]:
    val = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        val |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            return val, off
        shift += 7


def verify_dex_classes(dex_bytes: bytes) -> None:
    """Verify DEX 035 defines only com.hkg.carrotbridge.* classes (no leaked android.* stubs)."""
    if dex_bytes[:8] != b"dex\n035\x00":
        raise RuntimeError(f"Unexpected DEX magic header: {dex_bytes[:8]!r} (expected b'dex\\n035\\x00')")
    hdr = struct.unpack_from("<8sI20sIIIIIIIIIIIIIIIIII", dex_bytes, 0)
    str_cnt, str_off = hdr[9], hdr[10]
    type_cnt, type_off = hdr[11], hdr[12]
    cls_cnt, cls_off = hdr[19], hdr[20]

    strings = []
    for i in range(str_cnt):
        soff = struct.unpack_from("<I", dex_bytes, str_off + i * 4)[0]
        _, pos = _parse_uleb128(dex_bytes, soff)
        end = dex_bytes.find(b"\x00", pos)
        strings.append(dex_bytes[pos:end].decode("utf-8", errors="replace"))

    types = [strings[struct.unpack_from("<I", dex_bytes, type_off + i * 4)[0]] for i in range(type_cnt)]
    defined = set()
    for i in range(cls_cnt):
        c_idx = struct.unpack_from("<I", dex_bytes, cls_off + i * 32)[0]
        cname = types[c_idx]
        if not cname.startswith("Lcom/hkg/carrotbridge/"):
            raise RuntimeError(f"Stub or unexpected class leaked into classes.dex: {cname}")
        defined.add(cname)

    if "Landroid/location/IGpsStatusListener$Stub;" not in types or "Landroid/location/IGpsStatusListener$Stub;" in defined:
        raise RuntimeError("IGpsStatusListener$Stub must be referenced externally, not defined in DEX")


def build() -> None:
    if BUILD_DIR.exists():
        shutil.rmtree(BUILD_DIR)
    STUB_CLASSES_DIR.mkdir(parents=True, exist_ok=True)
    CLASSES_DIR.mkdir(parents=True, exist_ok=True)
    DEX_DIR.mkdir(parents=True, exist_ok=True)
    PKG_STAGE_DIR.mkdir(parents=True, exist_ok=True)
    USB_ROOT_DIR.mkdir(parents=True, exist_ok=True)

    # 1. Normalize LF line endings on all source scripts/configs
    source_files = [
        "NEMESIS.data",
        "config/bridge_config.json",
        "scripts/post.sh",
        "scripts/preun.sh",
        "scripts/tbt-dump.sh",
        "bin/launch.sh",
        "svc/bridge-daemon.sh",
    ]
    for rel in source_files:
        normalize_lf(BASE_DIR / rel)

    # 2. Generate version.json in repository root with SHA-256 hashes of updatable source files
    updatable_map = {
        "svc/bridge-daemon.sh": "package_carrotnavi_bridge/svc/bridge-daemon.sh",
        "bin/launch.sh": "package_carrotnavi_bridge/bin/launch.sh",
        "scripts/tbt-dump.sh": "package_carrotnavi_bridge/scripts/tbt-dump.sh",
        "config/bridge_config.json": "package_carrotnavi_bridge/config/bridge_config.json",
    }
    files_manifest = {}
    for target_rel, repo_rel in updatable_map.items():
        data = (REPO_ROOT / repo_rel).read_bytes()
        files_manifest[target_rel] = {
            "remote": repo_rel,
            "sha256": sha256_bytes(data),
        }

    version_doc = {
        "version": BRIDGE_VERSION,
        "repository": f"https://github.com/{GITHUB_OWNER}/{GITHUB_REPO}.git",
        "branch": GITHUB_BRANCH,
        "release_jar_url": f"https://github.com/{GITHUB_OWNER}/{GITHUB_REPO}/releases/latest/download/carrotbridge.jar",
        "release_jar_sha256_url": f"https://github.com/{GITHUB_OWNER}/{GITHUB_REPO}/releases/latest/download/carrotbridge.jar.sha256",
        "files": files_manifest,
    }
    version_bytes = (json.dumps(version_doc, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    VERSION_JSON_PATH.write_bytes(version_bytes)

    # 3. Compile compile-only Android stubs, then application Java sources (Java 8 target -> Android API 19 DEX)
    javac = find_javac()
    java = find_java()
    d8 = find_d8()
    stub_sources = [str(p) for p in sorted((BASE_DIR / "src_stub").rglob("*.java"))]
    java_sources = [str(p) for p in sorted((BASE_DIR / "src_java").rglob("*.java"))]

    subprocess.run(
        [javac, "--release", "8", "-encoding", "UTF-8", "-d", str(STUB_CLASSES_DIR), *stub_sources],
        check=True,
    )
    subprocess.run(
        [javac, "--release", "8", "-encoding", "UTF-8", "-cp", str(STUB_CLASSES_DIR), "-d", str(CLASSES_DIR), *java_sources],
        check=True,
    )

    # 4. Run host self-test suite on compiled classes (+ stubs on host classpath)
    subprocess.run(
        [java, "-cp", f"{CLASSES_DIR}{os.pathsep}{STUB_CLASSES_DIR}", "com.hkg.carrotbridge.CarrotBridgeMain", "test-self"],
        check=True,
    )

    # 5. Convert app .class files to Dalvik DEX (--min-api 19 -> DEX 035; stubs stay classpath-only)
    class_files = [str(p) for p in sorted(CLASSES_DIR.rglob("*.class"))]
    subprocess.run(
        [d8, "--min-api", "19", "--no-desugaring", "--classpath", str(STUB_CLASSES_DIR), "--output", str(DEX_DIR), *class_files],
        check=True,
    )

    classes_dex = DEX_DIR / "classes.dex"
    dex_bytes = classes_dex.read_bytes()
    verify_dex_classes(dex_bytes)

    jar_path = BUILD_DIR / "carrotbridge.jar"
    with zipfile.ZipFile(jar_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zinfo = zipfile.ZipInfo("classes.dex", date_time=(2026, 1, 1, 0, 0, 0))
        zinfo.compress_type = zipfile.ZIP_DEFLATED
        zf.writestr(zinfo, dex_bytes)

    jar_sha = sha256_bytes(jar_path.read_bytes())
    (BUILD_DIR / "carrotbridge.jar.sha256").write_bytes(f"{jar_sha}  carrotbridge.jar\n".encode("utf-8"))

    # 6. Stage NEMESIS package files
    for rel in source_files:
        src = BASE_DIR / rel
        dst = PKG_STAGE_DIR / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)

    (PKG_STAGE_DIR / "version.json").write_bytes(version_bytes)
    stage_jar = PKG_STAGE_DIR / "bin" / "carrotbridge.jar"
    stage_jar.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(jar_path, stage_jar)

    # 7. Build inner NEMESIS package zip (packages/carrotnavi.bridge.zip)
    inner_zip_path = USB_ROOT_DIR / "packages" / "carrotnavi.bridge.zip"
    inner_zip_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(inner_zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for file_path in sorted(PKG_STAGE_DIR.rglob("*")):
            if file_path.is_file():
                arcname = file_path.relative_to(PKG_STAGE_DIR).as_posix()
                zf.write(file_path, arcname)

    # 8. Build USB root descriptor (usb_root/NEMESIS.data)
    usb_meta = (
        "[nemesis]\n"
        "version=1.0\n"
        "type=install\n"
        "path=packages/carrotnavi.bridge.zip\n"
    )
    (USB_ROOT_DIR / "NEMESIS.data").write_bytes(usb_meta.encode("utf-8"))

    # 9. Build outer USB bundle zip (HKG_5W_CarrotNavi_USB.zip)
    with zipfile.ZipFile(OUTER_ZIP_PATH, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for file_path in sorted(USB_ROOT_DIR.rglob("*")):
            if file_path.is_file():
                arcname = file_path.relative_to(USB_ROOT_DIR).as_posix()
                zf.write(file_path, arcname)

    print(f"Built DEX jar:       {jar_path} ({jar_path.stat().st_size} bytes, sha256={jar_sha[:16]}...)")
    print(f"Built inner package: {inner_zip_path} ({inner_zip_path.stat().st_size} bytes)")
    print(f"Built USB bundle:    {OUTER_ZIP_PATH} ({OUTER_ZIP_PATH.stat().st_size} bytes)")


if __name__ == "__main__":
    build()
