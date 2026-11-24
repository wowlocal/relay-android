package org.telegram.relay.mattermost;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/** Recorded server fixtures (iOS infra/dev-server/fixtures, Mattermost 11.7.14) on the test classpath. */
public final class Fixtures {
    private Fixtures() {
    }

    public static String text(String path) {
        InputStream in = Fixtures.class.getClassLoader().getResourceAsStream("fixtures/" + path);
        if (in == null) {
            throw new IllegalArgumentException("missing fixture " + path);
        }
        try {
            return new String(MattermostClient.readFully(in), Charset.forName("UTF-8"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String rest(String name) {
        return text("rest/" + name + ".json");
    }

    public static String ws(String name) {
        return text("ws/" + name + ".json");
    }

    public static JsonObject manifest() {
        return JsonParser.parseString(text("manifest.json")).getAsJsonObject();
    }

    /** The manifest entry of a REST fixture file ({@code rest/<name>.json}). */
    public static JsonObject manifestEntry(String name) {
        JsonArray rest = manifest().getAsJsonArray("rest");
        for (JsonElement element : rest) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("file").getAsString().equals("rest/" + name + ".json")) {
                return entry;
            }
        }
        throw new IllegalArgumentException("no manifest entry for " + name);
    }
}
