package dev.homelab.inbox.read;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * What the Skills page shows (H-024 M2), as {@code overview.skills_data} builds it: the merged skills of the service's
 * copy of main ({@code repos/homelab/skills/<name>/SKILL.md}) with the jobs that use each, and the 20 newest skill
 * proposals. The proposals are read from {@code inbox.db} like the other M1/M2 reads (see {@link InboxDb}); the Python
 * inbox still writes them.
 */
@Component
public class SkillsService {

    private static final String INTRO = "Gemergde skills op main; elke taak en Claude-job kan ze gebruiken. "
            + "Nieuwe en betere komen alleen via een PR die jij merget.";
    private static final Pattern DESCRIPTION =
            Pattern.compile("^description:\\s*(.+)$", Pattern.MULTILINE | Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS);

    private final Settings settings;
    private final Jobs jobs;
    private final InboxDb db;

    public SkillsService(Settings settings, Jobs jobs, InboxDb db) {
        this.settings = settings;
        this.jobs = jobs;
        this.db = db;
    }

    /** jobs.skill_list: name -> description, in the order of the directory names. */
    Map<String, String> skillList() {
        Map<String, String> out = new TreeMap<>();
        Path dir = settings.skillsDir();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path d : s.toList()) {
                Path file = d.resolve("SKILL.md");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String text = Files.readString(file);
                int cut = text.indexOf("\n---");
                Matcher m = DESCRIPTION.matcher(cut < 0 ? text : text.substring(0, cut));
                out.put(d.getFileName().toString(), m.find() ? unquote(m.group(1).strip()) : "");
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the skills in " + dir + ": " + e.getMessage(), e);
        }
        return out;
    }

    /** {@code s.strip('"')} */
    private static String unquote(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && s.charAt(from) == '"') {
            from++;
        }
        while (to > from && s.charAt(to - 1) == '"') {
            to--;
        }
        return s.substring(from, to);
    }

    public Map<String, Object> data() {
        Map<String, List<String>> used = new LinkedHashMap<>();
        // Python loads the job files sorted by file name (with the .md) and lists the jobs of a skill in that order.
        jobs.load().values().stream().filter(Jobs.Job::ok).sorted(Comparator.comparing(j -> j.name() + ".md"))
                .forEach(j -> j.skills().forEach(s -> used.computeIfAbsent(s, k -> new ArrayList<>()).add(j.name())));
        List<Map<String, Object>> skills = new ArrayList<>();
        skillList().forEach((name, description) -> {
            Map<String, Object> skill = new LinkedHashMap<>();
            skill.put("name", name);
            skill.put("description", description);
            skill.put("jobs", used.getOrDefault(name, List.of()));
            skills.add(skill);
        });
        List<Map<String, Object>> proposals = new ArrayList<>();
        for (Map<String, Object> p : db.query("SELECT * FROM skill_prs ORDER BY id DESC LIMIT 20")) {
            Map<String, Object> proposal = new LinkedHashMap<>();
            proposal.put("name", InboxDb.str(p, "name"));
            proposal.put("pr_url", InboxDb.str(p, "pr_url"));
            proposal.put("task_id", InboxDb.num(p, "task_id"));
            proposal.put("created", Text.local(InboxDb.str(p, "created"), settings.tz()));
            proposals.add(proposal);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intro", INTRO);
        out.put("empty", "Nog geen skills.");
        out.put("proposals_empty", "Nog geen voorstellen.");
        out.put("skills", skills);
        out.put("proposals", proposals);
        return out;
    }
}
