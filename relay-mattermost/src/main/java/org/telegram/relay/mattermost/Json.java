package org.telegram.relay.mattermost;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The shared Gson instance and lenient JSON helpers.
 *
 * <p>Mattermost is mostly consistent, but some fields change type between versions (numbers sent
 * as strings, {@code null} instead of {@code ""}). The adapters registered here never fail on such
 * values: strings decode {@code null} as {@code ""} and accept numbers/booleans, numbers accept
 * numeric strings and fall back to 0, booleans accept {@code "true"}/{@code 1}. Boxed
 * {@code Long}/{@code Integer}/{@code Boolean} fields keep {@code null} for a JSON {@code null};
 * {@code List}/{@code Map} fields decode {@code null} as empty collections.
 */
public final class Json {
    private Json() {
    }

    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(String.class, new LenientString())
            .registerTypeAdapter(long.class, new LenientLong())
            .registerTypeAdapter(Long.class, new LenientLong().nullSafe())
            .registerTypeAdapter(int.class, new LenientInt())
            .registerTypeAdapter(Integer.class, new LenientInt().nullSafe())
            .registerTypeAdapter(boolean.class, new LenientBoolean())
            .registerTypeAdapter(Boolean.class, new LenientBoolean().nullSafe())
            .registerTypeAdapterFactory(new EmptyCollectionsForNull())
            .disableHtmlEscaping()
            .create();

    public static <T> T fromJson(String json, Class<T> type) throws MattermostException {
        try {
            T value = GSON.fromJson(json, type);
            if (value == null) {
                throw MattermostException.decoding("empty response for " + type.getSimpleName(), null);
            }
            return value;
        } catch (JsonParseException e) {
            throw MattermostException.decoding("cannot decode " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    public static <T> T fromJson(String json, Type type) throws MattermostException {
        try {
            T value = GSON.fromJson(json, type);
            if (value == null) {
                throw MattermostException.decoding("empty response for " + type, null);
            }
            return value;
        } catch (JsonParseException e) {
            throw MattermostException.decoding("cannot decode " + type + ": " + e.getMessage(), e);
        }
    }

    public static <T> T fromJson(JsonElement json, Class<T> type) throws MattermostException {
        try {
            T value = GSON.fromJson(json, type);
            if (value == null) {
                throw MattermostException.decoding("empty value for " + type.getSimpleName(), null);
            }
            return value;
        } catch (JsonParseException e) {
            throw MattermostException.decoding("cannot decode " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /** A JSON array as a list; {@code null} decodes as an empty list. */
    public static <T> List<T> listFromJson(String json, Class<T> elementType) throws MattermostException {
        Type type = TypeToken.getParameterized(List.class, elementType).getType();
        try {
            List<T> value = GSON.fromJson(json, type);
            if (value == null) {
                return new ArrayList<T>();
            }
            value.removeAll(Collections.singleton(null));
            return value;
        } catch (JsonParseException e) {
            throw MattermostException.decoding("cannot decode list of " + elementType.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /** Decodes {@code value} that may hold JSON directly or as a JSON-encoded string (WebSocket payloads). */
    public static <T> T decodeEmbedded(JsonElement value, Class<T> type) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        try {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String text = value.getAsString();
                if (text.isEmpty()) {
                    return null;
                }
                return GSON.fromJson(text, type);
            }
            return GSON.fromJson(value, type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Like {@link #decodeEmbedded(JsonElement, Class)} for a generic type. */
    public static <T> T decodeEmbedded(JsonElement value, Type type) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        try {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String text = value.getAsString();
                if (text.isEmpty()) {
                    return null;
                }
                return GSON.fromJson(text, type);
            }
            return GSON.fromJson(value, type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static JsonElement parse(String text) throws MattermostException {
        try {
            return JsonParser.parseString(text);
        } catch (JsonParseException e) {
            throw MattermostException.decoding("invalid JSON: " + e.getMessage(), e);
        }
    }

    /** Parses {@code text}, returning null when it is not a JSON object. */
    public static JsonObject parseObjectOrNull(String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    // MARK: - Field helpers

    public static String string(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonPrimitive()) {
            return value.getAsString();
        }
        return value.toString();
    }

    public static String string(JsonObject object, String key, String fallback) {
        String value = string(object, key);
        return value != null ? value : fallback;
    }

    public static long longValue(JsonObject object, String key, long fallback) {
        if (object == null) {
            return fallback;
        }
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        try {
            if (primitive.isNumber()) {
                return primitive.getAsNumber().longValue();
            }
            if (primitive.isString()) {
                String text = primitive.getAsString().trim();
                if (text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
                    return (long) Double.parseDouble(text);
                }
                return Long.parseLong(text);
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return fallback;
    }

    /** {@code null} when the key is missing or not a boolean-like value. */
    public static Boolean booleanValue(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isString()) {
            String text = primitive.getAsString();
            if ("true".equalsIgnoreCase(text)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(text)) {
                return Boolean.FALSE;
            }
        }
        if (primitive.isNumber()) {
            return primitive.getAsNumber().intValue() != 0;
        }
        return null;
    }

    public static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    public static JsonObject object(Object... keyValues) {
        JsonObject object = new JsonObject();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String key = (String) keyValues[i];
            Object value = keyValues[i + 1];
            if (value == null) {
                continue;
            }
            if (value instanceof JsonElement) {
                object.add(key, (JsonElement) value);
            } else if (value instanceof String) {
                object.addProperty(key, (String) value);
            } else if (value instanceof Number) {
                object.addProperty(key, (Number) value);
            } else if (value instanceof Boolean) {
                object.addProperty(key, (Boolean) value);
            } else if (value instanceof Map || value instanceof List) {
                object.add(key, GSON.toJsonTree(value));
            } else {
                object.add(key, GSON.toJsonTree(value));
            }
        }
        return object;
    }

    // MARK: - Lenient adapters

    /**
     * For {@code JsonObject} fields ({@code @JsonAdapter(Json.NullableJsonObject.class)}): {@code null}
     * or a non-object decodes as {@code null} instead of failing (Gson's built-in adapter can't be overridden).
     */
    public static final class NullableJsonObject extends TypeAdapter<JsonObject> {
        public NullableJsonObject() {
        }

        @Override
        public void write(JsonWriter out, JsonObject value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                // Works for string writers and for JsonTreeWriter (toJsonTree), which has no jsonValue().
                com.google.gson.internal.bind.TypeAdapters.JSON_ELEMENT.write(out, value);
            }
        }

        @Override
        public JsonObject read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            JsonElement element = JsonParser.parseReader(in);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        }
    }

    /** {@code null} lists and maps decode as empty ones, so model collections are never null. */
    static final class EmptyCollectionsForNull implements TypeAdapterFactory {
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            final Class<? super T> raw = type.getRawType();
            final boolean isList = raw == List.class || raw == java.util.Collection.class || raw == ArrayList.class;
            final boolean isMap = raw == Map.class || raw == java.util.HashMap.class || raw == java.util.LinkedHashMap.class;
            if (!isList && !isMap) {
                return null;
            }
            final TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
            return new TypeAdapter<T>() {
                @Override
                public void write(JsonWriter out, T value) throws IOException {
                    delegate.write(out, value);
                }

                @Override
                @SuppressWarnings("unchecked")
                public T read(JsonReader in) throws IOException {
                    if (in.peek() == JsonToken.NULL) {
                        in.nextNull();
                        return (T) (isList ? new ArrayList<Object>() : new java.util.LinkedHashMap<Object, Object>());
                    }
                    return delegate.read(in);
                }
            };
        }
    }

    static final class LenientString extends TypeAdapter<String> {
        @Override
        public void write(JsonWriter out, String value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value);
            }
        }

        @Override
        public String read(JsonReader in) throws IOException {
            JsonToken token = in.peek();
            switch (token) {
                case NULL:
                    in.nextNull();
                    return "";
                case BOOLEAN:
                    return Boolean.toString(in.nextBoolean());
                case STRING:
                case NUMBER:
                    return in.nextString();
                default:
                    return JsonParser.parseReader(in).toString();
            }
        }
    }

    static final class LenientLong extends TypeAdapter<Long> {
        @Override
        public void write(JsonWriter out, Long value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.longValue());
            }
        }

        @Override
        public Long read(JsonReader in) throws IOException {
            JsonToken token = in.peek();
            switch (token) {
                case NULL:
                    in.nextNull();
                    return 0L;
                case NUMBER:
                case STRING: {
                    String text = in.nextString().trim();
                    try {
                        return Long.parseLong(text);
                    } catch (NumberFormatException e) {
                        try {
                            return (long) Double.parseDouble(text);
                        } catch (NumberFormatException e2) {
                            return 0L;
                        }
                    }
                }
                case BOOLEAN:
                    return in.nextBoolean() ? 1L : 0L;
                default:
                    in.skipValue();
                    return 0L;
            }
        }
    }

    static final class LenientInt extends TypeAdapter<Integer> {
        private final LenientLong longs = new LenientLong();

        @Override
        public void write(JsonWriter out, Integer value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.intValue());
            }
        }

        @Override
        public Integer read(JsonReader in) throws IOException {
            long value = longs.read(in);
            if (value > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            if (value < Integer.MIN_VALUE) {
                return Integer.MIN_VALUE;
            }
            return (int) value;
        }
    }

    static final class LenientBoolean extends TypeAdapter<Boolean> {
        @Override
        public void write(JsonWriter out, Boolean value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.booleanValue());
            }
        }

        @Override
        public Boolean read(JsonReader in) throws IOException {
            JsonToken token = in.peek();
            switch (token) {
                case NULL:
                    in.nextNull();
                    return Boolean.FALSE;
                case BOOLEAN:
                    return in.nextBoolean();
                case STRING:
                    return "true".equalsIgnoreCase(in.nextString().trim());
                case NUMBER:
                    return in.nextDouble() != 0;
                default:
                    in.skipValue();
                    return Boolean.FALSE;
            }
        }
    }
}
