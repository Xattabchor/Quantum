#!/usr/bin/env python3
"""Run real Quantum CPU methods in klaxons1/j2me_cldc's Linux KVM, not an FPS test.
Requires its unpacked distribution, Java/ECJ, and the existing SDK API build.
A 32-bit loader can be supplied locally; nothing is installed or downloaded.
Outputs raw logs and machine-readable measurements under ignored build/.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import statistics
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TASKS = (
    "transform_project_1000", "matrix_mul_1000", "pack_triangles_1000",
    "base_shade_1000", "omni_shade_1000", "post_240x320", "post_176x220",
)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cldc-root", required=True, type=Path)
    parser.add_argument("--loader", type=Path)
    parser.add_argument("--library-path", type=Path)
    parser.add_argument("--java", default=os.environ.get("JAVA", "java"))
    parser.add_argument("--ecj-jar", default=os.environ.get("ECJ_JAR"), required=not os.environ.get("ECJ_JAR"), type=Path)
    parser.add_argument("--api-jar", default=ROOT / "build/cldc/api.jar", type=Path)
    parser.add_argument("--app-jar", default=ROOT / "downloads/Quantum.jar", type=Path)
    parser.add_argument("--output", default=ROOT / "build/cldc-perf/results", type=Path)
    args = parser.parse_args()
    cldc, output = args.cldc_root.resolve(), args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    app, api, ecj = args.app_jar.resolve(), args.api_jar.resolve(), args.ecj_jar.resolve()
    kvm, preverify = cldc / "bin/linux/kvm", cldc / "bin/linux/preverify"
    common, midp = cldc / "bin/common/api/classes", cldc / "midp2.0fcs/classes"
    prefix = []
    if args.loader:
        prefix = [str(args.loader.resolve())]
        if args.library_path:
            prefix += ["--library-path", str(args.library_path.resolve())]
    elif args.library_path:
        parser.error("--library-path requires --loader")
    # Fresh compiler output: stale classes must never enter the measurement.
    import tempfile
    with tempfile.TemporaryDirectory(prefix="classes-", dir=output) as temp:
        classes = Path(temp) / "compiled"
        classes.mkdir()
        compile_cmd = [args.java, "-cp", str(ecj), "org.eclipse.jdt.internal.compiler.batch.Main",
                       "-source", "1.3", "-target", "1.3", "-encoding", "UTF-8", "-proc:none",
                       "-bootclasspath", str(api), "-classpath", str(app), "-d", str(classes),
                       str(Path(__file__).with_name("KvmPerformance.java"))]
        compiled = subprocess.run(compile_cmd, capture_output=True, text=True, check=True)
        (output / "compile.log").write_text(compiled.stdout + compiled.stderr)
        verified = Path(temp) / "verified"
        subprocess.run(prefix + [str(preverify), "-classpath", os.pathsep.join(map(str, [common, app])),
                                "-d", str(verified), str(classes)], check=True, capture_output=True, text=True)
        base = prefix + [str(kvm), "-heapsize", "4M", "-classpath",
                         os.pathsep.join(map(str, [verified, common, midp, app])), "code.Rendering.KvmPerformance"]
        raw = []

        def run(arguments):
            result = subprocess.run(base + arguments, capture_output=True, text=True, timeout=60)
            text = result.stdout + result.stderr
            raw.append({"arguments": arguments, "exit_code": result.returncode, "output": text})
            (output / "raw.json").write_text(json.dumps(raw, indent=2) + "\n")
            if result.returncode:
                raise RuntimeError("KVM failed: " + text)
            return text

        assert "PASS KVM" in run(["health"])
        probes = {}
        for name in ("javax.microedition.m3g.Graphics3D", "javax.microedition.lcdui.Display", "code.utils.Main"):
            probes[name] = run(["probe", name]).strip()
            print(probes[name], flush=True)
        tasks = {}
        for task in TASKS:
            text = run([task])
            samples = re.findall(r"^SAMPLE (\d+) (\d+) (-?\d+)$", text, re.MULTILINE)
            if len(samples) != 5:
                raise RuntimeError("Expected five samples: " + text)
            measurements = [int(ms) / int(repeats) for ms, repeats, _ in samples]
            tasks[task] = {
                "median_ms_per_workload": statistics.median(measurements),
                "min_ms_per_workload": min(measurements), "max_ms_per_workload": max(measurements),
                "samples_ms_per_workload": measurements,
                "batch_repeats": int(samples[0][1]),
                "checksums": [int(checksum) for _, _, checksum in samples],
            }
            print(task, f"{statistics.median(measurements):.3f} ms/workload", flush=True)
        with zipfile.ZipFile(app) as jar:
            resource_files = sum(not name.endswith((".class", "/")) and not name.startswith("META-INF/")
                                 for name in jar.namelist())
        cpu = next((line.split(":", 1)[1].strip() for line in Path("/proc/cpuinfo").read_text().splitlines()
                    if line.startswith("model name")), "unknown") if Path("/proc/cpuinfo").exists() else platform.processor()
        result = {
            "kind": "CPU microbenchmark; NOT game FPS or M3G/GPU timing",
            "quantum_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
            "app_jar_sha256": digest(app), "app_jar_bytes": app.stat().st_size,
            "game_resource_files": resource_files, "kvm_sha256": digest(kvm),
            "preverify_sha256": digest(preverify),
            "benchmark_source_sha256": digest(Path(__file__).with_name("KvmPerformance.java")),
            "ecj_sha256": digest(ecj), "api_jar_sha256": digest(api),
            "cpu": cpu, "host": platform.platform(),
            "heap_bytes": 4 * 1024 * 1024, "samples": 5, "calibration_min_ms": 250,
            "probes": probes, "tasks": tasks,
        }
        if args.loader:
            result["loader_sha256"] = digest(args.loader.resolve())
        (output / "results.json").write_text(json.dumps(result, indent=2) + "\n")
        print("Results:", output / "results.json")


if __name__ == "__main__":
    main()
