#!/usr/bin/env python3
"""Generates text/EmojiTableData.java from the iOS fork's EmojiTableData.swift.

The Swift file is generated from emoji-datasource 16.0.0 (MIT License, Copyright (c) 2013 Cal
Henderson), the dataset Mattermost's built-in emoji names come from. This keeps the Android table
identical to the iOS one.

Usage: gen_emoji_table.py [path/to/EmojiTableData.swift]
"""
import os
import re
import sys

DEFAULT_SOURCE = os.path.expanduser(
    "~/Developer/relay-calls-integration/ios-device/submodules/MattermostSyncCore/Sources/"
    "MattermostSyncCore/Markdown/EmojiTableData.swift")
OUTPUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "java", "org",
                      "telegram", "relay", "mattermost", "text", "EmojiTableData.java")
ROW = re.compile(r'^        \("([^"]*)", "((?:\\u\{[0-9A-F]+\})+)"\),$')
SCALAR = re.compile(r'\\u\{([0-9A-F]+)\}')
# Each Java string literal must stay below 65535 bytes in the class file.
CHUNK_BYTES = 48000


def parse(path):
    sections = {"rows": [], "skinToneRows": []}
    current = None
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if "static let rows" in line:
                current = "rows"
                continue
            if "static let skinToneRows" in line:
                current = "skinToneRows"
                continue
            match = ROW.match(line.rstrip("\n"))
            if match and current:
                scalars = " ".join(SCALAR.findall(match.group(2)))
                sections[current].append(match.group(1) + "\t" + scalars)
    return sections


def chunks(rows):
    result = []
    current = []
    size = 0
    for row in rows:
        if size + len(row) + 1 > CHUNK_BYTES and current:
            result.append(current)
            current = []
            size = 0
        current.append(row)
        size += len(row) + 1
    if current:
        result.append(current)
    return result


def literal(rows):
    return '"' + "\\n".join(row.replace("\t", "\\t") for row in rows) + '\\n"'


def main():
    source = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SOURCE
    sections = parse(source)
    if len(sections["rows"]) < 1800 or len(sections["skinToneRows"]) < 1000:
        sys.exit("unexpected row counts: %d / %d" % (len(sections["rows"]), len(sections["skinToneRows"])))
    out = []
    out.append("// GENERATED FILE: do not edit. Run relay-mattermost/tools/gen_emoji_table.py.")
    out.append("//")
    out.append("// Source: emoji-datasource 16.0.0 (https://github.com/iamcal/emoji-data), emoji.json, MIT License,")
    out.append("// Copyright (c) 2013 Cal Henderson, by way of the iOS fork's EmojiTableData.swift. Mattermost's")
    out.append("// built-in emoji names come from the same dataset. Each row: space-separated short names (the first")
    out.append("// is the one Mattermost clients send), a tab, the emoji's code points in hex.")
    out.append("//")
    out.append("// The MIT License (MIT)")
    out.append("//")
    out.append("// Copyright (c) 2013 Cal Henderson")
    out.append("//")
    out.append("// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and")
    out.append("// associated documentation files (the \"Software\"), to deal in the Software without restriction,")
    out.append("// including without limitation the rights to use, copy, modify, merge, publish, distribute,")
    out.append("// sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is")
    out.append("// furnished to do so, subject to the following conditions:")
    out.append("//")
    out.append("// The above copyright notice and this permission notice shall be included in all copies or")
    out.append("// substantial portions of the Software.")
    out.append("//")
    out.append("// THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT")
    out.append("// NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND")
    out.append("// NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES")
    out.append("// OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN")
    out.append("// CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.")
    out.append("")
    out.append("package org.telegram.relay.mattermost.text;")
    out.append("")
    out.append("/** The rows of {@link EmojiTable}. */")
    out.append("final class EmojiTableData {")
    out.append("    private EmojiTableData() {")
    out.append("    }")
    out.append("")
    out.append("    /** %d emoji, in the dataset's order. */" % len(sections["rows"]))
    out.append("    static final String[] ROWS = {")
    for chunk in chunks(sections["rows"]):
        out.append("        " + literal(chunk) + ",")
    out.append("    };")
    out.append("")
    out.append("    /** Skin tone variants (names only resolve; not part of {@code allEmoji}). %d variants. */"
               % len(sections["skinToneRows"]))
    out.append("    static final String[] SKIN_TONE_ROWS = {")
    for chunk in chunks(sections["skinToneRows"]):
        out.append("        " + literal(chunk) + ",")
    out.append("    };")
    out.append("}")
    with open(OUTPUT, "w", encoding="utf-8") as handle:
        handle.write("\n".join(out) + "\n")
    print("wrote %s: %d rows, %d skin tone rows" % (OUTPUT, len(sections["rows"]), len(sections["skinToneRows"])))


if __name__ == "__main__":
    main()
