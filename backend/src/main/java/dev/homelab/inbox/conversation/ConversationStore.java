package dev.homelab.inbox.conversation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The conversation between Chris and the box (H-016), stored in PostgreSQL (H-024 M4): the tables {@code conversation}
 * and {@code conversation_refusals} and the {@code switch:*} keys of {@code kv}. A port of
 * {@code bin/homelab_inbox/conversation.py}: same authors and kinds, same scrubbing, same permission check and refusal
 * log. This class is the only writer of these tables once the import has run (the marker row, see {@link #MARKER}).
 */
@Service
public class ConversationStore {

    /** Written by the import in the same transaction as the data: until it is there, no call touches the tables. */
    public static final String MARKER = "store:conversation";

    public static final List<String> AUTHORS = List.of("chris", "claude", "box", "system");
    public static final List<String> KINDS =
            List.of("message", "question", "answer", "notification", "approval_request", "approval");
    public static final List<String> SWITCHES = List.of("discord", "discord_approvals", "kitt");
    /** What a client may append, and the permission that takes: a client always writes as Chris. */
    public static final Map<String, String> CLIENT_KINDS = Map.of("message", "write", "question", "ask", "answer", "answer");
    public static final int MAX_TEXT = 20000;
    public static final String REDACTED = "[removed: looks like a secret]";

    /** {@code config.SECRET_RE} of the Python inbox (the memory's secret filter); Unicode classes like Python's. */
    static final Pattern SECRET = Pattern.compile(
            "gh[pousr]_[A-Za-z0-9]{20,}|github_pat_\\w{20,}|sk-ant-[\\w-]{10,}|sk-[A-Za-z0-9]{20,}|xox[abprs]-[\\w-]{10,}"
                    + "|AKIA[0-9A-Z]{16}|tskey-[\\w-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----"
                    + "|eyJ[\\w-]{10,}\\.[\\w-]{10,}\\.[\\w-]{5,}"
                    + "|(?i:bearer)\\s+[\\w.~+/-]{16,}=*"
                    + "|(?i:token|password|passwd|secret|api[_-]?key|wachtwoord)\\s*[:=]\\s*\\S{8,}",
            Pattern.UNICODE_CHARACTER_CLASS);

    private static final Logger LOG = LoggerFactory.getLogger(ConversationStore.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");
    private static final String COLUMNS = "id, time, author, client, kind, text, link, ref";

    /** The caller sent something the store refuses (unknown author or kind, empty text): HTTP 400. */
    public static final class Invalid extends RuntimeException {
        public Invalid(String message) {
            super(message);
        }
    }

    private final ConversationDb db;
    private final Clients clients;

    public ConversationStore(ConversationDb db, Clients clients) {
        this.db = db;
        this.clients = clients;
    }

    public static String scrub(String text) {
        return SECRET.matcher(text == null ? "" : text).replaceAll(java.util.regex.Matcher.quoteReplacement(REDACTED));
    }

    static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(TIME);
    }

    private static String cut(String text, int max) {
        return text.codePointCount(0, text.length()) <= max ? text : text.substring(0, text.offsetByCodePoints(0, max));
    }

    /** A connection to a store whose import has run; anything else is {@link ConversationDb.Unavailable}. */
    private Connection open() {
        Connection c = db.open();
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kv WHERE key = ?")) {
            ps.setString(1, MARKER);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    c.close();
                    throw new ConversationDb.Unavailable(
                            "the conversation store is not imported yet (run import-conversation, docs/inbox-backend.md)", null);
                }
            }
            return c;
        } catch (SQLException e) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // nothing more to do for a connection that is already broken
            }
            throw new ConversationDb.Unavailable("the conversation store failed: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        for (String k : List.of("time", "author", "client", "kind", "text", "link", "ref")) {
            m.put(k, rs.getString(k));
        }
        return m;
    }

    /** Add an entry; the text is scrubbed first. */
    public Map<String, Object> append(String author, String client, String kind, String text, String link, String ref) {
        if (!AUTHORS.contains(author) || !KINDS.contains(kind)) {
            throw new Invalid("unknown author or kind: " + author + "/" + kind);
        }
        String clean = cut(scrub(String.valueOf(text).strip()), MAX_TEXT);
        if (clean.isEmpty()) {
            throw new Invalid("the text is empty");
        }
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO conversation (time, author, client, kind, text, link, ref) VALUES (?,?,?,?,?,?,?) RETURNING "
                        + COLUMNS)) {
            ps.setString(1, now());
            ps.setString(2, author);
            ps.setString(3, client);
            ps.setString(4, kind);
            ps.setString(5, clean);
            ps.setString(6, link == null || link.isEmpty() ? null : scrub(link));
            ps.setString(7, ref);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return row(rs);
            }
        } catch (SQLException e) {
            throw new ConversationDb.Unavailable("the conversation store failed: " + e.getMessage(), e);
        }
    }

    /** Entries with an id above {@code after}, oldest first; the limit is 1 to 1000. */
    public List<Map<String, Object>> read(long after, long limit) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLUMNS + " FROM conversation WHERE id > ? ORDER BY id LIMIT ?")) {
            ps.setLong(1, Math.max(0, after));
            ps.setLong(2, Math.max(1, Math.min(limit, 1000)));
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(row(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new ConversationDb.Unavailable("the conversation store failed: " + e.getMessage(), e);
        }
    }

    /** The refusal log, newest first. */
    public List<Map<String, Object>> refusals(long limit) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, time, client, action, why FROM conversation_refusals ORDER BY id DESC LIMIT ?")) {
            ps.setLong(1, Math.max(1, limit));
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    for (String k : List.of("time", "client", "action", "why")) {
                        m.put(k, rs.getString(k));
                    }
                    out.add(m);
                }
            }
            return out;
        } catch (SQLException e) {
            throw new ConversationDb.Unavailable("the conversation store failed: " + e.getMessage(), e);
        }
    }

    /** True unless the switch was turned off. */
    public boolean switchOn(String name) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("SELECT value FROM kv WHERE key = ?")) {
            ps.setString(1, "switch:" + name);
            try (ResultSet rs = ps.executeQuery()) {
                return !(rs.next() && "off".equals(rs.getString(1)));
            }
        } catch (SQLException e) {
            throw new ConversationDb.Unavailable("the conversation store failed: " + e.getMessage(), e);
        }
    }

    public void setSwitch(String name, boolean on) {
        if (!SWITCHES.contains(name)) {
            throw new Invalid("unknown switch: " + name);
        }
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value")) {
            ps.setString(1, "switch:" + name);
            ps.setString(2, on ? "on" : "off");
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ConversationDb.Unavailable("the conversation store failed: " + e.getMessage(), e);
        }
    }

    /** What clients may know: whether Discord is on, and whether approvals from Discord are on (off when Discord is). */
    public Map<String, Object> state() {
        boolean on = switchOn("discord");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("discord", on);
        m.put("discord_approvals", on && switchOn("discord_approvals"));
        return m;
    }

    /** Why a switch blocks this client (and action), or null. kitt's approvals are governed by its permission list alone. */
    public String switchedOff(String client, String action) {
        if ("kitt".equals(client)) {
            return switchOn("kitt") ? null : "Kitt is switched off on the homelab page";
        }
        if (!"discord".equals(client)) {
            return null;
        }
        if (!switchOn("discord")) {
            return "Discord is switched off on the homelab page";
        }
        if ("approve".equals(action) && !switchOn("discord_approvals")) {
            return "approvals from Discord are off";
        }
        return null;
    }

    /** True when the client has the permission; otherwise false, and the refusal is logged (journal and table). */
    public boolean allowed(String client, String action) {
        String why;
        Map<String, Set<String>> all = clients.all();
        if (Clients.FORBIDDEN.contains(action)) {
            why = "chat clients can never do this";
        } else if (Clients.PERMISSIONS.contains(action) && all.getOrDefault(client, Set.of()).contains(action)) {
            why = switchedOff(client, action);
            if (why == null) {
                return true;
            }
        } else {
            why = !all.containsKey(client) ? "unknown client" : "no permission '" + action + "'";
        }
        LOG.info("conversation: refused {}: {} ({})", client, action, why);
        refuse(client, action, why);
        return false;
    }

    public void refuse(String client, String action, String why) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO conversation_refusals (time, client, action, why) VALUES (?,?,?,?)")) {
            ps.setString(1, now());
            ps.setString(2, cut(String.valueOf(client), 40));
            ps.setString(3, cut(String.valueOf(action), 40));
            ps.setString(4, why);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ConversationDb.Unavailable("the conversation store failed: " + e.getMessage(), e);
        }
    }
}
