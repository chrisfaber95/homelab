package dev.homelab.inbox.conversation;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The PostgreSQL connection of the conversation store (H-024 M4). The datasource is not an auto-configured bean (the
 * other routes do not need one): the unit sets {@code SPRING_DATASOURCE_URL} (pgjdbc over the unix socket) and
 * {@code SPRING_DATASOURCE_USERNAME}, and one short connection is opened per call (a local socket, no password). The
 * Flyway baseline is applied on first use, so a start never fails because the database is down; the routes answer 503.
 */
@Component
public class ConversationDb {

    /** The store cannot be used right now: no database configured or reachable, or the import has not run. */
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final String url;
    private final String user;
    private volatile boolean migrated;

    public ConversationDb(@Value("${spring.datasource.url:}") String url,
                          @Value("${spring.datasource.username:}") String user) {
        this.url = url.strip();
        this.user = user.strip();
    }

    public Connection open() {
        if (url.isEmpty()) {
            throw new Unavailable("the conversation store has no database (SPRING_DATASOURCE_URL is not set)", null);
        }
        try {
            if (!migrated) {
                synchronized (this) {
                    if (!migrated) {
                        Flyway.configure().dataSource(url, user, null).load().migrate();
                        migrated = true;
                    }
                }
            }
            Connection c = DriverManager.getConnection(url, user, null);
            c.setAutoCommit(true);
            return c;
        } catch (SQLException | RuntimeException e) {
            throw new Unavailable("the conversation store cannot reach its database: " + e.getMessage(), e);
        }
    }
}
