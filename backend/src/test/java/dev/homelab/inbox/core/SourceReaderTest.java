package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The repo watcher and the digest sources. Ports of test_inbox_agents.py (RepoWatcher); the feed and page parts are
 * written from {@code jobs.fetch_source} and {@code feed_text} (Python has no test for them). */
class SourceReaderTest {

    private static final String OLD = "2026-11-01T10:00:00Z";
    private static final String NEW = "2026-11-13T10:00:00Z";

    private JobFixture.Recorder fx() {
        JobFixture.Recorder fx = new JobFixture.Recorder();
        fx.github.put("/repos/o/r/commits", List.of(Map.of("sha", "abcdef123", "commit", Map.of("message", "Fix it\n\nlong"))));
        fx.github.put("/repos/o/r/pulls", List.of(
                Map.of("number", 5, "state", "closed", "merged_at", NEW, "title", "New PR", "updated_at", NEW),
                Map.of("number", 4, "state", "open", "title", "Old PR", "updated_at", OLD)));
        fx.github.put("/repos/o/r/releases", List.of(
                Map.of("tag_name", "v2", "name", "Two", "published_at", NEW, "html_url", "u"),
                Map.of("tag_name", "v1", "published_at", OLD),
                Map.of("tag_name", "v3", "draft", true, "published_at", NEW)));
        return fx;
    }

    @Test // RepoWatcher.test_reports_commits_prs_and_releases_since
    void reportsCommitsPrsAndReleasesSince() {
        String out = SourceReader.watchRepo(fx(), "o/r", "2026-11-12T00:00:00Z");
        assertThat(out).startsWith("## GitHub o/r, sinds 2026-11-12\n");
        assertThat(out).contains("commit abcdef1 Fix it").contains("PR #5 (gemerged): New PR").contains("release v2: Two u");
        assertThat(out).doesNotContain("Old PR").doesNotContain("v1").doesNotContain("v3");
    }

    @Test // RepoWatcher.test_nothing_new_says_so
    void nothingNewSaysSo() {
        assertThat(SourceReader.watchRepo(new JobFixture.Recorder(), "o/r", "2026-11-12T00:00:00Z")).contains("geen activiteit");
    }

    @Test // (written from jobs.watch_repo: a failing GitHub read is text, not an exception)
    void aFailingReadIsTextNotAnException() {
        JobFixture.Recorder fx = new JobFixture.Recorder();
        fx.failure = new IOException("HTTP 403");
        assertThat(SourceReader.watchRepo(fx, "o/r", "2026-11-12T00:00:00Z")).isEqualTo("## GitHub o/r\n(ophalen mislukt: HTTP 403)");
    }

    @Test // (written from jobs.fetch_source: a digest reads the last 24 hours of a GitHub repo)
    void aDigestReadsTheLastDayOfAGithubRepo() {
        JobFixture.Recorder fx = fx();
        String out = SourceReader.fetchSource(fx, "github:o/r", Instant.parse("2026-11-14T08:00:00Z"));
        assertThat(out).startsWith("## GitHub o/r, laatste 24 uur\n").contains("commit abcdef1 Fix it").contains("PR #5");
        assertThat(fx.calls()).contains("github /repos/o/r/commits?since=2026-11-13T08:00:00Z&per_page=30");
    }

    @Test // (written from jobs.feed_text)
    void aFeedBecomesItsFirstItems() throws Exception {
        JobFixture.Recorder fx = new JobFixture.Recorder();
        fx.pageText = "<?xml version=\"1.0\"?><rss><channel><item><title>One</title><link>http://a</link><pubDate>Mon</pubDate></item>"
                + "<item><title>Two</title><link>http://b</link></item></channel></rss>";
        assertThat(SourceReader.fetchSource(fx, "https://x/feed", Instant.EPOCH)).isEqualTo("## https://x/feed\n- One (Mon) http://a\n- Two () http://b");
        fx.pageText = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><title>A</title><link href=\"http://c\"/><updated>D</updated></entry></feed>";
        assertThat(SourceReader.fetchSource(fx, "https://x/atom", Instant.EPOCH)).endsWith("- A (D) http://c");
    }

    @Test // (written from jobs.fetch_source: a page becomes text, a failed fetch is text)
    void aPageBecomesTextAndAFailedFetchIsText() {
        JobFixture.Recorder fx = new JobFixture.Recorder();
        fx.pageText = "<html><body><p>Hello <b>world</b></p></body></html>";
        assertThat(SourceReader.fetchSource(fx, "https://x/p", Instant.EPOCH)).isEqualTo("## https://x/p\nHello world");
        fx.failure = new IOException("timed out");
        assertThat(SourceReader.fetchSource(fx, "https://x/p", Instant.EPOCH)).isEqualTo("## https://x/p\n(ophalen mislukt: timed out)");
    }
}
