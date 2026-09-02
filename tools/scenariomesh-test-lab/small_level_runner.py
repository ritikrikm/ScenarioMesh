#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

TARGET_URL = os.environ.get("TARGET_URL", "https://github.com/cucumber/cucumber-jvm-starter-maven-java.git")
TARGET_BRANCH = os.environ.get("TARGET_BRANCH", "main")
SM_ROOT = Path(os.environ.get("SCENARIOMESH_ROOT", Path.cwd())).resolve()
OUT = Path(os.environ.get("LAB_OUT", "/tmp/scenariomesh-small-lab")).resolve()
WORKERS = int(os.environ.get("SCENARIOMESH_WORKERS", "4"))

ANSI_RE = re.compile(r"\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])")
RESULT_RE = re.compile(
    r"ScenarioMesh results: discovered=(\d+), passed=(\d+), skipped=(\d+), failed=(\d+), logical=(\d+), flakes=(\d+)"
)


def clean(text: str) -> str:
    return ANSI_RE.sub("", text)


def run(cmd: list[str], cwd: Path, log_path: Path) -> int:
    print("$", " ".join(cmd), flush=True)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            cmd,
            cwd=str(cwd),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        assert process.stdout is not None
        for line in process.stdout:
            sys.stdout.write(line)
            log.write(line)
            log.flush()
        return process.wait()


def clone(dest: Path) -> None:
    if dest.exists():
        shutil.rmtree(dest)
    subprocess.run(
        ["git", "clone", "--depth", "1", "--branch", TARGET_BRANCH, TARGET_URL, str(dest)],
        check=True,
    )
    if not (dest / "pom.xml").is_file():
        raise RuntimeError(f"Target has no root pom.xml: {dest}")


def count_surefire_testcases(root: Path) -> int:
    files = sorted((root / "target" / "surefire-reports").glob("TEST-*.xml"))
    if not files:
        return 0
    total = 0
    for file in files:
        tree = ET.parse(file)
        total += len(tree.findall(".//testcase"))
    return total


def find_cli_jar(root: Path) -> Path:
    jars = sorted(
        root.glob("scenariomesh-cli/target/scenariomesh-cli-*.jar"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    jars = [
        p for p in jars
        if "-sources" not in p.name and "-javadoc" not in p.name and not p.name.startswith("original-")
    ]
    if not jars:
        raise RuntimeError("ScenarioMesh CLI jar not found after build")
    return jars[0]


def find_summary(target: Path) -> Path | None:
    preferred = target / "target" / "scenariomesh" / "summary.json"
    if preferred.is_file():
        return preferred
    matches = sorted((target / "target").glob("scenariomesh*/summary.json"))
    return matches[0] if matches else None


def main() -> int:
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)

    print("=== Small-level ScenarioMesh real-repository lab ===")
    print(f"Target: {TARGET_URL} @ {TARGET_BRANCH}")
    print(f"ScenarioMesh: {SM_ROOT}")

    native = OUT / "native"
    clone(native)
    native_log = OUT / "native-maven.log"
    native_rc = run(["mvn", "-B", "-Dstyle.color=never", "test"], native, native_log)
    native_count = count_surefire_testcases(native)
    print(f"NATIVE_RESULT exit={native_rc} testcases={native_count}")
    if native_rc != 0:
        raise RuntimeError("Native Maven baseline failed; ScenarioMesh comparison is invalid")
    if native_count <= 0:
        raise RuntimeError("Native Maven baseline produced zero Surefire testcases")

    build_log = OUT / "scenariomesh-build.log"
    build_rc = run(["mvn", "-B", "-DskipTests", "clean", "install"], SM_ROOT, build_log)
    if build_rc != 0:
        raise RuntimeError("ScenarioMesh build/install failed")

    target = OUT / "scenariomesh"
    clone(target)
    cli = find_cli_jar(SM_ROOT)
    init_log = OUT / "scenariomesh-init.log"
    init_rc = run(["java", "-jar", str(cli), "init", "--project", str(target)], target, init_log)
    if init_rc != 0:
        raise RuntimeError("ScenarioMesh CLI init failed")

    scenario_log = OUT / "scenariomesh-maven.log"
    scenario_cmd = [
        "mvn", "-B", "-Dstyle.color=never", "test",
        "-Dscenariomesh.enabled=true",
        f"-Dscenariomesh.workers.count={WORKERS}",
        "-Dscenariomesh.execution.adapter=auto",
        "-Dscenariomesh.logging.showConfiguration=true",
        "-Dscenariomesh.logging.showProgress=true",
        "-Dscenariomesh.logging.liveConsole=true",
        "-Dscenariomesh.logging.workerFiles=true",
    ]
    scenario_rc = run(scenario_cmd, target, scenario_log)
    log = clean(scenario_log.read_text(encoding="utf-8", errors="replace"))

    result = {
        "target": TARGET_URL,
        "branch": TARGET_BRANCH,
        "native_exit": native_rc,
        "native_testcases": native_count,
        "scenariomesh_exit": scenario_rc,
        "takeover": "ScenarioMesh: takeover enabled" in log,
        "pass_through": "pass-through" in log.lower(),
        "junit_platform_seen": "junit-platform" in log.lower(),
    }

    match = RESULT_RE.search(log)
    if match:
        discovered, passed, skipped, failed, logical, flakes = map(int, match.groups())
        result.update({
            "discovered": discovered,
            "passed": passed,
            "skipped": skipped,
            "failed": failed,
            "logical": logical,
            "flakes": flakes,
        })

    summary_path = find_summary(target)
    if summary_path:
        result["summary_path"] = str(summary_path)
        try:
            result["summary"] = json.loads(summary_path.read_text(encoding="utf-8"))
        except Exception as exc:
            result["summary_parse_error"] = str(exc)

    result_path = OUT / "result.json"
    result_path.write_text(json.dumps(result, indent=2, default=str), encoding="utf-8")
    print("\n=== LAB RESULT ===")
    print(json.dumps(result, indent=2, default=str))

    errors: list[str] = []
    if scenario_rc != 0:
        errors.append(f"ScenarioMesh Maven exit was {scenario_rc}")
    if not result["takeover"]:
        errors.append("ScenarioMesh did not prove takeover ownership")
    if result["pass_through"]:
        errors.append("ScenarioMesh selected/passaged through to native Maven instead of owning this supported small target")
    if not result["junit_platform_seen"]:
        errors.append("JUnit Platform adapter was not observed")
    if not match:
        errors.append("ScenarioMesh result summary line was not found")
    else:
        if result["failed"] != 0:
            errors.append(f"ScenarioMesh reported {result['failed']} failed logical tests")
        if result["skipped"] != 0:
            errors.append(f"ScenarioMesh reported {result['skipped']} skipped logical tests")
        if result["discovered"] != native_count:
            errors.append(f"Discovery mismatch: native={native_count}, ScenarioMesh={result['discovered']}")
        if result["logical"] != native_count:
            errors.append(f"Logical execution mismatch/duplication: native={native_count}, ScenarioMesh={result['logical']}")
        if result["passed"] != native_count:
            errors.append(f"Pass-count mismatch: native={native_count}, ScenarioMesh={result['passed']}")

    if not summary_path:
        errors.append("ScenarioMesh summary.json was not generated")

    if errors:
        print("\nSMALL_LEVEL_GATE=FAIL")
        for error in errors:
            print(" -", error)
        return 1

    print("\nSMALL_LEVEL_GATE=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
