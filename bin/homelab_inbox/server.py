"""The HTTP server: access check, request handler, serve()."""
import hmac
import json
import re
import threading
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .briefs import brief_by_id, set_brief_status, start_build
from .config import ACTIVE, AUTO, BRIEF, CONF, HOME, JOBS, SECRET_RE, _cancel_runs, _lock
from .github import poll_github
from .helpers import label, log_line, page_url, read_log, repo_list, task_cmd
from .jobs import dispatch, draft_name, get_draft, job_scheduler, parse_job, save_job, set_paused, start_draft, update_draft, update_run
from .memory import LEARN_HINT, MEM_ID_RE, entry_text, load_memory, mem_get, save_memory
from .overview import activity_sentence, live_items
from .pages import Access, WEB, admin_ask, brief_page, briefs_page, csrf_token, draft_page, entry_page, job_page, jobs_page, memory_page, overview_page, page, run_page, skills_page, ssh_page, task_page, tasks_page
from .queue import cancel_task, recover, scheduler
from .router import children
from .storage import db, get, log, now, update


class Handler(BaseHTTPRequestHandler):
    server_version = "homelab-inbox/1"

    def log_message(self, fmt, *args):
        pass

    def send(self, code, body, ctype="text/html; charset=utf-8", location=None, cache=None):
        data = body if isinstance(body, bytes) else body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", cache or "no-store")
        # Alleen eigen bestanden; inline style alleen voor posities in de tijdlijn (style-attributen).
        self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'self' 'unsafe-inline'; "
                         "script-src 'self'; font-src 'self'; connect-src 'self'; img-src 'self'; form-action 'self'; "
                         "base-uri 'none'; frame-ancestors 'none'")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "same-origin")
        if location:
            self.send_header("Location", location)
        self.end_headers()
        self.wfile.write(data)

    def guard(self):
        ok, who = Access.allowed(self.client_address[0].removeprefix("::ffff:"))
        self.who = who
        if not ok:
            self.send(403, page("geweigerd", "<p>Alleen vanaf je eigen apparaten op het tailnet.</p>"))
        return ok

    def do_GET(self):
        if not self.guard():
            return
        url = urllib.parse.urlsplit(self.path)
        if m := re.fullmatch(r"/static/((?:fonts/)?[A-Za-z0-9-]+\.(css|js|woff2))", url.path):
            f = WEB / m.group(1)
            if f.is_file():
                ctype = {"css": "text/css; charset=utf-8", "js": "text/javascript; charset=utf-8",
                         "woff2": "font/woff2"}[m.group(2)]
                return self.send(200, f.read_bytes(), ctype, cache="private, max-age=86400")
        if url.path == "/api/live":
            items, queued = live_items()
            return self.send(200, json.dumps({"items": items, "queued": queued,
                                              "activity": activity_sentence(items, queued)}),
                             "application/json")
        if m := re.fullmatch(r"/api/log/([tr])(\d+)", url.path):
            start = int((urllib.parse.parse_qs(url.query).get("from") or ["0"])[0] or 0)
            lines, nxt = read_log(f"{m.group(1)}{m.group(2)}", max(0, start))
            if m.group(1) == "t":
                done = (t := get(int(m.group(2)))) is None or t["status"] not in ACTIVE
            else:
                with db() as c:
                    r = c.execute("SELECT status FROM job_runs WHERE id=?", (int(m.group(2)),)).fetchone()
                done = r is None or r["status"] not in ("queued", "running")
            # Klaar, maar nog regels te gaan: dan eerst die (done pas als alles binnen is).
            more = read_log(f"{m.group(1)}{m.group(2)}", nxt, 1)[0]
            return self.send(200, json.dumps({"lines": lines, "next": nxt, "done": done and not more}),
                             "application/json")
        if url.path == "/taken":
            return self.send(200, tasks_page())
        if url.path == "/ssh":
            return self.send(200, ssh_page())
        if url.path == "/memory":
            return self.send(200, memory_page(urllib.parse.parse_qs(url.query).get("q", [""])[0][:200]))
        if (m := re.fullmatch(r"/memory/e/(.+)", self.path)) and (entry := mem_get(m.group(1))):
            return self.send(200, entry_page(entry))
        m = re.fullmatch(r"/task/(\d+)", self.path)
        if self.path == "/":
            return self.send(200, overview_page())
        if m and (t := get(int(m.group(1)))):
            return self.send(200, task_page(t))
        if self.path == "/jobs":
            return self.send(200, jobs_page())
        if self.path == "/skills":
            return self.send(200, skills_page())
        if self.path == "/briefs":
            return self.send(200, briefs_page())
        if (m := re.fullmatch(r"/briefs/([A-Z]-\d{3,4})", self.path)) and (b := brief_by_id(m.group(1))):
            return self.send(200, brief_page(b))
        if (m := re.fullmatch(r"/job/([a-z0-9-]+)", self.path)) and m.group(1) in JOBS:
            return self.send(200, job_page(m.group(1)))
        if (m := re.fullmatch(r"/draft/(\d+)", self.path)) and (d := get_draft(int(m.group(1)))):
            return self.send(200, draft_page(d))
        if m := re.fullmatch(r"/run/(\d+)", self.path):
            with db() as c:
                r = c.execute("SELECT * FROM job_runs WHERE id=?", (int(m.group(1)),)).fetchone()
            if r:
                return self.send(200, run_page(r))
        self.send(404, page("niet gevonden", "<p>Niet gevonden.</p>"))

    def do_POST(self):
        if not self.guard():
            return
        # Tegen CSRF: het formulier moet van deze pagina komen (Origin) en het token bevatten.
        origin = self.headers.get("Origin", "")
        if urllib.parse.urlsplit(origin).netloc != self.headers.get("Host", ""):
            return self.send(403, page("geweigerd", "<p>Verkeerde herkomst.</p>"))
        length = min(int(self.headers.get("Content-Length") or 0), 64 * 1024)
        form = {k: v[0] for k, v in urllib.parse.parse_qs(self.rfile.read(length).decode()).items()}
        if not hmac.compare_digest(form.get("csrf", ""), csrf_token()):
            return self.send(403, page("geweigerd", "<p>Verlopen formulier; ververs de pagina.</p>"))
        if self.path == "/tasks":
            repo, text = form.get("repo", ""), form.get("text", "").strip()
            if (repo not in repo_list() and repo != AUTO) or not text:
                return self.send(400, page("fout", "<p>Kies een repo uit de lijst en beschrijf de taak.</p>"))
            with _lock, db() as c:
                cur = c.execute("INSERT INTO tasks (repo, text, status, created, session) VALUES (?,?,?,?,?)",
                                (repo, text[:8000], "queued", now(), str(uuid.uuid4())))
                tid = cur.lastrowid
            return self.send(303, "", location=f"/task/{tid}")
        if m := re.fullmatch(r"/briefs/([A-Z]-\d{3,4})/(build|park)", self.path):
            try:
                if m.group(2) == "park":
                    set_brief_status(m.group(1), "parked", f"parked on the homelab page by {self.who}")
                    return self.send(303, "", location="/briefs")
                tid = start_build(m.group(1), unattended=form.get("mode") == "without-me", who=self.who)
                return self.send(303, "", location=f"/task/{tid}")
            except Exception as ex:
                return self.send(400, briefs_page(error=str(ex)))
        if m := re.fullmatch(r"/run/(\d+)/cancel", self.path):
            return self.cancel_run(int(m.group(1)))
        if self.path.startswith(("/jobs/", "/job/", "/draft/")):
            return self.post_jobs(form)
        if self.path == "/ssh":
            name, key = form.get("name", "").strip(), form.get("key", "").strip()
            r = admin_ask({"cmd": "sshkey", "label": name, "key": key, "by": self.who})
            if not r.get("ok"):
                # Een geplakte privésleutel nooit terug op de pagina zetten.
                return self.send(400, ssh_page(error=r.get("text", "mislukt"), name=name,
                                               key="" if "PRIVATE KEY" in key else key))
            return self.send(200, ssh_page(done=r, name=name))
        if self.path.startswith("/memory/"):
            return self.post_memory(form)
        m = re.fullmatch(r"/task/(\d+)/(answer|cancel|attended)", self.path)
        t = get(int(m.group(1))) if m else None
        if not t:
            return self.send(404, page("niet gevonden", "<p>Niet gevonden.</p>"))
        if m.group(2) == "answer" and t["status"] == "waiting" and form.get("answer", "").strip():
            update(t["id"], status="queued", answer=None, pending_input=(
                f"Antwoord van de gebruiker op je vraag:\n\n{form['answer'].strip()[:8000]}\n\n"
                "Ga verder met de taak. Stel geen nieuwe vraag; neem waar nodig een redelijke aanname en noem die. "
                + LEARN_HINT), mem_source=f"jouw antwoord in {label(t).lower()}", mem_link=page_url(t["id"]))
        elif m.group(2) == "answer" and t["status"] == "answered" and not t["job"] and form.get("answer", "").strip():
            # Vervolg op een antwoord (bijvoorbeeld een plan met een vraag erin): zelfde sessie, nieuwe werkkopie.
            update(t["id"], status="queued", question=t["answer"], answer=None, ended=None, reprepare=1,
                   pending_input=(f"Reactie van de gebruiker op je antwoord:\n\n{form['answer'].strip()[:8000]}\n\n"
                                  "Ga verder met de taak. Neem waar nodig een redelijke aanname en noem die. "
                                  + LEARN_HINT),
                   mem_source=f"jouw reactie in {label(t).lower()}", mem_link=page_url(t["id"]))
        elif m.group(2) == "cancel" and (t["status"] in ACTIVE + ("waiting",) or (t["status"] == "routed" and not t["ended"])):
            if t["status"] == "routed":  # het hele bericht: alle kindtaken die nog niet klaar zijn
                for k in children(t["id"]):
                    if k["status"] in ACTIVE + ("waiting",):
                        cancel_task(k)
                update(t["id"], status="cancelled", ended=now())
            else:
                cancel_task(t)
        elif m.group(2) == "attended" and t["repo"] == BRIEF and t["unattended"]:
            update(t["id"], unattended=0)
            log_line(f"t{t['id']}", "info", f"build without me stopped by {self.who}: you merge the rest")
        return self.send(303, "", location=f"/task/{t['id']}")


    def post_jobs(self, form):
        """Jobs (F-022): opstellen, bevestigen, wijzigen, pauzeren, verwijderen, nu draaien."""
        text = form.get("text", "").replace("\r\n", "\n").strip()
        who = f"bevestigd op de pagina vanaf {self.who}"
        if self.path == "/jobs/draft" and text:
            return self.send(303, "", location=f"/draft/{start_draft(text[:4000])}")
        m = re.fullmatch(r"/draft/(\d+)/(confirm|cancel)", self.path)
        if m and (d := get_draft(int(m.group(1)))) and d["status"] == "ready":
            if m.group(2) == "cancel":
                update_draft(d["id"], status="cancelled")
                return self.send(303, "", location="/jobs")
            name = d["base"] or draft_name(text)
            try:
                if not d["base"] and name in JOBS:
                    raise ValueError(f"er is al een job {name}; kies een andere name")
                if d["base"] and draft_name(text) not in ("", d["base"]):
                    raise ValueError(f"de naam blijft {d['base']}")
                parse_job(name, text)
                save_job(name, text, f"jobs: {name} {'gewijzigd' if d['base'] else 'nieuw'} ({who})")
            except Exception as ex:
                return self.send(400, draft_page(d, error=str(ex), text=text))
            update_draft(d["id"], status="confirmed", text=text)
            return self.send(303, "", location=f"/job/{name}")
        m = re.fullmatch(r"/job/([a-z0-9-]+)/(run|pause|resume|delete|save|change)", self.path)
        if not m or m.group(1) not in JOBS:
            return self.send(404, page("niet gevonden", "<p>Niet gevonden.</p>"))
        name, action, job = m.group(1), m.group(2), JOBS[m.group(1)]
        try:
            if action == "change" and text:
                return self.send(303, "", location=f"/draft/{start_draft(text[:4000], base=name)}")
            if action == "run" and "error" not in job:
                dispatch(job, now(), manual=True)
            elif action in ("pause", "resume") and "error" not in job:
                save_job(name, set_paused(job["text"], action == "pause"),
                         f"jobs: {name} {'gepauzeerd' if action == 'pause' else 'hervat'} ({who})")
            elif action == "save" and text:
                parse_job(name, text)
                save_job(name, text, f"jobs: {name} bewerkt ({who})")
            elif action == "delete":
                save_job(name, None, f"jobs: {name} verwijderd ({who})")
                return self.send(303, "", location="/jobs")
        except Exception as ex:
            return self.send(400, job_page(name, error=str(ex), text=text or None))
        return self.send(303, "", location=f"/job/{name}")


    def cancel_run(self, run_id):
        """Een lokale job-run stoppen (F-024); een Claude-job annuleer je via zijn taak, net als in F-021."""
        with db() as c:
            r = c.execute("SELECT * FROM job_runs WHERE id=?", (run_id,)).fetchone()
        if not r:
            return self.send(404, page("niet gevonden", "<p>Niet gevonden.</p>"))
        if r["task_id"]:
            return self.send(303, "", location=f"/task/{r['task_id']}")
        if r["status"] == "running":
            _cancel_runs.add(run_id)
            update_run(run_id, status="cancelled", ended=now(), note="geannuleerd op de pagina")
            log_line(f"r{run_id}", "info", "geannuleerd op de pagina")
            if r["kind"] == "script":
                threading.Thread(target=task_cmd, args=("kill", f"j{run_id}"), kwargs={"timeout": 30},
                                 daemon=True).start()
        return self.send(303, "", location=f"/run/{run_id}")

    def post_memory(self, form):
        """Geheugen (F-023): toevoegen, bewerken, wissen; elke wijziging een commit op naam van de gebruiker."""
        text = form.get("text", "").replace("\r\n", "\n").strip()
        who = f"door jou op de pagina vanaf {self.who}"
        author = (f"{CONF['OWNER_GITHUB']} (via de pagina)", "homelab-inbox@homelab")
        if self.path == "/memory/add":
            kind, about, name = form.get("kind", ""), form.get("about", ""), form.get("name", "").strip()
            mid = f"{kind}/{about}/{name}"
            try:
                if kind not in ("voorkeuren", "feiten") or about not in {"algemeen", "box", *repo_list()} \
                        or not MEM_ID_RE.match(mid):
                    raise ValueError("kies voorkeur of feit, een onderwerp uit de lijst en een naam met kleine "
                                     "letters, cijfers en streepjes")
                if mem_get(mid):
                    raise ValueError(f"{mid} bestaat al; bewerk dat item")
                check_mem_text(text)
                save_memory({mid: entry_text(about, "door jou toegevoegd", "", text, now())},
                            f"geheugen: {mid} nieuw ({who})", author)
            except Exception as ex:
                return self.send(400, memory_page(error=str(ex)))
            return self.send(303, "", location=f"/memory/e/{mid}")
        m = re.fullmatch(r"/memory/e/(.+)/(save|delete)", self.path)
        entry = mem_get(m.group(1)) if m else None
        if not entry:
            return self.send(404, page("niet gevonden", "<p>Niet gevonden.</p>"))
        try:
            if m.group(2) == "delete":
                save_memory({entry["id"]: None}, f"geheugen: {entry['id']} gewist ({who})", author)
                return self.send(303, "", location="/memory")
            check_mem_text(text)
            source = entry["source"] if entry["source"].endswith("bewerkt door jou") or \
                entry["source"] == "door jou toegevoegd" else f"{entry['source'] or '-'}, bewerkt door jou"
            extra = {k: entry[k] for k in ("status", "pr")}
            save_memory({entry["id"]: entry_text(entry["about"], source, entry["link"], text, entry["added"] or now(),
                                                 **extra)}, f"geheugen: {entry['id']} bewerkt ({who})", author)
        except Exception as ex:
            return self.send(400, entry_page(entry, error=str(ex), text=text))
        return self.send(303, "", location=f"/memory/e/{entry['id']}")


def check_mem_text(text):
    if not text:
        raise ValueError("de tekst is leeg")
    if len(text) > 4000:
        raise ValueError("hooguit 4000 tekens")
    if SECRET_RE.search(text):
        raise ValueError("dit lijkt een token, wachtwoord of sleutel te bevatten; dat komt nooit in het geheugen")


class Server(ThreadingHTTPServer):
    import socket as _s
    address_family = _s.AF_INET6
    daemon_threads = True

    def server_bind(self):
        self.socket.setsockopt(self._s.IPPROTO_IPV6, self._s.IPV6_V6ONLY, 0)
        super().server_bind()


def serve():
    HOME.mkdir(parents=True, exist_ok=True)
    db().close()
    recover()
    load_memory()
    Access.init()
    for target in (scheduler, poll_github, job_scheduler):
        threading.Thread(target=target, daemon=True).start()
    srv = Server(("::", int(CONF["INBOX_PORT"])), Handler)
    log(f"homelab-inbox op poort {CONF['INBOX_PORT']}, eigenaar {Access.owner}")
    srv.serve_forever()
