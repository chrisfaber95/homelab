package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.ConversationNotes;
import dev.homelab.inbox.core.Effects.Notifier;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code helpers.notify}: a notification goes in the conversation in full, with its link, before it is pushed, and the push
 * is cut short. A conversation that fails never stops the push, and a push that fails never fails the caller. The ntfy
 * call itself is behind {@link Push}; in shadow nothing implements it, so nothing is sent.
 */
public class Notifications implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifications.class);
    public static final int PUSH_LIMIT = 300;

    /** One ntfy message: the title, the (already cut) text and the link to open, or null. */
    public interface Push {
        void send(String title, String message, String click) throws IOException;
    }

    private final ConversationNotes notes;
    private final Push push;

    public Notifications(ConversationNotes notes, Push push) {
        this.notes = notes;
        this.push = push;
    }

    @Override
    public void notify(String title, String body, String url, boolean store) {
        notify(title, body, url, store, PUSH_LIMIT);
    }

    @Override
    public void notify(String title, String body, String url, int limit) {
        notify(title, body, url, true, limit);
    }

    private void notify(String title, String body, String url, boolean store, int limit) {
        if (store) {
            try { // before sending: ntfy may fail, and the conversation must not stop ntfy
                notes.note("box", "ntfy", "notification", title + "\n" + body, url, null);
            } catch (RuntimeException e) {
                log.warn("conversation: not stored ({}): notification", e.toString());
            }
        }
        try {
            push.send(title, body.length() > limit ? body.substring(0, limit) : body, url);
        } catch (IOException | RuntimeException e) {
            log.warn("melding niet verstuurd ({}): {}", e.toString(), title);
        }
    }
}
