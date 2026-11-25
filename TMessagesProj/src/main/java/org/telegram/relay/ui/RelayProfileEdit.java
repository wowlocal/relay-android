package org.telegram.relay.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.relay.bridge.RelayProfile;
import org.telegram.relay.mattermost.model.User;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.UItem;

import java.util.ArrayList;

/**
 * Edit Info in Relay mode (A7c): Telegram's name fields plus the Mattermost username, nickname and position
 * (Telegram's "bio" field is the position), saved with one {@code PUT /users/me/patch}. Used by small hooks in
 * {@code UserInfoActivity}.
 */
public final class RelayProfileEdit {
    public final EditTextCell username;
    public final EditTextCell nickname;
    private final int account;
    private User loaded;

    public RelayProfileEdit(Context context, int account, Theme.ResourcesProvider resourcesProvider, Runnable onChange) {
        this.account = account;
        username = new EditTextCell(context, getString(R.string.RelayProfileUsername), false, false, 64, resourcesProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                super.onTextChanged(newText);
                onChange.run();
            }
        };
        username.setDivider(true);
        username.hideKeyboardOnEnter();
        nickname = new EditTextCell(context, getString(R.string.RelayProfileNickname), false, false, 64, resourcesProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                super.onTextChanged(newText);
                onChange.run();
            }
        };
        nickname.hideKeyboardOnEnter();
    }

    /** Loads the Mattermost profile; {@code onLoaded} gets the position for the bio field. */
    public void load(RelayProfile.Callback<User> onLoaded) {
        RelayProfile.loadMe(account, (me, error) -> {
            if (me != null) {
                loaded = me;
                username.setText(me.username);
                nickname.setText(me.nickname);
            }
            onLoaded.done(me, error);
        });
    }

    /** The Mattermost position once loaded, else null. */
    public String loadedPosition() {
        return loaded != null ? loaded.position : null;
    }

    public void fillItems(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.RelayProfileMattermost)));
        items.add(UItem.asCustom(username));
        items.add(UItem.asCustom(nickname));
        items.add(UItem.asShadow(getString(R.string.RelayProfileInfo)));
    }

    public boolean hasChanges() {
        return loaded != null && (!TextUtils.equals(loaded.username, text(username)) || !TextUtils.equals(loaded.nickname, text(nickname)));
    }

    private static String text(EditTextCell cell) {
        return cell.getText() != null ? cell.getText().toString().trim() : "";
    }

    /** Saves everything; {@code done} gets null on success, else the error text (username taken, …). */
    public void save(String first, String last, String position, RelayProfile.Callback<User> done) {
        String newUsername = text(username);
        RelayProfile.saveProfile(account, first, last, position, text(nickname), newUsername.isEmpty() ? null : newUsername, (me, error) -> {
            if (me != null) {
                loaded = me;
                TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
                if (user != null) {
                    user.first_name = me.firstName;
                    user.last_name = me.lastName;
                    user.username = me.username;
                    UserConfig.getInstance(account).setCurrentUser(user);
                    UserConfig.getInstance(account).saveConfig(true);
                    MessagesController.getInstance(account).putUser(user, false);
                }
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.mainUserInfoChanged);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
            } else if (error != null && error.contains("username")) {
                error = getString(R.string.RelayUsernameTaken);
            }
            done.done(me, error);
        });
    }
}
