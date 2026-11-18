package dev.homelab.inbox.core;

import java.sql.Connection;

/** Opens one short connection to the PostgreSQL {@code inbox} database; the caller closes it. */
@FunctionalInterface
public interface ConnectionSource {
    Connection open();
}
