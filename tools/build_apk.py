# -*- coding: utf-8 -*-
"""EatWhat APK 离线构建脚本（无 Gradle/Android Studio）。

复用 astrbot_plugin_oppo_watch/_android_build 的离线工具链：
android.jar(API27) / aapt / ECJ / d8 / zipalign / apksigner。

用法:
  python tools/build_apk.py                 # 产物 out/EatWhat.apk
  python tools/build_apk.py --open          # 构建后打开输出目录

工具链查找顺序: 环境变量 EATWHAT_TOOLCHAIN -> ../astrbot_plugin_oppo_watch/_android_build
"""
import argparse
import os
import shutil
import subprocess

BASE = os.path.dirname(os.path.abspath(__file__))            # tools/
REPO = os.path.dirname(BASE)
APP_DIR = os.path.join(REPO, "phone_app")
OUT = os.path.join(REPO, "out")
BUILD = os.path.join(REPO, ".build")

NAME = "EatWhat"
MIN_SDK = 26
TARGET_SDK = 28
KEYSTORE = os.path.join(BASE, "eatwhat.keystore")

TOOLCHAIN_CANDIDATES = [
    os.environ.get("EATWHAT_TOOLCHAIN", ""),
    os.path.join(os.path.dirname(REPO), "astrbot_plugin_oppo_watch", "_android_build"),
]


def find_toolchain():
    for t in TOOLCHAIN_CANDIDATES:
        if t and os.path.exists(os.path.join(t, "_platform27", "android-8.1.0", "android.jar")):
            return t
    return None


def run(cmd, desc):
    print("\n== %s ==\n  %s" % (desc, " ".join(cmd)))
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.stdout:
        print(r.stdout[-2000:])
    if r.stderr:
        print("[stderr]", r.stderr[-2000:])
    if r.returncode != 0:
        print("!! 失败:", desc, "returncode", r.returncode)
        return False
    return True


def find_keytool():
    for k in ("keytool", r"C:\Program Files\Java\jre-1.8\bin\keytool.exe"):
        try:
            if subprocess.run([k, "-help"], capture_output=True).returncode == 0:
                return k
        except Exception:
            continue
    return None


def ensure_keystore():
    if os.path.exists(KEYSTORE):
        return True
    keytool = find_keytool()
    if keytool is None:
        print("!! 找不到 keytool，无法生成签名密钥")
        return False
    return run([keytool, "-genkeypair", "-v", "-keystore", KEYSTORE, "-alias", "eatwhat",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                "-storepass", "eatwhat123", "-keypass", "eatwhat123",
                "-dname", "CN=EatWhat,O=Personal,C=CN"], "keytool 生成签名密钥")


def main():
    ap = argparse.ArgumentParser(description="离线构建 EatWhat APK")
    ap.add_argument("--open", action="store_true", help="构建完成后打开输出目录")
    args = ap.parse_args()

    tc = find_toolchain()
    if tc is None:
        print("!! 找不到离线工具链（android.jar/aapt/d8）。")
        print("   请设置环境变量 EATWHAT_TOOLCHAIN 指向 astrbot_plugin_oppo_watch/_android_build")
        return 1
    print("工具链:", tc)

    android_jar = os.path.join(tc, "_platform27", "android-8.1.0", "android.jar")
    aapt = os.path.join(tc, "android-9", "aapt.exe")
    d8_jar = os.path.join(tc, "android-9", "lib", "d8.jar")
    apksigner_jar = os.path.join(tc, "android-9", "lib", "apksigner.jar")
    zipalign = os.path.join(tc, "android-9", "zipalign.exe")
    ecj = os.path.join(tc, "ecj.jar")

    manifest = os.path.join(APP_DIR, "AndroidManifest.xml")
    res_dir = os.path.join(APP_DIR, "res")
    src_dir = os.path.join(APP_DIR, "src")
    for p in (android_jar, manifest, res_dir, src_dir, aapt, d8_jar, apksigner_jar, zipalign, ecj):
        if not os.path.exists(p):
            print("!! 缺失:", p)
            return 1

    shutil.rmtree(BUILD, ignore_errors=True)
    os.makedirs(BUILD, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)
    apk_unsigned = os.path.join(OUT, "%s-unsigned.apk" % NAME)
    apk_aligned = os.path.join(OUT, "%s-aligned.apk" % NAME)
    apk_signed = os.path.join(OUT, "%s.apk" % NAME)
    for p in (apk_unsigned, apk_aligned, apk_signed):
        if os.path.exists(p):
            os.remove(p)

    # 1. aapt: 打包资源 + 生成 R.java
    gen = os.path.join(BUILD, "gen")
    os.makedirs(gen, exist_ok=True)
    if not run([aapt, "package", "-f", "-m", "-J", gen, "-M", manifest, "-S", res_dir,
                "-I", android_jar, "-F", apk_unsigned,
                "--min-sdk-version", str(MIN_SDK), "--target-sdk-version", str(TARGET_SDK)],
               "aapt 打包资源"):
        return 1

    # 2. ECJ: 编译 Java 源码 + R.java
    classes = os.path.join(BUILD, "classes")
    os.makedirs(classes, exist_ok=True)
    java_files = []
    for root_dir in (src_dir, gen):
        for root, _, files in os.walk(root_dir):
            java_files += [os.path.join(root, f) for f in files if f.endswith(".java")]
    if not run(["java", "-jar", ecj, "-8", "-encoding", "UTF-8",
                "-classpath", android_jar, "-d", classes] + java_files, "ECJ 编译"):
        return 1

    # 3. d8: 转 dex
    dex_dir = os.path.join(BUILD, "dex")
    os.makedirs(dex_dir, exist_ok=True)
    class_files = []
    for root, _, files in os.walk(classes):
        class_files += [os.path.join(root, f) for f in files if f.endswith(".class")]
    if not run(["java", "-jar", d8_jar, "--release", "--lib", android_jar,
                "--min-api", str(MIN_SDK), "--output", dex_dir] + class_files, "d8 转 dex"):
        return 1

    # 4. aapt: dex 加入 APK（在 dex 目录内以相对路径添加）
    old_cwd = os.getcwd()
    os.chdir(dex_dir)
    ok = run([aapt, "add", apk_unsigned, "classes.dex"], "aapt 添加 dex")
    os.chdir(old_cwd)
    if not ok:
        return 1

    # 5. zipalign + 6. 签名（密钥持久化，保证可覆盖安装）
    if not run([zipalign, "-f", "4", apk_unsigned, apk_aligned], "zipalign"):
        return 1
    if not ensure_keystore():
        return 1
    if not run(["java", "-jar", apksigner_jar, "sign",
                "--ks", KEYSTORE, "--ks-pass", "pass:eatwhat123",
                "--key-pass", "pass:eatwhat123", "--out", apk_signed, apk_aligned], "apksigner 签名"):
        return 1

    for suffix in ("-unsigned.apk", "-aligned.apk"):
        p = os.path.join(OUT, NAME + suffix)
        if os.path.exists(p):
            os.remove(p)

    print("\n=== 构建完成 ===")
    print("  %s (%d bytes)" % (apk_signed, os.path.getsize(apk_signed)))
    if args.open:
        subprocess.Popen(["explorer", "/select,", apk_signed])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
