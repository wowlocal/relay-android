package org.telegram.relay.mattermost.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Built-in Mattermost emoji short names → Unicode. Names not in the table are treated as (possibly
 * custom) emoji and get a {@link TextEntity.Type#CUSTOM_EMOJI} entity.
 *
 * <p>Port of the iOS {@code EmojiTable}; the rows ({@link EmojiTableData}) are generated from
 * emoji-datasource, the dataset Mattermost's own emoji names come from. Each row lists one or more
 * names; the first is the one Mattermost clients send ({@code +1} for 👍, {@code thinking_face} for 🤔).
 */
public final class EmojiTable {
    private EmojiTable() {
    }

    /** A short name with its emoji. */
    public static final class Entry {
        public final String name;
        public final String emoji;

        Entry(String name, String emoji) {
            this.name = name;
            this.emoji = emoji;
        }

        @Override
        public String toString() {
            return name + "=" + emoji;
        }
    }

    private static final class Data {
        /** Row names (space-separated aliases) and emoji, dataset order. */
        final List<String[]> rows = new ArrayList<String[]>();
        final List<String[]> skinToneRows = new ArrayList<String[]>();
        final Map<String, String> table = new HashMap<String, String>(8192);
        final Map<String, String> names = new HashMap<String, String>(8192);
        final List<Entry> allEmoji = new ArrayList<Entry>();

        Data() {
            read(EmojiTableData.ROWS, rows);
            read(EmojiTableData.SKIN_TONE_ROWS, skinToneRows);
            List<String[]> all = new ArrayList<String[]>(rows);
            all.addAll(skinToneRows);
            for (String[] row : all) {
                String[] aliases = row[0].split(" ");
                for (String alias : aliases) {
                    table.put(alias, row[1]);
                }
                if (!names.containsKey(row[1])) {
                    names.put(row[1], aliases[0]);
                }
                String stripped = stripVariationSelector(row[1]);
                if (!names.containsKey(stripped)) {
                    names.put(stripped, aliases[0]);
                }
            }
            Set<String> seen = new HashSet<String>();
            for (String[] row : rows) {
                if (seen.add(row[1])) {
                    allEmoji.add(new Entry(row[0].split(" ")[0], row[1]));
                }
            }
        }

        private static void read(String[] chunks, List<String[]> into) {
            for (String chunk : chunks) {
                int start = 0;
                while (start < chunk.length()) {
                    int newline = chunk.indexOf('\n', start);
                    if (newline < 0) {
                        newline = chunk.length();
                    }
                    String line = chunk.substring(start, newline);
                    start = newline + 1;
                    int tab = line.indexOf('\t');
                    if (tab <= 0) {
                        continue;
                    }
                    StringBuilder emoji = new StringBuilder();
                    for (String hex : line.substring(tab + 1).split(" ")) {
                        emoji.appendCodePoint(Integer.parseInt(hex, 16));
                    }
                    into.add(new String[] {line.substring(0, tab), emoji.toString()});
                }
            }
        }
    }

    private static volatile Data data;

    private static Data data() {
        Data current = data;
        if (current == null) {
            synchronized (EmojiTable.class) {
                current = data;
                if (current == null) {
                    current = new Data();
                    data = current;
                }
            }
        }
        return current;
    }

    static String stripVariationSelector(String emoji) {
        if (emoji.indexOf('\uFE0F') < 0) {
            return emoji;
        }
        return emoji.replace("\uFE0F", "");
    }

    /** The emoji of a short name (case-sensitive; Mattermost names are lowercase), or null. */
    public static String unicode(String name) {
        return name == null ? null : data().table.get(name);
    }

    /**
     * The short name Mattermost clients send for an emoji: the first name of its row ({@code +1} for 👍).
     * Also matches the emoji without its variation selector (U+FE0F).
     */
    public static String name(String emoji) {
        if (emoji == null) {
            return null;
        }
        Map<String, String> names = data().names;
        String name = names.get(emoji);
        return name != null ? name : names.get(stripVariationSelector(emoji));
    }

    /** Every known short name (including aliases and skin tone variants). */
    public static Set<String> allNames() {
        return Collections.unmodifiableSet(data().table.keySet());
    }

    /** Every distinct emoji (without skin tone variants) with its primary short name, in the dataset's order. */
    public static List<Entry> allEmoji() {
        return Collections.unmodifiableList(data().allEmoji);
    }

    /**
     * Autocomplete for {@code :query}: built-in emoji with a short name containing {@code query}
     * (case-insensitive), one entry per emoji with its best matching name. Exact names first, then
     * names starting with the query, then the rest, alphabetically within each group. Skin tone
     * variants are left out.
     */
    public static List<Entry> search(String query, int limit) {
        List<Entry> result = new ArrayList<Entry>();
        if (query == null || query.isEmpty() || limit <= 0) {
            return result;
        }
        String lowered = query.toLowerCase(Locale.ROOT);
        final List<Object[]> matches = new ArrayList<Object[]>();
        Set<String> seen = new HashSet<String>();
        for (String[] row : data().rows) {
            if (seen.contains(row[1])) {
                continue;
            }
            int bestRank = Integer.MAX_VALUE;
            String bestName = null;
            for (String alias : row[0].split(" ")) {
                int rank;
                if (alias.equals(lowered)) {
                    rank = 0;
                } else if (alias.startsWith(lowered)) {
                    rank = 1;
                } else if (alias.contains(lowered)) {
                    rank = 2;
                } else {
                    continue;
                }
                if (rank < bestRank) {
                    bestRank = rank;
                    bestName = alias;
                }
            }
            if (bestName != null) {
                seen.add(row[1]);
                matches.add(new Object[] {bestRank, bestName, row[1]});
            }
        }
        Collections.sort(matches, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] lhs, Object[] rhs) {
                int rank = ((Integer) lhs[0]).compareTo((Integer) rhs[0]);
                return rank != 0 ? rank : ((String) lhs[1]).compareTo((String) rhs[1]);
            }
        });
        for (int i = 0; i < matches.size() && i < limit; i++) {
            result.add(new Entry((String) matches.get(i)[1], (String) matches.get(i)[2]));
        }
        return result;
    }

    public static List<Entry> search(String query) {
        return search(query, 50);
    }
}
