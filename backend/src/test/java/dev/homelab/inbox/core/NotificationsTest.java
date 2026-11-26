package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ports {@code tests/test_inbox_events.Notifications} (the questions test is {@code RoundFinishTest}). */
class NotificationsTest {

    private static final String LONG = "A long result sentence. ".repeat(400);

    private final List<String[]> notes = new ArrayList<>();
    private final List<String[]> pushes = new ArrayList<>();
    private boolean conversationFails;
    private boolean pushFails;

    private final Notifications notifications = new Notifications(
            (author, client, kind, text, link, ref) -> {
                if (conversationFails) {
                    throw new IllegalStateException("disk full");
                }
                notes.add(new String[] {author, client, kind, text, link});
            },
            (title, message, click) -> {
                if (pushFails) {
                    throw new IOException("ntfy down");
                }
                pushes.add(new String[] {title, message, click});
            });

    /** test_full_text_and_link_are_stored */
    @Test
    void theFullTextAndTheLinkAreStored() {
        notifications.notify("Inbox 5: PR #3", LONG, "https://github.com/x/y/pull/3");
        assertThat(notes).hasSize(1);
        String[] e = notes.get(0);
        assertThat(e[0] + "/" + e[1] + "/" + e[2]).isEqualTo("box/ntfy/notification");
        assertThat(e[3]).isEqualTo("Inbox 5: PR #3\n" + LONG);
        assertThat(e[4]).isEqualTo("https://github.com/x/y/pull/3");
    }

    /** test_ntfy_gets_the_short_text */
    @Test
    void ntfyGetsTheShortTextAndTheLink() {
        notifications.notify("T", LONG, "http://x/1");
        assertThat(pushes.get(0)[1]).hasSize(300);
        assertThat(pushes.get(0)[2]).isEqualTo("http://x/1");
        assertThat(notes.get(0)[3].length()).isGreaterThan(5000);
        notifications.notify("T", LONG, "http://x/1", 100);
        assertThat(pushes.get(1)[1]).hasSize(100);
    }

    /** the caller stored the full text itself (an answer or a question of a task): only the push */
    @Test
    void storeFalseOnlyPushes() {
        notifications.notify("T", "short", "http://x/1", false);
        assertThat(notes).isEmpty();
        assertThat(pushes).hasSize(1);
    }

    /** test_ntfy_still_sends_when_the_conversation_fails */
    @Test
    void ntfyStillSendsWhenTheConversationFails() {
        conversationFails = true;
        notifications.notify("Inbox 6 mislukt", "boom", "http://x/6");
        assertThat(pushes).hasSize(1);
        assertThat(pushes.get(0)[0]).isEqualTo("Inbox 6 mislukt");
    }

    /** helpers.notify: a push that cannot be sent is logged, never thrown at the caller; the conversation has it */
    @Test
    void aFailingPushDoesNotFailTheCaller() {
        pushFails = true;
        notifications.notify("T", "b", null);
        assertThat(notes).hasSize(1);
        assertThat(pushes).isEmpty();
    }
}
