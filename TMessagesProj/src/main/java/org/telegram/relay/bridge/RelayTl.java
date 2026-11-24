package org.telegram.relay.bridge;

import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** TL helpers for the bridge: wire round trips, raw vectors, errors, method names. */
public final class RelayTl {
    private RelayTl() {
    }

    /** A {@code Vector<T>} response written as the server would: {@code 0x1cb5c415, count, items…}. */
    public static final class RawVector extends TLObject {
        public final List<? extends TLObject> items;

        public RawVector(List<? extends TLObject> items) {
            this.items = items;
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(Vector.constructor);
            stream.writeInt32(items.size());
            for (TLObject item : items) {
                item.serializeToStream(stream);
            }
        }
    }

    /** A {@code Vector<long>} response. */
    public static final class RawLongVector extends TLObject {
        public final long[] values;

        public RawLongVector(long[] values) {
            this.values = values;
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(Vector.constructor);
            stream.writeInt32(values.length);
            for (long v : values) {
                stream.writeInt64(v);
            }
        }
    }

    public static RawVector emptyVector() {
        return new RawVector(Collections.<TLObject>emptyList());
    }

    public static TLRPC.TL_error error(int code, String text) {
        TLRPC.TL_error error = new TLRPC.TL_error();
        error.code = code;
        error.text = text;
        return error;
    }

    /**
     * A stream for wire-format answers: {@code TLRPC.Message.writeAttachPath} (a local-database field
     * appended after every message) is skipped for it, as the server never sends one.
     */
    public static final class WireData extends SerializedData {
    }

    public static byte[] serialize(TLObject object) {
        SerializedData out = new WireData();
        object.serializeToStream(out);
        byte[] bytes = out.toByteArray();
        out.cleanup();
        return bytes;
    }

    /**
     * Parses {@code response} the way the network path does: serialise it and let the request's own
     * {@code deserializeResponse} read it back, so flags, optional fields and Vector/Bool return types
     * look exactly like a server answer, and nothing the bridge caches is ever aliased.
     */
    public static TLObject roundTrip(TLObject request, TLObject response) {
        byte[] bytes = serialize(response);
        SerializedData in = new SerializedData(bytes);
        try {
            int magic = in.readInt32(true);
            return request.deserializeResponse(in, magic, true);
        } finally {
            in.cleanup();
        }
    }

    /** {@code TL_messages_getDialogs} → {@code messages.getDialogs}, {@code TL_account.getThemes} → {@code account.getThemes}. */
    public static String methodName(Class<?> type) {
        String name = type.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        int dollar = name.lastIndexOf('$');
        String outer = dollar >= 0 ? name.substring(0, dollar) : "";
        String simple = dollar >= 0 ? name.substring(dollar + 1) : name;
        if (simple.startsWith("TL_")) {
            simple = simple.substring(3);
            int underscore = simple.indexOf('_');
            if (underscore > 0) {
                return simple.substring(0, underscore) + "." + simple.substring(underscore + 1);
            }
            return simple;
        }
        if (outer.startsWith("TL_")) {
            return outer.substring(3) + "." + simple;
        }
        return outer.isEmpty() ? simple : outer + "." + simple;
    }

    /** The TL constructor id of a class ({@code public static final int constructor}), or 0. */
    public static int constructorOf(Class<?> type) {
        try {
            Field field = type.getField("constructor");
            return field.getInt(null);
        } catch (Throwable ignore) {
            return 0;
        }
    }

    public static <T> ArrayList<T> list() {
        return new ArrayList<>();
    }
}
