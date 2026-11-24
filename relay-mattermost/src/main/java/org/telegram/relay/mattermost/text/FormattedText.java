package org.telegram.relay.mattermost.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Plain text plus entities (UTF-16 ranges). Immutable. */
public final class FormattedText {
    private final String text;
    private final List<TextEntity> entities;

    public FormattedText(String text, List<TextEntity> entities) {
        this.text = text == null ? "" : text;
        this.entities = entities == null
            ? Collections.<TextEntity>emptyList()
            : Collections.unmodifiableList(new ArrayList<TextEntity>(entities));
    }

    public FormattedText(String text) {
        this(text, null);
    }

    public String getText() {
        return text;
    }

    /** From {@link MarkdownToEntities}: sorted by {@link TextEntity#ORDER}. */
    public List<TextEntity> getEntities() {
        return entities;
    }

    /** The text covered by {@code entity}, clamped to the text. */
    public String substring(TextEntity entity) {
        int lower = Math.max(0, Math.min(entity.getOffset(), text.length()));
        int upper = Math.max(lower, Math.min(entity.getEnd(), text.length()));
        return text.substring(lower, upper);
    }

    /** The entities of one type. */
    public List<TextEntity> entitiesOfType(TextEntity.Type type) {
        List<TextEntity> result = new ArrayList<TextEntity>();
        for (TextEntity entity : entities) {
            if (entity.getType() == type) {
                result.add(entity);
            }
        }
        return result;
    }

    /** Whether some entity is marked {@link TextEntity#isHighlight()} (a mention of the current user, or @channel/@here/@all). */
    public boolean hasHighlight() {
        for (TextEntity entity : entities) {
            if (entity.isHighlight()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof FormattedText)) {
            return false;
        }
        FormattedText other = (FormattedText) object;
        return text.equals(other.text) && entities.equals(other.entities);
    }

    @Override
    public int hashCode() {
        return 31 * text.hashCode() + entities.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                builder.append("\\n");
            } else if (c == '"' || c == '\\') {
                builder.append('\\').append(c);
            } else {
                builder.append(c);
            }
        }
        builder.append("\" ").append(entities);
        return builder.toString();
    }
}
