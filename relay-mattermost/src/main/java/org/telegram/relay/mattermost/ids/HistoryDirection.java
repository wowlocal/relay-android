package org.telegram.relay.mattermost.ids;

/** Paging direction relative to a boundary post. */
public enum HistoryDirection {
    /** Towards older posts ({@code before=}), i.e. lower local ids. */
    OLDER,
    /** Towards newer posts ({@code after=}), i.e. higher local ids. */
    NEWER
}
