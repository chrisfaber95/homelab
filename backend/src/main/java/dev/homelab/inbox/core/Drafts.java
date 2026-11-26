package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.DraftClaude;
import dev.homelab.inbox.read.Jobs;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A job draft ({@code jobs.start_draft} and {@code jobs.make_draft}): the row is made at once, Claude writes the job file
 * on a background thread through {@link DraftClaude}, and the row becomes {@code ready} or {@code failed}. In shadow the
 * effect answers with an error, so nothing is started.
 */
public class Drafts {

    private static final Logger log = LoggerFactory.getLogger(Drafts.class);

    static final String SYSTEM = """
            Je stelt een job-bestand op voor de homelab-inbox (F-022). Je hebt geen tools; je antwoordt alleen met
            het bestand, in één ```job-blok, zonder verdere tekst. Formaat:

            ```job
            ---
            name: korte-naam-met-streepjes
            when: het schema in gewone woorden, in het Nederlands
            schedule: "0 7 * * 1-5"   (alleen de vijf cron-velden: minuut uur dag maand weekdag; tijdzone {tz}; 0 = zondag)
            kind: box-report | repo-chore | digest | script | repo-watch
            model: local | claude
            repo: alleen bij repo-chore (verplicht) en script (standaard homelab); een van: {repos}
            skills: [alleen bij model claude, en alleen uit: {skills}]
            script: alleen bij script: pad in de repo
            args: [alleen bij script, optioneel]
            sources: [alleen bij digest en repo-watch (daar alleen github:eigenaar/repo): https-URL's (RSS of webpagina) of github:eigenaar/repo]
            deliver: page | ntfy | conversation   (optioneel; standaard page. Bij ntfy of conversation moet de instructie zeggen wat "niets te melden" is en dan met {marker} als eerste regel antwoorden)
            minutes: hoeveel minuten handwerk een run de gebruiker bespaart (schatting, geheel getal)
            ---
            De instructie: wat de job doet en hoe het resultaat eruit moet zien, kort en concreet.
            ```

            Soorten: box-report (rapport uit de staat van de box: health checks, deploys, LLM-gebruik, wijzigingen, backup),
            repo-chore (een klus in een repo die eindigt in een PR, bijvoorbeeld afhankelijkheden bijwerken of oude branches),
            digest (bronnen ophalen en samenvatten), repo-watch (nieuwe commits, PR's en releases van GitHub-repos sinds de dag van de vorige run), script (een script uit een repo draaien en de uitvoer samenvatten).
            Model: local (het lokale model, gratis) als de job alleen tekst samenvat of eruit haalt die de job zelf verzamelt
            (box-report, digest, script); claude als er tools, code of een repo nodig zijn (repo-chore altijd claude).
            Laat velden die niet gelden weg. De instructie voor een lokale job is een opdracht aan een klein model: kort, één
            taak, met de gewenste vorm (bijvoorbeeld "alleen wat faalde, in hooguit vijf regels; niets mis: zeg dat in één zin").""";

    private final JobRuns runs;
    private final TaskStore store;
    private final Jobs jobs;
    private final JobSettings settings;
    private final DraftClaude claude;
    private final Executor background;

    public Drafts(JobRuns runs, TaskStore store, Jobs jobs, JobSettings settings, DraftClaude claude, Executor background) {
        this.runs = runs;
        this.store = store;
        this.jobs = jobs;
        this.settings = settings;
        this.claude = claude;
        this.background = background;
    }

    /** The row of a new draft ({@code base} null for a new job, a job name for a change); Claude is started behind it. */
    public long start(String request, String base) {
        long id = runs.insertDrafting(store.now(), request, base);
        background.execute(() -> make(id));
        return id;
    }

    void make(long id) {
        try {
            var d = runs.draft(id).orElseThrow();
            String request = String.valueOf(d.get("request"));
            String base = d.get("base") == null ? null : d.get("base").toString();
            String baseText = null;
            if (base != null && jobs.load().containsKey(base)) {
                baseText = jobs.fileText(base).orElse(null);
            }
            String system = SYSTEM.replace("{tz}", settings.tz().getId()).replace("{marker}", JobText.NOTHING_MARKER)
                    .replace("{repos}", String.join(", ", jobs.repoNames()))
                    .replace("{skills}", jobs.skillNames().isEmpty() ? "(nog geen)" : String.join(", ", jobs.skillNames()));
            Effects.DraftAnswer a = claude.draft(id, system, DraftRunnerOutput.prompt(request, base, baseText));
            if (a.text() != null) {
                runs.updateDraft(id, TaskStore.fields("status", "ready", "text", a.text()));
            } else {
                runs.updateDraft(id, TaskStore.fields("status", "failed", "error", cut(a.error(), 500)));
            }
        } catch (RuntimeException e) {
            log.warn("draft {}: {}", id, e.toString());
            try {
                runs.updateDraft(id, TaskStore.fields("status", "failed", "error", cut(String.valueOf(e.getMessage()), 500)));
            } catch (RuntimeException again) {
                log.warn("draft {} could not be marked failed: {}", id, again.toString());
            }
        }
    }

    private static String cut(String s, int n) {
        String v = String.valueOf(s);
        return v.length() > n ? v.substring(0, n) : v;
    }
}
