#!/usr/bin/env python3
"""dist/의 빌드 결과물로 dist/update.json(자동 업데이트 정보)을 만든다.

버전은 소스에서 읽는다:
  - Windows: blocker/version.go 의 appVersion
  - Android: android/app/build.gradle 의 versionCode / versionName

사용법:
  python3 scripts/make_update_json.py --notes "바뀐 점 한 줄" [--android-notes "..."] [--windows-notes "..."]

앱은 기본 브랜치의 dist/update.json을 읽으므로, 커밋해 기본 브랜치에 올리면 배포된다.
"""
import argparse
import hashlib
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE_URL = "https://raw.githubusercontent.com/souljjab/Study/HEAD/dist/"


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as f:
        return f.read()


def file_info(name):
    path = os.path.join(ROOT, "dist", name)
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return {"url": BASE_URL + name, "sha256": h.hexdigest(), "size": os.path.getsize(path)}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--notes", default="", help="두 앱 공통 변경 내용")
    ap.add_argument("--android-notes", default=None)
    ap.add_argument("--windows-notes", default=None)
    a = ap.parse_args()

    win_ver = re.search(r'appVersion\s*=\s*"([^"]+)"', read("blocker/version.go"))
    gradle = read("android/app/build.gradle")
    code = re.search(r"versionCode\s+(\d+)", gradle)
    name = re.search(r"versionName\s+'([^']+)'", gradle)
    if not (win_ver and code and name):
        sys.exit("버전을 읽지 못했어요: blocker/version.go, android/app/build.gradle 확인")

    out = {
        "windows": {"version": win_ver.group(1), **file_info("StudyBlocker.exe"),
                    "notes": a.windows_notes if a.windows_notes is not None else a.notes},
        "android": {"versionCode": int(code.group(1)), "versionName": name.group(1), **file_info("StudyBlocker.apk"),
                    "notes": a.android_notes if a.android_notes is not None else a.notes},
    }
    with open(os.path.join(ROOT, "dist", "update.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(json.dumps(out, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
