package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.Notifier;
import dev.homelab.inbox.read.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A skill proposal of Claude becomes a PR on a branch {@code skill/<name>-<id>} ({@code jobs.propose_skill}); never main. */
final class LiveSkills {

    private static final Logger log = LoggerFactory.getLogger(LiveSkills.class);

    private final ServiceGit git;
    private final Gh gh;
    private final Settings conf;
    private final TaskStore tasks;
    private final Notifier notifier;
    private final String pageBase;

    LiveSkills(ServiceGit git, Gh gh, Settings conf, TaskStore tasks, Notifier notifier, String pageBase) {
        this.git = git;
        this.gh = gh;
        this.conf = conf;
        this.tasks = tasks;
        this.notifier = notifier;
        this.pageBase = pageBase;
    }

    void propose(Task t, String name, String description, String body) {
        String slug = conf.conf("HOMELAB_REPO", "chrisfaber95/homelab");
        try {
            Path cache = git.refresh("homelab", slug);
            boolean exists = Files.exists(cache.resolve("skills").resolve(name).resolve("SKILL.md"));
            String branch = "skill/" + name + "-" + t.id();
            Path tmp = Files.createTempDirectory(conf.runnerHome(), "skill");
            try {
                Path repo = tmp.resolve("r");
                git.git(conf.runnerHome(), "clone", "--quiet", cache.toString(), repo.toString());
                git.git(repo, "checkout", "--quiet", "-b", branch);
                Path f = repo.resolve("skills").resolve(name).resolve("SKILL.md");
                Files.createDirectories(f.getParent());
                Files.writeString(f, "---\nname: " + name + "\ndescription: " + Gh.JSON.writeValueAsString(description.strip())
                        + "\n---\n\n" + body.strip() + "\n");
                git.git(repo, "add", "-A");
                List<String> commit = new ArrayList<>(List.of("-c", "user.name=Claude (homelab-inbox)", "-c",
                        "user.email=noreply@anthropic.com", "commit", "--quiet", "-m",
                        "Skill " + name + ": " + (exists ? "verbetering" : "nieuw") + " (voorstel uit " + TaskActions.label(t) + ")"));
                git.git(repo, commit.toArray(String[]::new));
                // Only to skill/<name>-<id>, never to main.
                git.git(repo, "push", "--quiet", git.remoteUrl("homelab", slug), "HEAD:refs/heads/" + branch);
            } finally {
                Share.remove(tmp);
            }
            Effects.PullRequests.Opened pr = gh.createPr(slug, "skill: " + name + (exists ? " (verbetering)" : ""), branch, "main",
                    description.strip() + "\n\nVoorgesteld door Claude na " + TaskActions.label(t) + " (" + pageBase + "/task/"
                            + t.id() + "). Na de merge gebruiken taken en jobs deze skill; zonder merge niet. Claude kan hem niet zelf "
                            + "mergen.\n\n<!-- homelab-inbox skill -->");
            tasks.addSkillPr(name, t.id(), pr.url());
            notifier.notify("Skill-voorstel: " + name, description.strip(), pr.url());
        } catch (IOException | RuntimeException e) {
            log.warn("skill-voorstel {}: {}", name, e.toString());
            notifier.notify("Skill-voorstel " + name + " mislukt", String.valueOf(e.getMessage()), pageBase + "/task/" + t.id());
        }
    }
}
