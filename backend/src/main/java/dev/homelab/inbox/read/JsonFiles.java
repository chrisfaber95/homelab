package dev.homelab.inbox.read;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Small file readers shared by the read routes. */
final class JsonFiles {

    private JsonFiles() {}

    /** helpers.read_json with a null default: the parsed JSON (Map, List, ...), or null when the file is missing or broken. */
    static Object readJson(JsonMapper json, Path file) {
        try {
            return json.readValue(Files.readString(file), Object.class);
        } catch (IOException | JacksonException e) {
            return null;
        }
    }
}
