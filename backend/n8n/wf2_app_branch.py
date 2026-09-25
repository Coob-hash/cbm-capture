"""Adds the CBM App branch to WF2, next to the Google Drive branch.

    python wf2_app_branch.py [--upgrade] <wf2-export.json> <output.json> <report-pdf.js>

Input is an export of the running WF2 (n8n export:workflow) and the workflows' own PDF renderer,
`cbm/templates/technician-report/report-pdf.js` from the workflow release. The renderer is embedded
in the new Code node, so a report written in the app and one written in the browser are the same
document; re-running this script re-embeds whatever the release now holds.

  Completed Upload (Drive Trigger) ───────────────────────────────┐
  App Report Submitted (LISTEN cbm_app_report) ─► Read App Report ─┤
  App Report Sweep Tick ─► Sweep App Reports ─────────────────────┘
                                                                   ▼
                                                         Extract Ticket ID (shared)
                                                           ─► Fetch Ticket ─► Ticket Open and Assigned?
                                                           ─► App Report?
      ├ yes ─► Render Report PDF ─► Record App Submission ─► App Report Claimed? ─┐
      └ no  ─► Download Report PDF ───────────────────────────────────────────────┴► Extract Report Text and Photo
                                                           … ─► Set Pending Approval ─► Approval Cycle

The report is claimed as soon as its document exists, while the ticket is still the technician's to
report (ASSIGNED, REWORK): the workflows' claim accepts a report only then, for the cycle the ticket
is in. 'Set Pending Approval' moves the ticket on and opens the next cycle, and 'Approval Cycle'
reads the ticket id and approval_id from the row it returns, so nothing is inserted between the two.
(The branch of 21 Sep claimed after 'Set Pending Approval': the claim found the job closed, and the
claim node's output had no ticket for 'Approval Cycle', so the review loop never started. Third
audit, 25 Sep, finding 1.) A report the workflows do not take stops at 'App Report Claimed?'; the
reason is on the app's row, and the app offers the job to be reported again.

The Drive branch is unchanged: a Drive file still parses its ticket number out of the file name,
still downloads from Drive, and never meets the claim nodes (the portal claims its own report).
Deleting the Drive branch later means removing its trigger, 'Download Report PDF' and the Drive
half of 'Extract Ticket ID'.

Changed nodes: 'Extract Ticket ID' only. Everything after the extraction — the assessment, the FM
review loop, the IFC write, the closure and the notices — is untouched.

--upgrade takes an export of a WF2 that has the branch of 21 Sep, removes it - its eight nodes, the
two connections it rerouted and its lines in 'Extract Ticket ID' - and adds the branch as it is now.
Whatever else was changed in the workflow since is kept.
"""

import json
import sys
import uuid

PG_CREDENTIAL = {"postgres": {"id": "cbmLocalPg20260917", "name": "CBM Postgres - Local Demo"}}
INTERNAL_PHOTO_URL = "http://cbm-app-internal:8081/internal/reports/"
NEW_NODES = ("App Report Submitted", "Read App Report", "App Report Sweep Tick", "Sweep App Reports",
             "App Report?", "Render Report PDF", "Record App Submission", "App Report Claimed?")

# Only a report the workflows took for this cycle is assessed. The claim's result carries no
# document, so the node hands the extraction the PDF the renderer made, as the Drive download does.
CLAIMED_CODE = """// 'Record App Submission' has just claimed the report for this approval cycle, while the ticket is
// still the technician's to report - the only time the workflows' claim accepts one. Only a claimed
// report is assessed. Anything else (the cycle has a report already, the job moved on) has been
// written back to the app's row as NOT_PROCESSED with its reason, and this run stops here.
const result = $input.first().json.result || {};
if (result.proceed !== true) return [];
// The claim's result carries no document: hand the extraction the PDF the renderer made.
const rendered = $('Render Report PDF').first();
return [{json: rendered.json, binary: rendered.binary}];
"""

# The one line of the Drive branch that is replaced, and what replaces it.
EXTRACT_TICKET_LINE = "const f=$input.first().json,name=String(f.name||'');"
EXTRACT_TICKET_APP = r"""const f=$input.first().json;
// App branch: a report written in the CBM App (cbm_app.reports_for_wf2). It carries its own ticket,
// so there is no file name to read it out of, and the fields travel with it for the renderer.
const app=f.report&&f.report.source==='APP'?f.report:null;
if(app){
 if(!Number.isInteger(app.ticket_id)||app.ticket_id<1)throw new Error('App report without a ticket');
 if(!app.report_id)throw new Error('App report without an id');
 return [{json:{matched:true,ticket_id:app.ticket_id,upload_kind:'REPORT',source:'APP',
  app_report:app,report_file_id:null,report_file_name:'TICKET-'+app.ticket_id+'.pdf',report_link:''}}];
}
// Drive branch, unchanged from here down.
const name=String(f.name||'');"""


def render_code(renderer_source: str) -> str:
    """The Code node: the template's renderer, the AFTER photo, and the PDF it produces."""
    return (
        "// The technician wrote this report in the app, so the PDF does not exist yet. It is made\n"
        "// here with the template's own renderer — the same file the browser form loads — so both\n"
        "// routes produce the same document for the facility manager and for the archive.\n"
        "globalThis.PDFLib = require('pdf-lib');\n"
        "const crypto = require('crypto');\n"
        "\n"
        "// ---- cbm/templates/technician-report/report-pdf.js (embedded verbatim) ----\n"
        f"{renderer_source.rstrip()}\n"
        "// ---- end of the renderer ----\n"
        "\n"
        "const app = $('Extract Ticket ID').first().json.app_report;\n"
        "if (!app) throw new Error('Not a report from the app');\n"
        "const fields = Object.assign({}, app.report, {photo_caption: app.photo ? app.photo.caption : ''});\n"
        "let photoDataUrl = null;\n"
        "if (app.photo) {\n"
        "  const bytes = await this.helpers.httpRequest({\n"
        f"    method: 'GET', url: '{INTERNAL_PHOTO_URL}' + app.report_id + '/photo',\n"
        "    encoding: 'arraybuffer', timeout: 30000,\n"
        "  });\n"
        "  photoDataUrl = 'data:image/jpeg;base64,' + Buffer.from(bytes).toString('base64');\n"
        "}\n"
        "const pdf = Buffer.from(await globalThis.CBMReport.createReport(fields, photoDataUrl, false));\n"
        "if (pdf.subarray(0, 5).toString() !== '%PDF-') throw new Error('The renderer did not produce a PDF');\n"
        "const sha256 = crypto.createHash('sha256').update(pdf).digest('hex');\n"
        "return [{\n"
        "  json: {app_report_id: app.report_id, ticket_id: app.ticket_id, pdf_sha256: sha256, pdf_bytes: pdf.length},\n"
        "  binary: {data: await this.helpers.prepareBinaryData(pdf, 'TICKET-' + app.ticket_id + '.pdf', 'application/pdf')},\n"
        "}];\n"
    )


def fail(message: str) -> None:
    raise SystemExit(f"WF2 is not what this patch expects: {message}")


# The branch as first applied, on 21 Sep: claimed after 'Set Pending Approval'.
BRANCH_OF_21_SEP = ("App Report Submitted", "Read App Report", "App Report Sweep Tick", "Sweep App Reports",
                    "App Report?", "Render Report PDF", "Claim App Report?", "Record App Submission")


def remove_branch_of_21_sep(wf: dict) -> dict:
    """The WF2 the branch of 21 Sep was applied to, with any change made to it since."""
    conns = wf.setdefault("connections", {})
    names = {n["name"] for n in wf["nodes"]}
    if "Claim App Report?" not in names or not set(BRANCH_OF_21_SEP) <= names:
        fail("--upgrade needs the branch of 21 Sep ('Claim App Report?' and its seven companions)")

    def main_targets(name):
        return [t["node"] for t in (conns.get(name, {}).get("main") or [[]])[0]]

    if main_targets("Ticket Open and Assigned?") != ["App Report?"] or main_targets("Set Pending Approval") != ["Claim App Report?"]:
        fail("the branch of 21 Sep is not wired as it was applied; review before upgrading")
    eti = next(n for n in wf["nodes"] if n["name"] == "Extract Ticket ID")["parameters"]
    if eti.get("jsCode", "").count(EXTRACT_TICKET_APP) != 1:
        fail("Extract Ticket ID does not hold the branch's code; review before upgrading")
    eti["jsCode"] = eti["jsCode"].replace(EXTRACT_TICKET_APP, EXTRACT_TICKET_LINE, 1)
    wf["nodes"] = [n for n in wf["nodes"] if n["name"] not in BRANCH_OF_21_SEP]
    for name in BRANCH_OF_21_SEP:
        conns.pop(name, None)
    conns["Ticket Open and Assigned?"]["main"][0] = [{"node": "Download Report PDF", "type": "main", "index": 0}]
    conns["Set Pending Approval"]["main"][0] = [{"node": "Approval Cycle", "type": "main", "index": 0}]
    left = [f"{src}->{t['node']}" for src, c in conns.items() for branch in (c.get("main") or [])
            for t in (branch or []) if t["node"] in BRANCH_OF_21_SEP]
    if left:
        fail(f"other nodes lead into the branch of 21 Sep: {left}")
    return wf


def patch(wf: dict, renderer_source: str) -> dict:
    nodes = {n["name"]: n for n in wf["nodes"]}
    conns = wf.setdefault("connections", {})

    for name in NEW_NODES:
        if name in nodes:
            fail(f"'{name}' already exists; the branch has been added before")
    for name in ("Completed Upload (Drive Trigger)", "Extract Ticket ID", "Fetch Ticket",
                 "Ticket Open and Assigned?", "Download Report PDF", "Extract Report Text and Photo",
                 "Set Pending Approval", "Approval Cycle"):
        if name not in nodes:
            fail(f"'{name}' is missing")

    def main_targets(name, output=0):
        branches = conns.get(name, {}).get("main", [])
        return [t["node"] for t in (branches[output] if len(branches) > output else [])]

    if main_targets("Ticket Open and Assigned?") != ["Download Report PDF"]:
        fail("Ticket Open and Assigned? no longer feeds Download Report PDF")
    if main_targets("Download Report PDF") != ["Extract Report Text and Photo"]:
        fail("Download Report PDF no longer feeds the extraction")
    if main_targets("Set Pending Approval") != ["Approval Cycle"]:
        fail("Set Pending Approval no longer feeds Approval Cycle")
    if "root.CBMReport" not in renderer_source or "createReport" not in renderer_source:
        fail("that file is not the report renderer")

    def pos(name, dx, dy):
        x, y = nodes[name]["position"]
        return [x + dx, y + dy]

    def node(name, type_, version, position, parameters, **extra):
        n = {"parameters": parameters, "id": str(uuid.uuid4()), "name": name, "type": type_,
             "typeVersion": version, "position": position, **extra}
        wf["nodes"].append(n)
        nodes[name] = n
        return n

    def link(src, dst, output=0):
        outs = conns.setdefault(src, {}).setdefault("main", [])
        while len(outs) <= output:
            outs.append([])
        outs[output].append({"node": dst, "type": "main", "index": 0})

    def when_app():
        """True when this run came from the app, read from the node both branches pass through."""
        return {"conditions": {"options": {"caseSensitive": True, "leftValue": "",
                                           "typeValidation": "strict", "version": 1},
                               "combinator": "and",
                               "conditions": [{"id": str(uuid.uuid4()),
                                               "leftValue": "={{ $('Extract Ticket ID').first().json.source === 'APP' }}",
                                               "rightValue": True,
                                               "operator": {"type": "boolean", "operation": "true",
                                                            "singleValue": True}}]},
                "options": {}}

    # 1. How WF2 hears about a report written in the app: a notification, and a sweep behind it.
    node("App Report Submitted", "n8n-nodes-base.postgresTrigger", 1,
         pos("Completed Upload (Drive Trigger)", 0, 260),
         {"triggerMode": "listenTrigger", "channelName": "cbm_app_report", "options": {}},
         credentials=PG_CREDENTIAL,
         notes="cbm_app.store_technician_report() sends NOTIFY cbm_app_report with the report id once the app has stored it.")
    node("Read App Report", "n8n-nodes-base.postgres", 2.5,
         pos("Completed Upload (Drive Trigger)", 220, 260),
         {"operation": "executeQuery",
          "query": "SELECT x AS report FROM cbm_app.reports_for_wf2($1::uuid) x;",
          "options": {"queryBatching": "independently",
                      "queryReplacement": "={{ [String($json.payload || '')] }}"}},
         credentials=PG_CREDENTIAL,
         notes="Returns the report only while it still waits for WF2, so a late or repeated notification does nothing.")
    node("App Report Sweep Tick", "n8n-nodes-base.scheduleTrigger", 1.2,
         pos("Completed Upload (Drive Trigger)", 0, 440),
         {"rule": {"interval": [{"field": "minutes", "minutesInterval": 1}]}},
         notes="The safety net behind the notification: WF2 has no other tick of its own.")
    node("Sweep App Reports", "n8n-nodes-base.postgres", 2.5,
         pos("Completed Upload (Drive Trigger)", 220, 440),
         {"operation": "executeQuery",
          "query": "SELECT x AS report FROM cbm_app.reports_for_wf2() x;",
          "options": {}},
         credentials=PG_CREDENTIAL,
         notes="Reports the notification missed (stored over two minutes ago) and ones WF2 left unfinished, retried after ten.")
    link("App Report Submitted", "Read App Report")
    link("App Report Sweep Tick", "Sweep App Reports")
    link("Read App Report", "Extract Ticket ID")
    link("Sweep App Reports", "Extract Ticket ID")

    # 2. Extract Ticket ID: the shared node, because two nodes after it read it by name.
    eti = nodes["Extract Ticket ID"]["parameters"]
    if eti.get("jsCode", "").count(EXTRACT_TICKET_LINE) != 1:
        fail("Extract Ticket ID code changed; review before patching")
    eti["jsCode"] = eti["jsCode"].replace(EXTRACT_TICKET_LINE, EXTRACT_TICKET_APP, 1)

    # 3. Where the PDF comes from: Drive, or the renderer.
    node("App Report?", "n8n-nodes-base.if", 2.2, pos("Download Report PDF", 0, -200), when_app(),
         notes="A report written in the app has no Drive file: it is rendered instead.")
    conns["Ticket Open and Assigned?"]["main"][0] = []
    link("Ticket Open and Assigned?", "App Report?", 0)
    node("Render Report PDF", "n8n-nodes-base.code", 2, pos("Download Report PDF", 220, -200),
         {"jsCode": render_code(renderer_source)},
         notes="The template's own renderer, with the AFTER photo fetched from the App API's internal service.")
    link("App Report?", "Render Report PDF", 0)
    link("App Report?", "Download Report PDF", 1)

    # 4. Claim the report through the workflows' own submission function, and record the outcome -
    #    now, while the ticket is still the technician's to report; then hand on the document.
    node("Record App Submission", "n8n-nodes-base.postgres", 2.5, pos("Download Report PDF", 440, -200),
         {"operation": "executeQuery",
          "query": "SELECT cbm_app.record_app_report_submission($1::jsonb) AS result;",
          "options": {"queryBatching": "independently",
                      "queryReplacement": "={{ [JSON.stringify({report_id: $json.app_report_id, pdf_sha256: $json.pdf_sha256})] }}"}},
         credentials=PG_CREDENTIAL,
         notes="Claims the report for this approval cycle through public.cbm_claim_technician_report() and records the outcome on the app's row. Before 'Set Pending Approval', which opens the next cycle.")
    node("App Report Claimed?", "n8n-nodes-base.code", 2, pos("Download Report PDF", 660, -200),
         {"jsCode": CLAIMED_CODE},
         notes="Only a claimed report is assessed; the rendered PDF goes on to the extraction.")
    link("Render Report PDF", "Record App Submission")
    link("Record App Submission", "App Report Claimed?")
    link("App Report Claimed?", "Extract Report Text and Photo")
    return wf


def main():
    args = sys.argv[1:]
    upgrade = args[:1] == ["--upgrade"]
    args = args[1:] if upgrade else args
    if len(args) != 3:
        raise SystemExit(__doc__)
    src, dst, renderer = args
    data = json.load(open(src, encoding="utf-8"))
    wf = data[0] if isinstance(data, list) else data
    before = len(wf["nodes"])
    if upgrade:
        remove_branch_of_21_sep(wf)
    out = patch(wf, open(renderer, encoding="utf-8").read())
    json.dump([out] if isinstance(data, list) else out, open(dst, "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print(f"{dst}: {before} -> {len(out['nodes'])} nodes")


if __name__ == "__main__":
    main()
