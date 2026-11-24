package org.telegram.relay.mattermost;

import java.util.ArrayList;
import java.util.List;

/** Collects query parameters, skipping null values. */
public final class QueryBuilder {
    private final List<String[]> items = new ArrayList<String[]>();

    public QueryBuilder add(String name, String value) {
        if (value != null) {
            items.add(new String[]{name, value});
        }
        return this;
    }

    public QueryBuilder add(String name, Integer value) {
        return add(name, value == null ? null : String.valueOf(value));
    }

    public QueryBuilder add(String name, Long value) {
        return add(name, value == null ? null : String.valueOf(value));
    }

    /** Adds {@code name=true|false} when {@code value} is non-null. */
    public QueryBuilder add(String name, Boolean value) {
        return add(name, value == null ? null : (value ? "true" : "false"));
    }

    /** Adds {@code name=true} only when {@code value} is true. */
    public QueryBuilder flag(String name, boolean value) {
        if (value) {
            add(name, "true");
        }
        return this;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** {@code a=1&b=2} (no leading {@code ?}). */
    public String encode() {
        StringBuilder builder = new StringBuilder();
        for (String[] item : items) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(UrlCodec.encodeQueryComponent(item[0])).append('=').append(UrlCodec.encodeQueryComponent(item[1]));
        }
        return builder.toString();
    }
}
