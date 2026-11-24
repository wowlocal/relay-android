package org.telegram.relay.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.relay.account.RelaySession;
import org.telegram.relay.account.RelaySessionStore;
import org.telegram.relay.auth.RelayAuth;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Settings → About Relay: version, the Mattermost server, the GPL notice of Telegram for Android
 * (Relay is a fork of it) and the licenses of the bundled open-source libraries.
 */
public class RelayAboutActivity extends UniversalFragment {

    private static final int ID_VERSION = 1;
    private static final int ID_SERVER = 2;
    private static final int ID_SERVER_VERSION = 3;
    private static final int ID_SOURCE = 4;
    private static final int ID_LICENSE_BASE = 100;

    /** Name, license, URL of the license or project. */
    private static final String[][] LIBRARIES = {
        {"Telegram for Android", "GPL-2.0-or-later", "https://github.com/DrKLO/Telegram"},
        {"TDLib / tgnet", "BSL-1.0", "https://github.com/tdlib/td"},
        {"AndroidX, Media3 (ExoPlayer)", "Apache-2.0", "https://github.com/androidx/media"},
        {"FFmpeg", "LGPL-2.1-or-later", "https://ffmpeg.org/legal.html"},
        {"WebRTC, tgcalls", "BSD-3-Clause, LGPL-3.0", "https://webrtc.org/support/license"},
        {"BoringSSL", "OpenSSL / ISC", "https://boringssl.googlesource.com/boringssl/+/HEAD/LICENSE"},
        {"Opus, Ogg, opusfile", "BSD-3-Clause", "https://opus-codec.org/license/"},
        {"libvpx, libyuv", "BSD-3-Clause", "https://chromium.googlesource.com/webm/libvpx/+/HEAD/LICENSE"},
        {"dav1d", "BSD-2-Clause", "https://code.videolan.org/videolan/dav1d"},
        {"OpenH264", "BSD-2-Clause", "https://github.com/cisco/openh264"},
        {"rlottie / tlottie", "MIT", "https://github.com/Samsung/rlottie"},
        {"SQLite", "Public domain", "https://sqlite.org/copyright.html"},
        {"Abseil", "Apache-2.0", "https://github.com/abseil/abseil-cpp"},
        {"Boost.Regex", "BSL-1.0", "https://www.boost.org/LICENSE_1_0.txt"},
        {"WAMR", "Apache-2.0", "https://github.com/bytecodealliance/wasm-micro-runtime"},
        {"Gson", "Apache-2.0", "https://github.com/google/gson"},
        {"Guava", "Apache-2.0", "https://github.com/google/guava"},
        {"nv-websocket-client", "Apache-2.0", "https://github.com/TakahikoKawasaki/nv-websocket-client"},
        {"ZXing", "Apache-2.0", "https://github.com/zxing/zxing"},
        {"Markwon", "Apache-2.0", "https://github.com/noties/Markwon"},
        {"JLaTeXMath", "GPL-2.0 with classpath exception", "https://github.com/opencollab/jlatexmath"},
        {"NanoHTTPD", "BSD-3-Clause", "https://github.com/NanoHttpd/nanohttpd"},
        {"mp4parser", "Apache-2.0", "https://github.com/sannies/mp4parser"},
        {"Google Play services, Firebase", "Android SDK License", "https://developer.android.com/studio/terms"},
    };

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        fragmentView = super.createView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        return fragmentView;
    }

    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.RelaySettingsAbout);
    }

    private View headerView;

    private View createHeader(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(0, dp(20), 0, dp(16));

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.mipmap.ic_launcher_round);
        layout.addView(icon, LayoutHelper.createLinear(84, 84, Gravity.CENTER_HORIZONTAL));

        TextView title = new TextView(context);
        title.setText(getString(R.string.AppName));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));

        TextView subtitle = new TextView(context);
        subtitle.setText(getString(R.string.RelayAboutDescription));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        layout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 6, 32, 0));
        return layout;
    }

    public static String versionString() {
        try {
            PackageInfo info = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            return String.format(Locale.US, "%s (%d)", info.versionName, info.versionCode / 10);
        } catch (Exception e) {
            FileLog.e(e);
            return "";
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (headerView == null) {
            headerView = createHeader(getContext());
        }
        items.add(UItem.asCustomShadow(headerView));

        final RelaySession session = RelaySessionStore.get(currentAccount);
        items.add(UItem.asButton(ID_VERSION, getString(R.string.RelayAboutVersion), versionString()));
        if (session != null) {
            items.add(UItem.asButton(ID_SERVER, getString(R.string.RelaySettingsServer), RelayAuth.displayHost(session.serverUrl)));
            if (!TextUtils.isEmpty(session.serverVersion)) {
                items.add(UItem.asButton(ID_SERVER_VERSION, getString(R.string.RelayAboutServerVersion), session.serverVersion));
            }
        }
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.RelayAboutLicenses)));
        for (int i = 0; i < LIBRARIES.length; i++) {
            items.add(UItem.asButton(ID_LICENSE_BASE + i, LIBRARIES[i][0], LIBRARIES[i][1]));
        }
        items.add(UItem.asShadow(getString(R.string.RelayAboutGplNotice)));
        items.add(UItem.asButton(ID_SOURCE, getString(R.string.RelayAboutSourceCode)));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_SERVER) {
            final RelaySession session = RelaySessionStore.get(currentAccount);
            if (session != null) {
                AndroidUtilities.addToClipboard(session.serverUrl);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.copy, getString(R.string.RelayServerCopied)).show();
            }
        } else if (item.id == ID_SOURCE) {
            Browser.openUrl(getParentActivity(), LIBRARIES[0][2]);
        } else if (item.id >= ID_LICENSE_BASE && item.id < ID_LICENSE_BASE + LIBRARIES.length) {
            Browser.openUrl(getParentActivity(), LIBRARIES[item.id - ID_LICENSE_BASE][2]);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
