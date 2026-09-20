"""Small adb UI driver, restricted to the dedicated acceptance emulator (5580)."""
import argparse
import json
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8")
ADB = r"D:\Android\Sdk\platform-tools\adb.exe"


def adb(*args):
    result = subprocess.run([ADB, "-s", "emulator-5580", *args], check=True, capture_output=True)
    return result.stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/acceptance-window.xml")
    return list(ET.fromstring(adb("shell", "cat", "/sdcard/acceptance-window.xml")).iter("node"))


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.5)


parser = argparse.ArgumentParser()
parser.add_argument("action", choices=["dump", "tap", "fill", "capture"])
parser.add_argument("value", nargs="?")
parser.add_argument("text", nargs="?")
args = parser.parse_args()
if args.action == "capture":
    pathlib.Path(args.value).write_bytes(adb("exec-out", "screencap", "-p"))
else:
    tree = nodes()
    if args.action == "dump":
        for node in tree:
            a = node.attrib
            if a.get("text") or a.get("content-desc") or a.get("class") == "android.widget.EditText":
                print(json.dumps({k: a.get(k) for k in ["text", "content-desc", "class", "bounds", "checked", "enabled"]}, ensure_ascii=False))
    elif args.action == "tap":
        matches = [n for n in tree if args.value in (n.get("text"), n.get("content-desc"))]
        if len(matches) != 1:
            raise RuntimeError(f"Expected one visible match for {args.value!r}, got {len(matches)}")
        tap(matches[0])
    elif args.action == "fill":
        edits = [n for n in tree if n.get("class") == "android.widget.EditText"]
        tap(edits[int(args.value)])
        adb("shell", "input", "text", args.text)
