package dev.homelab.inbox.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Tasks whose running round must end as cancelled ({@code config._cancel}). */
public class Cancellations {

    private final Set<Long> ids = ConcurrentHashMap.newKeySet();

    public void request(long taskId) {
        ids.add(taskId);
    }

    public boolean requested(long taskId) {
        return ids.contains(taskId);
    }

    public void clear(long taskId) {
        ids.remove(taskId);
    }
}
