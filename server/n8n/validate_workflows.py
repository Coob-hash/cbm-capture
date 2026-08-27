"""
Structural checks for the n8n workflow JSON.

n8n will happily import a workflow whose connections point at nodes that do not exist; the
error surfaces later as a branch that silently does nothing. These checks are cheap and catch
that class of mistake before the file reaches a running instance.

    python validate_workflows.py WF1_Capture_Intake.json WF2_EOD_AI_Agent_and_FM_Approval.json
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

TRIGGER_HINTS = ("webhook", "Trigger", "scheduleTrigger", "cron")


def validate(path: Path) -> list[str]:
    wf = json.loads(path.read_text(encoding="utf-8"))
    problems: list[str] = []

    names = [n["name"] for n in wf["nodes"]]
    by_name = {n["name"]: n for n in wf["nodes"]}

    duplicates = {n for n in names if names.count(n) > 1}
    if duplicates:
        problems.append(f"duplicate node names: {sorted(duplicates)}")

    ids = [n["id"] for n in wf["nodes"]]
    dup_ids = {i for i in ids if ids.count(i) > 1}
    if dup_ids:
        problems.append(f"duplicate node ids: {sorted(dup_ids)}")

    referenced: set[str] = set()
    sources: set[str] = set()
    for source, spec in wf["connections"].items():
        if source not in by_name:
            problems.append(f"connection from unknown node {source!r}")
        sources.add(source)
        # Every connection type, not just "main": LangChain sub-nodes (a chat model, a tool, an
        # output parser) attach to their agent through ai_languageModel / ai_tool /
        # ai_outputParser, and appear as connection *sources* rather than targets.
        for outputs in spec.values():
            for output in outputs:
                for link in output:
                    target = link["node"]
                    referenced.add(target)
                    if target not in by_name:
                        problems.append(f"{source!r} connects to unknown node {target!r}")

    # A node should either receive something or feed something; one that does neither is dead.
    for node in wf["nodes"]:
        is_trigger = any(h in node["type"] or h in node["name"] for h in TRIGGER_HINTS)
        if not is_trigger and node["name"] not in referenced and node["name"] not in sources:
            problems.append(f"node {node['name']!r} is not connected to anything")

    # Expressions that call $('Node Name') must name a node that exists, or the expression
    # throws at run time on a branch that may be rarely exercised.
    blob = json.dumps(wf)
    import re
    for referenced_name in set(re.findall(r"\$\('([^']+)'\)", blob)):
        if referenced_name not in by_name:
            problems.append(f"expression references unknown node $('{referenced_name}')")

    # No fabricated camera intrinsics may reach the ray math. The whole point of the change is
    # that K comes from the device, so a reintroduced $env.CAMERA_* fallback is a regression.
    #
    # This deliberately ignores comment lines: several of them *mention* the fallback in order
    # to explain why it is gone, and a plain grep cannot tell prose from code.
    for node in wf["nodes"]:
        js = node["parameters"].get("jsCode")
        if isinstance(js, str):
            for lineno, line in enumerate(js.split("\n"), 1):
                stripped = line.strip()
                if stripped.startswith("//") or stripped.startswith("*"):
                    continue
                if "$env.CAMERA_" in line:
                    problems.append(
                        f"{node['name']!r} line {lineno}: $env.CAMERA_* fallback reintroduced")
        # Expressions outside Code nodes carry no comments, so any occurrence is real.
        for key, value in node["parameters"].items():
            if key != "jsCode" and "$env.CAMERA_" in json.dumps(value):
                problems.append(f"{node['name']!r} parameter {key!r}: $env.CAMERA_* fallback reintroduced")

    # A responseMode:responseNode webhook needs a Respond node downstream or the caller hangs
    # until the workflow times out.
    respond_nodes = [n for n in wf["nodes"] if n["type"].endswith("respondToWebhook")]
    webhooks = [n for n in wf["nodes"]
                if n["type"].endswith("webhook")
                and n["parameters"].get("responseMode") == "responseNode"]
    if webhooks and not respond_nodes:
        problems.append("webhook uses responseMode=responseNode but no Respond node exists")

    return problems


def main() -> int:
    failed = False
    for arg in sys.argv[1:]:
        path = Path(arg)
        problems = validate(path)
        wf = json.loads(path.read_text(encoding="utf-8"))
        if problems:
            failed = True
            print(f"FAIL {path.name}")
            for p in problems:
                print(f"     - {p}")
        else:
            print(f"OK   {path.name}  ({len(wf['nodes'])} nodes, "
                  f"{sum(len(o) for s in wf['connections'].values() for o in s.get('main', []))} links)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
