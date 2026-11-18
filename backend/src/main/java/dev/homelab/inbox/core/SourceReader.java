package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.JobIo;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** The sources of a digest and of the repo watcher, as text for the model ({@code watch_repo}, {@code fetch_source},
 * {@code feed_text}). Reads only, and only through {@link JobIo}. */
final class SourceReader {

    private SourceReader() {}

    private static final Pattern FEED = Pattern.compile("<(rss|feed)[\\s>]");

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static String s(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String firstLine(String message) {
        return message.lines().findFirst().orElse("");
    }

    /** New commits, pull requests and releases of a GitHub repo since {@code since}; "geen activiteit" when none. */
    static String watchRepo(JobIo io, String slug, String since) {
        List<Map<String, Object>> commits;
        List<Map<String, Object>> pulls;
        List<Map<String, Object>> releases;
        try {
            commits = list(io.github("/repos/" + slug + "/commits?since=" + since + "&per_page=30"));
            pulls = list(io.github("/repos/" + slug + "/pulls?state=all&sort=updated&direction=desc&per_page=30")).stream()
                    .filter(p -> s(p.get("updated_at")).compareTo(since) >= 0).toList();
            releases = list(io.github("/repos/" + slug + "/releases?per_page=10")).stream()
                    .filter(r -> !Boolean.TRUE.equals(r.get("draft")))
                    .filter(r -> (r.get("published_at") != null && !s(r.get("published_at")).isEmpty() ? s(r.get("published_at"))
                            : s(r.get("created_at"))).compareTo(since) >= 0).toList();
        } catch (Exception e) {
            return "## GitHub " + slug + "\n(ophalen mislukt: " + e.getMessage() + ")";
        }
        List<String> lines = activity(commits, pulls);
        for (Map<String, Object> r : releases) {
            lines.add(("release " + s(r.get("tag_name")) + ": " + s(r.get("name")) + " " + s(r.get("html_url"))).stripTrailing());
        }
        return "## GitHub " + slug + ", sinds " + since.substring(0, 10) + "\n"
                + (lines.isEmpty() ? "geen activiteit" : String.join("\n", lines));
    }

    @SuppressWarnings("unchecked")
    private static List<String> activity(List<Map<String, Object>> commits, List<Map<String, Object>> pulls) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> c : commits) {
            String sha = s(c.get("sha"));
            String message = s(((Map<String, Object>) c.get("commit")).get("message"));
            lines.add("commit " + sha.substring(0, Math.min(7, sha.length())) + " " + firstLine(message));
        }
        for (Map<String, Object> p : pulls) {
            lines.add("PR #" + p.get("number") + " (" + (p.get("merged_at") != null ? "gemerged" : s(p.get("state"))) + "): "
                    + s(p.get("title")));
        }
        return lines;
    }

    /** One source of a digest: a GitHub repo (last 24 hours), a feed or a web page. A failure is text, not an exception. */
    static String fetchSource(JobIo io, String src, Instant now) {
        try {
            if (src.startsWith("github:")) {
                String slug = src.substring("github:".length());
                String since = JobText.iso(now.minus(Duration.ofDays(1))).replace("+00:00", "Z");
                String sinceDay = since;
                List<String> lines = activity(list(io.github("/repos/" + slug + "/commits?since=" + since + "&per_page=30")),
                        list(io.github("/repos/" + slug + "/pulls?state=all&sort=updated&direction=desc&per_page=20"))
                                .stream().filter(p -> s(p.get("updated_at")).compareTo(sinceDay) >= 0).toList());
                return "## GitHub " + slug + ", laatste 24 uur\n" + (lines.isEmpty() ? "geen activiteit" : String.join("\n", lines));
            }
            String raw = io.fetchPage(src);
            return "## " + src + "\n" + (FEED.matcher(raw.substring(0, Math.min(2000, raw.length()))).find()
                    ? feedText(raw) : JobText.htmlText(raw));
        } catch (Exception e) {
            return "## " + src + "\n(ophalen mislukt: " + e.getMessage() + ")";
        }
    }

    private static String childText(Element item, String name) {
        NodeList kids = item.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element e && local(e).equals(name)) {
                return e.getTextContent() == null ? "" : e.getTextContent().strip();
            }
        }
        return "";
    }

    private static String local(Element e) {
        String tag = e.getNodeName();
        return tag.substring(tag.lastIndexOf(':') + 1);
    }

    /** {@code feed_text}: the first 20 items of an RSS or Atom feed. */
    static String feedText(String raw) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            NodeList all = f.newDocumentBuilder().parse(new InputSource(new StringReader(raw))).getElementsByTagName("*");
            List<String> items = new ArrayList<>();
            for (int i = 0; i < all.getLength() && items.size() < 20; i++) {
                Element it = (Element) all.item(i);
                if (!local(it).equals("item") && !local(it).equals("entry")) {
                    continue;
                }
                String link = childText(it, "link");
                if (link.isEmpty()) {
                    NodeList kids = it.getChildNodes();
                    for (int k = 0; k < kids.getLength() && link.isEmpty(); k++) {
                        if (kids.item(k) instanceof Element e && local(e).equals("link")) {
                            link = e.getAttribute("href");
                        }
                    }
                }
                String date = childText(it, "pubDate");
                date = date.isEmpty() ? childText(it, "updated") : date;
                date = date.isEmpty() ? childText(it, "published") : date;
                items.add("- " + childText(it, "title") + " (" + date + ") " + link);
            }
            return items.isEmpty() ? "geen items" : String.join("\n", items);
        } catch (Exception e) {
            throw new IllegalStateException("feed: " + e.getMessage(), e);
        }
    }
}
