#!/usr/bin/env python3
"""Builds Screenshot-Marker's APK with nothing but a JDK and Python. Version: 2026-11-18

No Android SDK and no Gradle: the build fetches three jars from public repositories (checked by
SHA-256), compiles the Java sources against android.jar, turns them into classes.dex with dx,
writes the binary AndroidManifest.xml and a one-entry resources.arsc itself, zips everything with
4-byte alignment and signs it with APK Signature Scheme v2.

    python tools/build.py              # build/ScreenshotMarker-<version>.apk, signed
    python tools/build.py --unsigned   # only build/unsigned.apk, no key needed

The signing key: keystore/screenshot-marker.p12 (alias "marker") with its password in
keystore/password.txt, or the paths and password in SCREENSHOT_MARKER_KEYSTORE and
SCREENSHOT_MARKER_KEYSTORE_PASSWORD. Both stay out of git. Updates only install over an
installed app when they are signed with the same key.
"""
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

# ---- the one place for the version ------------------------------------------------------------
VERSION_CODE = 2
VERSION_NAME = "1.1"

PKG = "app.screenmarker"
APP_LABEL = "Screenshot-Marker"
MIN_SDK, TARGET_SDK = 29, 34

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / ".tools"
BUILD = ROOT / "build"
SRC = ROOT / "app" / "src"
ICON = ROOT / "app" / "res" / "icon.png"

JARS = {
    # android.jar of API level 34 (stubs and framework resources), from the Sable/android-platforms collection
    "android.jar": ("https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar",
                    "6cea1df3efb77103ac3e2beb9bf4718964b0e0869ab16d39d29d5cbae1c147ad"),
    # dx, Android's dexer, repackaged on Maven Central
    "dx.jar": ("https://repo1.maven.org/maven2/com/jakewharton/android/repackaged/dalvik-dx/16.0.1/dalvik-dx-16.0.1.jar",
               "1e4b645628e3bdb097b5331d669e177ef235a551582a8c646dbe36865e541907"),
    # apksig, the library behind apksigner, on Maven Central
    "apksig.jar": ("https://repo1.maven.org/maven2/com/android/tools/build/apksig/2.3.0/apksig-2.3.0.jar",
                   "9637078c0016244e4be0941836295365a7e2e5b164c59cb7885783c40460bfee"),
}

# apksig 2.3.0 reaches into JDK internals; newer JDKs need them opened.
JAVA_OPENS = ["--add-exports", "java.base/sun.security.x509=ALL-UNNAMED",
              "--add-exports", "java.base/sun.security.pkcs=ALL-UNNAMED",
              "--add-exports", "java.base/sun.security.util=ALL-UNNAMED"]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fetch_tools():
    TOOLS.mkdir(exist_ok=True)
    for name, (url, digest) in JARS.items():
        target = TOOLS / name
        if target.exists() and sha256(target) == digest:
            continue
        print(f"Lade {name} …")
        tmp = target.with_suffix(".part")
        with urllib.request.urlopen(url) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f)
        got = sha256(tmp)
        if got != digest:
            tmp.unlink()
            sys.exit(f"{name}: Prüfsumme stimmt nicht ({got}), abgebrochen.")
        tmp.replace(target)


def run(cmd, env=None):
    result = subprocess.run(cmd, env=env)
    if result.returncode != 0:
        sys.exit(f"Fehlgeschlagen: {' '.join(str(c) for c in cmd[:3])} …")


# ---- binary XML (AndroidManifest.xml) -----------------------------------------------------------
ANDROID_NS = "http://schemas.android.com/apk/res/android"
ICON_PATH = "res/drawable-nodpi-v4/icon.png"
ICON_ID = 0x7F010000
ATTR_IDS = {  # from android.R.attr
    "theme": 0x01010000, "label": 0x01010001, "icon": 0x01010002, "name": 0x01010003,
    "exported": 0x01010010, "configChanges": 0x0101001F, "mimeType": 0x01010026,
    "minSdkVersion": 0x0101020C, "versionCode": 0x0101021B, "versionName": 0x0101021C,
    "targetSdkVersion": 0x01010270,
}
THEME_MATERIAL_NO_ACTION_BAR = 0x0103022E  # android.R.style.Theme_Material_NoActionBar
T_REF, T_STRING, T_INT_DEC, T_INT_HEX, T_BOOL = 0x01, 0x03, 0x10, 0x11, 0x12


def E(tag, attrs=(), children=()):
    return (tag, list(attrs), list(children))


def A(name, typ, val, ns=True):
    return (ns, name, typ, val)


def intent_filter(action, mime=None, launcher=False):
    category = "android.intent.category.LAUNCHER" if launcher else "android.intent.category.DEFAULT"
    children = [E("action", [A("name", T_STRING, action)]), E("category", [A("name", T_STRING, category)])]
    if mime:
        children.append(E("data", [A("mimeType", T_STRING, mime)]))
    return E("intent-filter", [], children)


def manifest():
    return E("manifest", [
        A("versionCode", T_INT_DEC, VERSION_CODE), A("versionName", T_STRING, VERSION_NAME),
        A("package", T_STRING, PKG, ns=False),
    ], [
        E("uses-sdk", [A("minSdkVersion", T_INT_DEC, MIN_SDK), A("targetSdkVersion", T_INT_DEC, TARGET_SDK)]),
        E("application", [
            A("theme", T_REF, THEME_MATERIAL_NO_ACTION_BAR),
            A("label", T_STRING, APP_LABEL),
            A("icon", T_REF, ICON_ID),
        ], [
            E("activity", [
                A("name", T_STRING, PKG + ".MainActivity"),
                A("exported", T_BOOL, True),
                # orientation | keyboardHidden | screenLayout | uiMode | screenSize | smallestScreenSize:
                # the drawing survives turning the phone
                A("configChanges", T_INT_HEX, 0xFA0),
            ], [
                intent_filter("android.intent.action.MAIN", launcher=True),
                intent_filter("android.intent.action.SEND", "image/*"),   # "Teilen" from any app
                intent_filter("android.intent.action.EDIT", "image/*"),   # "Bearbeiten mit" in galleries
            ]),
        ]),
    ])


def pad4(b):
    return b + b"\0" * ((4 - len(b) % 4) % 4)


def string_pool(strings, utf8=False):
    offsets, data = [], b""
    for s in strings:
        offsets.append(len(data))
        if utf8:
            enc = s.encode("utf-8")
            assert len(s) < 128 and len(enc) < 128
            data += bytes([len(s), len(enc)]) + enc + b"\0"
        else:
            data += struct.pack("<H", len(s)) + s.encode("utf-16-le") + b"\0\0"
    data = pad4(data)
    header = 28
    body = b"".join(struct.pack("<I", o) for o in offsets) + data
    return struct.pack("<HHIIIIII", 0x0001, header, header + len(body), len(strings), 0,
                       0x100 if utf8 else 0, header + 4 * len(strings), 0) + body


def binary_xml(root):
    attr_names = []

    def collect(el):
        for ns, n, _, _ in el[1]:
            if ns and n not in attr_names:
                attr_names.append(n)
        for c in el[2]:
            collect(c)

    collect(root)
    attr_names.sort(key=lambda n: ATTR_IDS[n])
    strings = list(attr_names)  # attribute names first: the resource map covers them

    def idx(s):
        if s not in strings:
            strings.append(s)
        return strings.index(s)

    def chunk(typ, payload):
        return struct.pack("<HHIII", typ, 16, 16 + len(payload), 1, 0xFFFFFFFF) + payload

    body = []

    def emit(el):
        tag, attrs, children = el
        attrs = sorted(attrs, key=lambda a: ATTR_IDS.get(a[1], 0x7FFFFFFF) if a[0] else -1)
        raw = b""
        for ns, n, t, v in attrs:
            ns_i = idx(ANDROID_NS) if ns else 0xFFFFFFFF
            if t == T_STRING:
                value_raw = data = idx(v)
            else:
                value_raw = 0xFFFFFFFF
                data = (0xFFFFFFFF if v else 0) if t == T_BOOL else v
            raw += struct.pack("<IIIHBBI", ns_i, idx(n), value_raw, 8, 0, t, data)
        body.append(chunk(0x0102, struct.pack("<IIHHHHHH", 0xFFFFFFFF, idx(tag), 20, 20, len(attrs), 0, 0, 0) + raw))
        for c in children:
            emit(c)
        body.append(chunk(0x0103, struct.pack("<II", 0xFFFFFFFF, idx(tag))))

    ns_payload = struct.pack("<II", idx("android"), idx(ANDROID_NS))
    start_ns = chunk(0x0100, ns_payload)
    emit(root)
    end_ns = chunk(0x0101, ns_payload)
    resmap = struct.pack("<HHI", 0x0180, 8, 8 + 4 * len(attr_names)) + b"".join(struct.pack("<I", ATTR_IDS[n]) for n in attr_names)
    content = string_pool(strings) + resmap + start_ns + b"".join(body) + end_ns
    return struct.pack("<HHI", 0x0003, 8, 8 + len(content)) + content


# ---- resources.arsc: one drawable, the icon -----------------------------------------------------
def resources_arsc():
    values = string_pool([ICON_PATH], utf8=True)
    type_strings = string_pool(["drawable"], utf8=True)
    key_strings = string_pool(["icon"], utf8=True)
    spec = struct.pack("<HHIBBHI", 0x0202, 16, 20, 1, 0, 0, 1) + struct.pack("<I", 0)
    config = bytearray(64)
    struct.pack_into("<I", config, 0, 64)       # size
    struct.pack_into("<H", config, 14, 0xFFFF)  # density: nodpi
    struct.pack_into("<H", config, 24, 4)       # sdkVersion 4, as in the -v4 path
    entries_start = 20 + 64 + 4
    entry = struct.pack("<HHI", 8, 0, 0) + struct.pack("<HBBI", 8, 0, T_STRING, 0)
    typ = (struct.pack("<HHIBBHII", 0x0201, 20 + 64, entries_start + len(entry), 1, 0, 0, 1, entries_start)
           + bytes(config) + struct.pack("<I", 0) + entry)
    header = 288
    pkg_body = type_strings + key_strings + spec + typ
    pkg = (struct.pack("<HHII", 0x0200, header, header + len(pkg_body), 0x7F)
           + PKG.encode("utf-16-le").ljust(256, b"\0")
           + struct.pack("<IIIII", header, 0, header + len(type_strings), 0, 0) + pkg_body)
    content = values + pkg
    return struct.pack("<HHII", 0x0002, 12, 12 + len(content), 1) + content


def write_apk(path, files):
    """Stored entries start on a 4-byte boundary, as Android 11+ requires for resources.arsc."""
    with zipfile.ZipFile(path, "w") as z:
        for name, data, compress in files:
            zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
            if not compress:
                offset = z.fp.tell() + 30 + len(name.encode())
                zi.extra = b"\0" * ((4 - offset % 4) % 4)
            z.writestr(zi, data)


def main():
    unsigned_only = "--unsigned" in sys.argv
    fetch_tools()
    if BUILD.exists():
        shutil.rmtree(BUILD)
    classes = BUILD / "classes"
    classes.mkdir(parents=True)
    android_jar = str(TOOLS / "android.jar")

    sources = sorted(str(p) for p in SRC.rglob("*.java"))
    print(f"Kompiliere {len(sources)} Dateien …")
    run(["javac", "-Xlint:-options", "-nowarn", "-source", "8", "-target", "8", "-encoding", "UTF-8",
         "-bootclasspath", android_jar, "-d", str(classes)] + sources)
    run(["java", "-cp", str(TOOLS / "dx.jar"), "com.android.dx.command.Main", "--dex",
         "--min-sdk-version=26", "--output=" + str(BUILD / "classes.dex"), str(classes)])

    unsigned = BUILD / "unsigned.apk"
    write_apk(unsigned, [
        ("AndroidManifest.xml", binary_xml(manifest()), True),
        ("resources.arsc", resources_arsc(), False),
        ("classes.dex", (BUILD / "classes.dex").read_bytes(), True),
        (ICON_PATH, ICON.read_bytes(), False),
    ])
    if unsigned_only:
        print(f"Fertig: {unsigned}")
        return

    keystore = Path(os.environ.get("SCREENSHOT_MARKER_KEYSTORE", ROOT / "keystore" / "screenshot-marker.p12"))
    password = os.environ.get("SCREENSHOT_MARKER_KEYSTORE_PASSWORD")
    if password is None and (ROOT / "keystore" / "password.txt").exists():
        password = (ROOT / "keystore" / "password.txt").read_text().strip()
    if not keystore.exists() or not password:
        sys.exit(f"Kein Signaturschlüssel: {keystore} mit Passwort fehlt. Siehe README, oder --unsigned.")

    signer = BUILD / "signer"
    signer.mkdir()
    run(["javac", "-nowarn", "-cp", str(TOOLS / "apksig.jar"), "-d", str(signer),
         str(ROOT / "tools" / "Sign.java"), str(ROOT / "tools" / "Verify.java")])
    cp = os.pathsep.join([str(TOOLS / "apksig.jar"), str(signer)])
    out = BUILD / f"ScreenshotMarker-{VERSION_NAME}.apk"
    env = dict(os.environ, SCREENSHOT_MARKER_KEYSTORE_PASSWORD=password)
    run(["java"] + JAVA_OPENS + ["-cp", cp, "Sign", str(unsigned), str(out), str(keystore)], env=env)
    run(["java"] + JAVA_OPENS + ["-cp", cp, "Verify", str(out)])
    print(f"Fertig: {out}  (SHA-256 {sha256(out)})")


if __name__ == "__main__":
    main()
