#!/usr/bin/env python3
"""Run native reader diagnostics on an explicitly selected disposable emulator; stdlib only."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

PACKAGE = "io.github.dpan538.inkreader.preview"
RUNNER = PACKAGE + ".test/com.inkreader.nativeink.InkEvidence"
SUITES = {
    "responsive300": ("responsive300-verification", "responsive300-report.json"),
    "responsive-window": ("responsive-window-verification", "responsive-window-report.json"),
    "source": ("source-verification", "source-report.json"),
    "screen": ("screen-verification", "screen-report.json"),
    "quality": ("quality-verification", "quality-report.json"),
    "reading-revision": ("reading-revision", "reading-revision-report.json"),
    "structure": ("structure-verification", "structure-report.json"),
    "reflow": ("reflow-verification", "reflow-report.json"),
    "experience": ("experience-verification", "experience-report.json"),
    "viewport": ("viewport-verification", "viewport-report.json"),
    "comics300": ("comic-300-verification", "comic-300-report.json"),
    "documents": ("document-verification", "document-report.json"),
    "migration": ("migration-verification", "migration-report.json"),
    "anchors": ("anchor-verification", "anchor-report.json"),
    "imports": ("imports-verification", "import-report.json"),
    "library": ("library-verification", "library-report.json"),
    "backup": ("backup-experience", "backup-experience-report.json"),
    "updates": ("update-verification", "update-report.json"),
    "update-channel": ("update-verification", "channel-report.json"),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="One disposable emulator serial; never selects a device implicitly")
    parser.add_argument("--adb", default=os.environ.get("ADB", shutil.which("adb") or "adb"))
    parser.add_argument("--allow-reset-test-data", action="store_true", help="Acknowledge that native suites reset reader preferences and test library state")
    parser.add_argument("--install", action="store_true", help="Install the already-built app and test APKs")
    parser.add_argument("--suite", action="append", choices=SUITES)
    parser.add_argument("--private-corpus", type=Path, help="Optional local EPUB/PDF corpus and independent expectations.json/migration.json")
    parser.add_argument("--output", type=Path, default=Path("local/verification/reader-quality"))
    parser.add_argument("--case", type=int, choices=range(900), metavar="0..899", help="Replay a reported case in the selected suite")
    parser.add_argument("--seed", type=int, choices=range(10), help="Replay a reported fixture")
    parser.add_argument("--profile", type=int, choices=range(5), help="Replay a reported window profile")
    parser.add_argument("--font", type=int, choices=(26, 32, 40), help="Replay a reported text size")
    parser.add_argument("--columns", type=int, choices=(1, 2), help="Replay a reported column preference")
    args = parser.parse_args()
    if not args.allow_reset_test_data:
        parser.error("Use --allow-reset-test-data for the disposable test emulator")
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    def adb(*command, **kwargs):
        return subprocess.run([args.adb, "-s", args.serial, *command], check=True, text=True, capture_output=True, **kwargs).stdout

    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("This program only operates on an Android emulator")
    # Establish the documented disposable-device baseline, including after an interrupted run.
    adb("shell", "wm", "size", "3408x2272")
    adb("shell", "wm", "density", "320")
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    if args.install:
        for file in ("android/app/build/outputs/apk/debug/app-debug.apk", "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"):
            print(adb("install", "-r", str(root / file)).strip(), flush=True)
    remote_root = "/sdcard/Android/data/" + PACKAGE + "/files/"
    if args.private_corpus:
        adb("shell", "mkdir", "-p", remote_root + "import-samples")
        for file in sorted(args.private_corpus.iterdir()):
            if file.is_file() and (file.suffix.lower() in (".epub", ".pdf") or file.name in ("expectations.json", "migration.json")):
                adb("push", str(file.resolve()), remote_root + "import-samples/" + file.name)
    suites = args.suite or ["screen", "quality", "structure", "reflow", "responsive300", "responsive-window", "reading-revision", "experience", "viewport", "comics300"]
    if args.private_corpus and not args.suite:
        suites += ["documents", "anchors"]
        if (args.private_corpus / "migration.json").is_file():
            suites.append("migration")
    summary = []
    for suite in suites:
        folder, report = SUITES[suite]
        destination = output / folder
        destination.mkdir(exist_ok=True)
        local_report = destination / report
        if local_report.exists():
            local_report.replace(destination / (report + ".previous"))
        adb("shell", "rm", "-f", remote_root + folder + "/" + report)
        if suite == "updates":
            adb("shell", "appops", "set", PACKAGE, "REQUEST_INSTALL_PACKAGES", "deny")
        command = ["shell", "am", "instrument", "-w", "-e", "suite", suite]
        for name in ("seed", "profile", "font", "columns", "case"):
            if getattr(args, name) is not None:
                command += ["-e", name, str(getattr(args, name))]
        command.append(RUNNER)
        print("Running " + suite, flush=True)
        record = {"suite": suite, "passed": False, "report": str(local_report)}
        try:
            log = adb(*command, timeout=1800)
            (output / (suite + ".log")).write_text(log)
            adb("pull", remote_root + folder + "/.", str(destination))
            if not local_report.is_file():
                raise RuntimeError("No fresh report; the process may have crashed. Old reports are never accepted.")
            result = json.loads(local_report.read_text())
            record.update(passed=result.get("passed") is True, checks=len(result.get("checks", result.get("detectorChecks", []))), rounds=result.get("roundCount"), error=result.get("error"))
            if suite == "documents":
                reviews = []
                for file in destination.glob("*-audit.json"):
                    reviews.extend({"report": str(file), **finding} for finding in json.loads(file.read_text()).get("findings", []) if finding.get("severity") == "review")
                record["reviewFindings"] = reviews
            if not record["passed"]:
                context = result.get("failureContext", {})
                replay = [sys.executable, str(Path(__file__).resolve()), "--adb", args.adb, "--serial", args.serial, "--allow-reset-test-data", "--suite", suite]
                for name in ("seed", "profile", "font", "columns", "case"):
                    if suite in ("responsive300", "responsive-window") and name != "case":
                        continue
                    if name in context:
                        replay += ["--" + name, str(context[name])]
                record["replay"] = replay
                record["failureContext"] = context
        except (subprocess.SubprocessError, OSError, ValueError, RuntimeError) as error:
            record["error"] = str(error)
            diagnostic = getattr(error, "stdout", None) or getattr(error, "output", None)
            if diagnostic:
                if isinstance(diagnostic, bytes):
                    diagnostic = diagnostic.decode("utf-8", errors="replace")
                (output / (suite + ".log")).write_text(diagnostic)
        summary.append(record)
        (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
        display = {key: value for key, value in record.items() if key not in ("reviewFindings", "failureContext")}
        if "reviewFindings" in record:
            display["reviewCount"] = len(record["reviewFindings"])
        if "failureContext" in record:
            display["failureContext"] = {key: value for key, value in record["failureContext"].items() if key != "source"}
        print(json.dumps(display, ensure_ascii=False), flush=True)
        if not record["passed"]:
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
