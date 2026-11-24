package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/** {@code POST /files}. */
public class FileUploadResponse {
    @SerializedName("file_infos") public List<FileInfo> fileInfos = new ArrayList<FileInfo>();
    /** The {@code client_ids} sent with the upload, in the same order as {@link #fileInfos}. */
    @SerializedName("client_ids") public List<String> clientIds = new ArrayList<String>();
}
