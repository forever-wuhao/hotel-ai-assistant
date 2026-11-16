"""Reproducible fixture-based integration evaluation; not a benchmark of GLM quality."""
import argparse
import datetime as dt
import json
from pathlib import Path
import re
import statistics
import time
import urllib.request
import uuid


def request(base, path, body=None):
    raw = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(base + path, data=raw, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=150) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:9090")
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("cases.json"))
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("latest-demo.json"))
    parser.add_argument("--allow-live", action="store_true", help="Explicitly allow paid model calls against the demo data profile")
    args = parser.parse_args()
    status = request(args.base_url, "/api/ai/agent/status")
    if status["dataKind"] != "demo" or (status["mode"] != "demo" and not args.allow_live):
        raise SystemExit("Requires fictional demo data. Real model calls also require --allow-live.")
    today = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date()
    cases = json.loads(args.cases.read_text(encoding="utf-8"))
    results, latencies, total_tools, successful_tools = [], [], 0, 0
    for case in cases:
        session, errors, traces = None, [], []
        for step in case["turns"]:
            message = re.sub(r"\{\{today\+(\d+)\}\}", lambda m: str(today + dt.timedelta(days=int(m[1]))), step["message"])
            start = time.perf_counter()
            try:
                answer = request(args.base_url, "/api/ai/agent/chat", {
                    "message": message, "sessionId": session, "requestId": str(uuid.uuid4())
                })
                elapsed = round((time.perf_counter() - start) * 1000, 2)
                latencies.append(elapsed)
                session = answer["sessionId"]
                tools, citations = answer["tools"], answer["citations"]
                total_tools += len(tools)
                successful_tools += sum(t["success"] for t in tools)
                actual_names = [t["name"] for t in tools]
                actual_ids = [s["id"] for s in citations]
                if actual_names != step["tools"]:
                    errors.append(f"tools: {actual_names} != {step['tools']}")
                if "citationIds" in step and actual_ids != step["citationIds"]:
                    errors.append(f"citation ids: {actual_ids}")
                if "citationPrefix" in step and (not actual_ids or not all(i.startswith(step["citationPrefix"]) for i in actual_ids)):
                    errors.append("missing or cross-hotel citations")
                if [t["code"] for t in tools if not t["success"]] != step.get("failedCodes", []):
                    errors.append("unexpected tool outcome")
                for phrase in step.get("contains", []):
                    if phrase not in answer["answer"]:
                        errors.append(f"missing expected text: {phrase}")
                for phrase in step.get("excludes", []):
                    if phrase in answer["answer"]:
                        errors.append(f"unexpected text: {phrase}")
                if any(s["dataKind"] != "demo" or not s["source"] for s in citations):
                    errors.append("missing provenance")
                traces.append({"message": message, "answer": answer["answer"], "tools": tools, "citationIds": actual_ids, "elapsedMs": elapsed})
            except Exception as exc:
                # Avoid dumping provider secrets or response bodies to reports.
                errors.append(type(exc).__name__)
                break
        results.append({"id": case["id"], "passed": not errors, "errors": errors, "turns": traces})
    passed = sum(r["passed"] for r in results)
    report = {
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "mode": status["mode"], "dataKind": status["dataKind"],
        "scope": "Fixture-based integration checks; scripted demo is NOT a model-quality benchmark. Expected failed tools are counted separately from task checks.",
        "tasks": len(results), "passed": passed, "taskCheckPassRate": passed / len(results),
        "toolCalls": total_tools, "successfulToolCalls": successful_tools,
        "rawToolSuccessRate": successful_tools / total_tools if total_tools else None,
        "latencyMs": {"median": statistics.median(latencies) if latencies else None,
                      "p95": sorted(latencies)[min(len(latencies) - 1, int(len(latencies) * .95))] if latencies else None},
        "results": results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": passed, "tasks": len(results), "mode": status["mode"], "report": str(args.output)}, ensure_ascii=True))
    for result in results:
        if not result["passed"]:
            print(json.dumps({"id": result["id"], "errors": result["errors"]}, ensure_ascii=True))
    raise SystemExit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
