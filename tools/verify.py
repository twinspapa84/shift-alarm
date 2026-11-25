#!/usr/bin/env python3
"""shift-alarm-app 정적 검증 스크립트.

컴파일러가 잡지 못하는 '선언과 참조의 어긋남'을 점검한다.
 1) Gradle 설정(패키지명, minSdk 26, targetSdk/compileSdk 34)
 2) AndroidManifest 권한/컴포넌트 등록과 실제 클래스 파일 일치
 3) 코드·레이아웃의 R.string / R.drawable / R.color 참조가 리소스에 존재
 4) 레이아웃 id 와 ViewBinding(binding.xxx) 참조 일치
 5) getString(...) 인자 개수와 문자열 포맷 플레이스홀더 개수 일치
 6) GitHub Actions 워크플로 필수 스텝

사용법: python3 tools/verify.py
종료 코드 0 = 모두 통과, 1 = 실패 항목 있음.
"""
from __future__ import annotations

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app")
SRC = os.path.join(APP, "src", "main", "java")
RES = os.path.join(APP, "src", "main", "res")
MANIFEST = os.path.join(APP, "src", "main", "AndroidManifest.xml")
NS = "{http://schemas.android.com/apk/res/android}"

results: list[tuple[bool, str]] = []


def check(ok: bool, label: str, detail: str = "") -> None:
    results.append((ok, f"{label}{(' — ' + detail) if detail else ''}"))


def read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def kotlin_files() -> list[str]:
    return glob.glob(os.path.join(SRC, "**", "*.kt"), recursive=True)


def declared_package(path: str) -> str:
    m = re.search(r"^package\s+([\w.]+)", read(path), re.M)
    return m.group(1) if m else ""


def resource_names(subdir: str, node: str) -> set[str]:
    """values* 폴더의 특정 노드 이름을 모두 모은다."""
    names: set[str] = set()
    for path in glob.glob(os.path.join(RES, "values*", "*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for item in root.findall(node):
            name = item.get("name")
            if name:
                names.add(name)
    return names


# --------------------------------------------------------------- 1) Gradle 설정
gradle = read(os.path.join(APP, "build.gradle.kts"))
check('namespace = "io.github.twinspapa84.shiftalarm"' in gradle, "namespace = io.github.twinspapa84.shiftalarm")
check('applicationId = "io.github.twinspapa84.shiftalarm"' in gradle, "applicationId = io.github.twinspapa84.shiftalarm")
check(re.search(r"minSdk\s*=\s*26", gradle) is not None, "minSdk 26 (Android 8.0+)")
check(re.search(r"targetSdk\s*=\s*34", gradle) is not None, "targetSdk 34")
check(re.search(r"compileSdk\s*=\s*34", gradle) is not None, "compileSdk 34")
check('id("org.jetbrains.kotlin.android")' in gradle, "Kotlin 플러그인 적용")
check('implementation("com.google.android.material:material' in gradle, "Material 3 의존성")
check('testImplementation("junit:junit' in gradle, "JUnit 단위 테스트 의존성")

# --------------------------------------------------------------- 2) Manifest
manifest = ET.parse(MANIFEST).getroot()
package_name = "io.github.twinspapa84.shiftalarm"

declared_permissions = {p.get(NS + "name") for p in manifest.findall("uses-permission")}
required_permissions = [
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.WAKE_LOCK",
    "android.permission.VIBRATE",
]
for perm in required_permissions:
    check(perm in declared_permissions, f"권한 선언 {perm.split('.')[-1]}")

app_node = manifest.find("application")
check(app_node is not None, "application 노드 존재")

classes: dict[str, str] = {}
for path in kotlin_files():
    classes[f"{declared_package(path)}.{os.path.splitext(os.path.basename(path))[0]}"] = path

component_count = 0
if app_node is not None:
    for tag in ("activity", "service", "receiver"):
        for node in app_node.findall(tag):
            raw = node.get(NS + "name", "")
            full = raw if raw.startswith("com.") else package_name + raw
            component_count += 1
            check(full in classes, f"{tag} 클래스 존재: {full.split('.')[-1]}")
    check(component_count >= 6, f"컴포넌트 등록 수 ({component_count}개)")

    service_node = app_node.find("service")
    check(
        service_node is not None
        and service_node.get(NS + "foregroundServiceType") == "specialUse"
        and service_node.find("property") is not None,
        "ForegroundService specialUse 타입 + subtype property",
    )

    boot_receiver = None
    for node in app_node.findall("receiver"):
        actions = {a.get(NS + "name") for a in node.iter("action")}
        if "android.intent.action.BOOT_COMPLETED" in actions:
            boot_receiver = node
    check(boot_receiver is not None, "BOOT_COMPLETED 리시버 등록")
    if boot_receiver is not None:
        check(boot_receiver.get(NS + "exported") == "true", "BOOT_COMPLETED 리시버 exported=true")
        boot_actions = {a.get(NS + "name") for a in boot_receiver.iter("action")}
        check("android.intent.action.MY_PACKAGE_REPLACED" in boot_actions, "앱 업데이트 시 재예약 액션")
        check(
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" in boot_actions,
            "정확한 알람 권한 변경 시 재예약 액션",
        )
        check("android.intent.action.TIME_SET" in boot_actions, "시간 변경 시 재예약 액션")
        check("android.intent.action.TIMEZONE_CHANGED" in boot_actions, "시간대 변경 시 재예약 액션")

    alarm_receiver = None
    for node in app_node.findall("receiver"):
        name = node.get(NS + "name", "")
        if name.endswith("AlarmReceiver"):
            alarm_receiver = node
    check(alarm_receiver is not None, "알람 리시버 등록")

runtime_perms = set()
for path in kotlin_files():
    runtime_perms |= set(re.findall(r"Manifest\.permission\.(\w+)", read(path)))
for perm in sorted(runtime_perms):
    check(f"android.permission.{perm}" in declared_permissions, f"코드 사용 권한 선언: {perm}")

# --------------------------------------------------------------- 3) 리소스 참조
string_map: dict[str, str] = {}
for values_path in glob.glob(os.path.join(RES, "values*", "*.xml")):
    try:
        values_root = ET.parse(values_path).getroot()
    except ET.ParseError:
        continue
    for item in values_root.findall("string"):
        if item.get("name"):
            string_map[item.get("name")] = item.text or ""
strings = set(string_map)
colors = resource_names("values", "color")
styles = resource_names("values", "style")
drawables = {
    os.path.splitext(os.path.basename(p))[0]
    for p in glob.glob(os.path.join(RES, "drawable*", "*.xml"))
}
xml_res = {
    os.path.splitext(os.path.basename(p))[0]
    for p in glob.glob(os.path.join(RES, "xml", "*.xml"))
}

EXTERNAL_STYLE_PREFIXES = ("Widget.Material3.", "Widget.MaterialComponents.", "TextAppearance.",
                           "Theme.Material3.", "Theme.MaterialComponents.")


def external_style(name: str) -> bool:
    return name.startswith(EXTERNAL_STYLE_PREFIXES)


missing_refs: list[str] = []
for path in kotlin_files() + glob.glob(os.path.join(RES, "**", "*.xml"), recursive=True):
    text = read(path)
    rel = os.path.relpath(path, ROOT)
    for name in re.findall(r"R\.string\.(\w+)", text) + re.findall(r"@string/(\w+)", text):
        if name not in strings:
            missing_refs.append(f"{rel}: string/{name}")
    for name in re.findall(r"R\.drawable\.(\w+)", text) + re.findall(r"@drawable/(\w+)", text):
        if name not in drawables:
            missing_refs.append(f"{rel}: drawable/{name}")
    for name in re.findall(r"R\.color\.(\w+)", text) + re.findall(r"@color/(\w+)", text):
        if name not in colors:
            missing_refs.append(f"{rel}: color/{name}")
    for name in re.findall(r"@style/([\w.]+)", text):
        if name not in styles and not external_style(name):
            missing_refs.append(f"{rel}: style/{name}")
    for name in re.findall(r"R\.xml\.(\w+)", text):
        if name not in xml_res:
            missing_refs.append(f"{rel}: xml/{name}")

check(not missing_refs, "리소스 참조 무결성", "; ".join(missing_refs[:6]))

# --------------------------------------------------------------- 4) ViewBinding id
layout_ids: set[str] = set()
for path in glob.glob(os.path.join(RES, "layout", "*.xml")):
    layout_ids |= set(re.findall(r'@\+id/(\w+)', read(path)))

binding_refs: set[str] = set()
for path in kotlin_files():
    for name in re.findall(r"binding\.(\w+)", read(path)):
        # ActivityMainBinding / root 같은 것은 id 가 아니다
        if name == "root" or name[0].isupper():
            continue
        binding_refs.add(name)
missing_ids = sorted(binding_refs - layout_ids)
check(not missing_ids, "ViewBinding id 참조", ", ".join(missing_ids))


# --------------------------------------------------------------- 5) 문자열 포맷 인자
def split_top_level(text: str) -> list[str]:
    parts, depth, buf, in_str = [], 0, "", False
    for ch in text:
        if in_str:
            buf += ch
            if ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
            buf += ch
        elif ch in "([{":
            depth += 1
            buf += ch
        elif ch in ")]}":
            depth -= 1
            buf += ch
        elif ch == "," and depth == 0:
            parts.append(buf.strip())
            buf = ""
        else:
            buf += ch
    if buf.strip():
        parts.append(buf.strip())
    return parts


def get_string_calls(text: str) -> list[tuple[str, int]]:
    calls = []
    for m in re.finditer(r"getString\(", text):
        i = m.end()
        depth, in_str = 1, False
        while i < len(text) and depth > 0:
            ch = text[i]
            if in_str:
                if ch == "\\":
                    i += 2
                    continue
                if ch == '"':
                    in_str = False
            elif ch == '"':
                in_str = True
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            i += 1
        inner = text[m.end():i - 1]
        parts = split_top_level(inner)
        if not parts or not parts[0].startswith("R.string."):
            continue
        calls.append((parts[0].split(".")[-1], max(len(parts) - 1, 0)))
    return calls


format_problems: list[str] = []
for path in kotlin_files():
    for name, arg_count in get_string_calls(read(path)):
        template = string_map.get(name, "")
        placeholders = {int(x) for x in re.findall(r"%(\d+)\$", template)}
        expected = max(placeholders) if placeholders else 0
        if arg_count != expected:
            format_problems.append(
                f"{os.path.basename(path)}: {name} 인자 {arg_count}개 (필요 {expected})"
            )
check(not format_problems, "문자열 포맷 인자 수", "; ".join(format_problems[:6]))

# --------------------------------------------------------------- 6) 워크플로
wf_path = os.path.join(ROOT, ".github", "workflows", "build-apk.yml")
check(os.path.exists(wf_path), "GitHub Actions 워크플로 파일")
if os.path.exists(wf_path):
    wf = read(wf_path)
    check("workflow_dispatch" in wf, "수동 실행(workflow_dispatch)")
    check("testDebugUnitTest" in wf, "단위 테스트 스텝")
    check("assembleDebug" in wf, "assembleDebug 스텝")
    check("ShiftAlarm-debug.apk" in wf, "APK 이름 ShiftAlarm-debug.apk")
    check("upload-artifact@v4" in wf, "Artifact 업로드")
    check("java-version: '17'" in wf, "JDK 17")
    check("gradlew" in wf, "Gradle wrapper 사용")

# --------------------------------------------------------------- 7) wrapper
wrapper_props = os.path.join(ROOT, "gradle", "wrapper", "gradle-wrapper.properties")
check(os.path.exists(wrapper_props), "gradle-wrapper.properties 존재")
if os.path.exists(wrapper_props):
    check("gradle-8.7-bin.zip" in read(wrapper_props), "wrapper 버전 8.7")
check(os.path.exists(os.path.join(ROOT, "gradle", "wrapper", "gradle-wrapper.jar")), "gradle-wrapper.jar 존재")

# --------------------------------------------------------------- 결과 출력
passed = sum(1 for ok, _ in results if ok)
failed = [label for ok, label in results if not ok]
for ok, label in results:
    print(f"[{'PASS' if ok else 'FAIL'}] {label}")
print(f"\n총 {len(results)}개 검사 / 통과 {passed} / 실패 {len(failed)}")
sys.exit(0 if not failed else 1)
