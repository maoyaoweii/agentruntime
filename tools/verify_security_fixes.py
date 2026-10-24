#!/usr/bin/env python3
"""Static acceptance checks for the minimal production safety boundary.

This intentionally does not invoke Gradle: it is safe to run while another
agent is building APKs.
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
errors = []

def require(condition, message):
    if not condition:
        errors.append(message)

manifest_path = ROOT / "agent/src/main/AndroidManifest.xml"
manifest = ET.parse(manifest_path).getroot()
perms = {node.get(ANDROID + "name") for node in manifest.findall("uses-permission")}
require("android.permission.WRITE_SETTINGS" in perms,
        "Manifest must declare android.permission.WRITE_SETTINGS")
activity = next((node for node in manifest.findall(".//activity")
                 if node.get(ANDROID + "name") == "com.agentruntime.ui.TaskExecActivity"), None)
require(activity is not None, "TaskExecActivity declaration is missing")
require(activity is not None and activity.get(ANDROID + "exported") == "${taskExecExported}",
        "TaskExecActivity must use the taskExecExported manifest placeholder")

gradle = (ROOT / "agent/build.gradle").read_text()
require(re.search(r"debug\s*\{.*?manifestPlaceholders\.taskExecExported\s*=\s*['\"]true['\"]", gradle, re.S) is not None,
        "debug must set taskExecExported=true for explicit adb/instrumentation calls")
require(re.search(r"release\s*\{.*?manifestPlaceholders\.taskExecExported\s*=\s*['\"]false['\"]", gradle, re.S) is not None,
        "release must set taskExecExported=false")

task = (ROOT / "agent/src/main/java/com/agentruntime/ui/TaskExecActivity.java").read_text()
require('getBooleanExtra("auto_confirm", false)' in task,
        "TaskExecActivity auto_confirm default must be false")
main = (ROOT / "agent/src/main/java/com/agentruntime/ui/MainActivity.java").read_text()
require('getBoolean("auto_confirm", false)' in main,
        "MainActivity restored auto_confirm default must be false")
layout = (ROOT / "agent/src/main/res/layout/activity_main.xml").read_text()
checkbox = re.search(r'<CheckBox\b(?P<body>.*?)id="@\+id/cb_auto_confirm"(?P<tail>.*?)/>', layout, re.S)
require(checkbox is not None and 'android:checked="true"' not in checkbox.group(0),
        "MainActivity auto-confirm checkbox must be unchecked by default")
overlay = (ROOT / "agent/src/main/java/com/agentruntime/overlay/OverlayService.java").read_text()
require("TaskRunner.runGoal(goal, false, true)" in overlay,
        "OverlayService must not silently enable test auto-confirm")

if errors:
    for error in errors:
        print("FAIL:", error)
    sys.exit(1)
print("PASS: security static checks (Manifest permission/variants and auto-confirm defaults)")
