package org.telegram.relay.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelaySessionStore;
import org.telegram.relay.auth.RelayAuth;
import org.telegram.relay.mattermost.model.ServerVersion;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.lang.ref.WeakReference;

/**
 * Relay's "server + login" screen. Replaces Telegram's {@code IntroActivity}/{@code LoginActivity}
 * (boot, last logout, add account). Built from Telegram's own login components (outlined
 * {@code EditTextBoldCursor} fields, {@code ButtonWithCounterView}, Telegram alerts) and theme keys.
 *
 * <p>Steps: server address → credentials → MFA code (when the account has MFA). On success it runs
 * {@link RelayAuth#completeLogin} and opens the chat list.
 */
public class RelayLoginActivity extends BaseFragment {
    private static final int STEP_SERVER = 0;
    private static final int STEP_CREDENTIALS = 1;
    private static final int STEP_MFA = 2;

    private static WeakReference<RelayLoginActivity> visible = new WeakReference<>(null);

    private final boolean newAccount;
    private int step = STEP_SERVER;
    private RelayAuth.ServerInfo server;
    private boolean busy;

    private ImageView backButton;
    private LinearLayout[] pages = new LinearLayout[3];

    private OutlineTextContainerView serverOutline;
    private EditTextBoldCursor serverField;
    private ButtonWithCounterView connectButton;
    private TextView serverFooter;

    private TextView loginTitle;
    private TextView loginSubtitle;
    private OutlineTextContainerView loginIdOutline;
    private EditTextBoldCursor loginIdField;
    private OutlineTextContainerView passwordOutline;
    private EditTextBoldCursor passwordField;
    private ButtonWithCounterView loginButton;
    private TextView loginFooter;

    private TextView mfaSubtitle;
    private OutlineTextContainerView mfaOutline;
    private EditTextBoldCursor mfaField;
    private ButtonWithCounterView mfaButton;

    /** The login screen of the active account (boot / after the last logout). */
    public RelayLoginActivity() {
        this.newAccount = false;
    }

    /** Add-account flow for a free slot. */
    public RelayLoginActivity(int account) {
        this.currentAccount = account;
        this.newAccount = true;
    }

    /** Telegram's IntroActivity API: the screen shown after the last logout. */
    public RelayLoginActivity setOnLogout() {
        return this;
    }

    /** The login screen currently on top, if any (debug login hook). */
    public static RelayLoginActivity visibleInstance() {
        return visible.get();
    }

    public int getAccount() {
        return currentAccount;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = root;

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        FrameLayout pagesContainer = new FrameLayout(context);
        scrollView.addView(pagesContainer, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        pages[STEP_SERVER] = buildServerPage(context);
        pages[STEP_CREDENTIALS] = buildCredentialsPage(context);
        pages[STEP_MFA] = buildMfaPage(context);
        for (LinearLayout page : pages) {
            pagesContainer.addView(page, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 0, 0, 0, 0));
        }

        backButton = new ImageView(context);
        backButton.setImageResource(R.drawable.ic_ab_back);
        backButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
        backButton.setContentDescription(LocaleController.getString(R.string.AccDescrGoBack));
        int padding = AndroidUtilities.dp(4);
        backButton.setPadding(padding, padding, padding, padding);
        backButton.setOnClickListener(v -> {
            if (onBackPressed(true)) {
                finishFragment();
            }
        });
        root.addView(backButton, LayoutHelper.createFrame(32, 32, Gravity.LEFT | Gravity.TOP, 16, 16 + AndroidUtilities.statusBarHeight / AndroidUtilities.density, 0, 0));

        showStep(STEP_SERVER, false);
        return fragmentView;
    }

    // MARK: - Pages

    private LinearLayout page(Context context) {
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, AndroidUtilities.statusBarHeight + AndroidUtilities.dp(56), 0, AndroidUtilities.dp(32));
        return page;
    }

    private TextView title(Context context, CharSequence text, int sizeDp) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setGravity(Gravity.CENTER);
        view.setText(text);
        return view;
    }

    private TextView subtitle(Context context, CharSequence text) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        view.setText(text);
        return view;
    }

    private TextView footer(Context context) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private EditTextBoldCursor field(Context context, OutlineTextContainerView outline, String label, String contentDescription, int inputType, int imeAction, Runnable onAction) {
        outline.setText(label);
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setCursorSize(AndroidUtilities.dp(20));
        field.setCursorWidth(1.5f);
        field.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
        field.setBackground(null);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        field.setMaxLines(1);
        field.setSingleLine(true);
        int padding = AndroidUtilities.dp(16);
        field.setPadding(padding, padding, padding, padding);
        field.setInputType(inputType);
        field.setImeOptions(imeAction | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        field.setContentDescription(contentDescription);
        field.setOnFocusChangeListener((v, hasFocus) -> outline.animateSelection(hasFocus ? 1f : 0f));
        field.setOnEditorActionListener((textView, action, event) -> {
            if (action == imeAction) {
                onAction.run();
                return true;
            }
            return false;
        });
        outline.attachEditText(field);
        outline.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        return field;
    }

    private ButtonWithCounterView button(Context context, String text, String contentDescription, Runnable onClick) {
        ButtonWithCounterView button = new ButtonWithCounterView(context, true, null).setRound();
        button.setText(text, false);
        button.setContentDescription(contentDescription);
        button.setOnClickListener(v -> onClick.run());
        return button;
    }

    private View logo(Context context) {
        TextView logo = new TextView(context);
        logo.setText("R");
        logo.setTextColor(0xffffffff);
        logo.setTypeface(AndroidUtilities.bold());
        logo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 52);
        logo.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.BL_TR, new int[]{0xff2a9d8f, 0xff3b5bdb});
        background.setCornerRadius(AndroidUtilities.dp(26));
        logo.setBackground(background);
        return logo;
    }

    private LinearLayout buildServerPage(Context context) {
        LinearLayout page = page(context);
        page.addView(logo(context), LayoutHelper.createLinear(100, 100, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 0));
        page.addView(title(context, LocaleController.getString(R.string.RelayAppName), 26), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 20, 32, 0));
        page.addView(subtitle(context, LocaleController.getString(R.string.RelayServerSubtitle)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 10, 32, 0));

        serverOutline = new OutlineTextContainerView(context);
        serverField = field(context, serverOutline, LocaleController.getString(R.string.RelayServerField), "relay_server_field",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                EditorInfo.IME_ACTION_GO, this::onConnect);
        serverField.setHint(LocaleController.getString(R.string.RelayServerHint));
        String last = RelaySessionStore.lastServerUrl();
        if (!TextUtils.isEmpty(last)) {
            serverField.setText(last);
            serverField.setSelection(serverField.length());
        }
        page.addView(serverOutline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 28, 16, 0));

        connectButton = button(context, LocaleController.getString(R.string.RelayConnect), "relay_connect_button", this::onConnect);
        page.addView(connectButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 20, 16, 0));

        serverFooter = footer(context);
        serverFooter.setText(LocaleController.formatString(R.string.RelayServerFooter, ServerVersion.MINIMUM_SUPPORTED.major + "." + ServerVersion.MINIMUM_SUPPORTED.minor));
        page.addView(serverFooter, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 16, 32, 0));
        return page;
    }

    private LinearLayout buildCredentialsPage(Context context) {
        LinearLayout page = page(context);
        loginTitle = title(context, LocaleController.getString(R.string.RelayLoginTitle), 24);
        page.addView(loginTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 24, 32, 0));
        loginSubtitle = subtitle(context, "");
        page.addView(loginSubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 10, 32, 0));

        loginIdOutline = new OutlineTextContainerView(context);
        loginIdField = field(context, loginIdOutline, LocaleController.getString(R.string.RelayLoginIdField), "relay_login_field",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                EditorInfo.IME_ACTION_NEXT, () -> passwordField.requestFocus());
        page.addView(loginIdOutline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 28, 16, 0));

        passwordOutline = new OutlineTextContainerView(context);
        passwordField = field(context, passwordOutline, LocaleController.getString(R.string.RelayPasswordField), "relay_password_field",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                EditorInfo.IME_ACTION_DONE, this::onLogin);
        passwordField.setTransformationMethod(PasswordTransformationMethod.getInstance());
        page.addView(passwordOutline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        loginButton = button(context, LocaleController.getString(R.string.RelayLogIn), "relay_login_button", this::onLogin);
        page.addView(loginButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 20, 16, 0));

        loginFooter = footer(context);
        page.addView(loginFooter, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 16, 32, 0));
        return page;
    }

    private LinearLayout buildMfaPage(Context context) {
        LinearLayout page = page(context);
        page.addView(title(context, LocaleController.getString(R.string.RelayMfaTitle), 24), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 24, 32, 0));
        mfaSubtitle = subtitle(context, "");
        page.addView(mfaSubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 10, 32, 0));
        mfaOutline = new OutlineTextContainerView(context);
        mfaField = field(context, mfaOutline, LocaleController.getString(R.string.RelayMfaField), "relay_mfa_field",
                InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_DONE, this::onVerifyMfa);
        page.addView(mfaOutline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 28, 16, 0));
        mfaButton = button(context, LocaleController.getString(R.string.RelayVerify), "relay_mfa_button", this::onVerifyMfa);
        page.addView(mfaButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 20, 16, 0));
        return page;
    }

    private void showStep(int newStep, boolean focus) {
        step = newStep;
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == newStep ? View.VISIBLE : View.GONE);
        }
        backButton.setVisibility(newStep != STEP_SERVER || newAccount ? View.VISIBLE : View.GONE);
        if (newStep == STEP_CREDENTIALS && server != null) {
            String host = server.displayHost;
            loginTitle.setText(!TextUtils.isEmpty(server.siteName) ? server.siteName : LocaleController.getString(R.string.RelayLoginTitle));
            loginSubtitle.setText(LocaleController.formatString(R.string.RelayLoginSubtitle, host));
            loginFooter.setText(LocaleController.formatString(R.string.RelayLoginFooter, host, server.version != null ? server.version.toString() : "?"));
        } else if (newStep == STEP_MFA && server != null) {
            mfaSubtitle.setText(LocaleController.formatString(R.string.RelayMfaSubtitle, loginIdField.getText().toString().trim()));
            mfaField.setText("");
        }
        if (focus) {
            EditTextBoldCursor target = newStep == STEP_SERVER ? serverField : newStep == STEP_CREDENTIALS ? (loginIdField.length() == 0 ? loginIdField : passwordField) : mfaField;
            target.requestFocus();
            AndroidUtilities.showKeyboard(target);
        }
    }

    // MARK: - Actions

    private void setBusy(boolean value) {
        busy = value;
        connectButton.setLoading(value && step == STEP_SERVER);
        loginButton.setLoading(value && step == STEP_CREDENTIALS);
        mfaButton.setLoading(value && step == STEP_MFA);
    }

    private void onConnect() {
        if (busy) {
            return;
        }
        String input = serverField.getText().toString();
        if (RelayAuth.normalizeServerUrl(input) == null) {
            fieldError(serverOutline, serverField);
            showError(LocaleController.getString(R.string.RelayErrorInvalidUrlTitle), LocaleController.getString(R.string.RelayErrorInvalidUrl));
            return;
        }
        setBusy(true);
        RelayAuth.checkServer(input, (info, error) -> {
            setBusy(false);
            if (error != null) {
                fieldError(serverOutline, serverField);
                showFailure(error);
                return;
            }
            server = info;
            showStep(STEP_CREDENTIALS, true);
        });
    }

    private void onLogin() {
        if (busy || server == null) {
            return;
        }
        String loginId = loginIdField.getText().toString().trim();
        String password = passwordField.getText().toString();
        if (loginId.isEmpty() || password.isEmpty()) {
            fieldError(loginId.isEmpty() ? loginIdOutline : passwordOutline, loginId.isEmpty() ? loginIdField : passwordField);
            showError(LocaleController.getString(R.string.RelayErrorLoginTitle), LocaleController.getString(R.string.RelayErrorEmptyFields));
            return;
        }
        submit(loginId, password, null);
    }

    private void onVerifyMfa() {
        if (busy) {
            return;
        }
        String code = mfaField.getText().toString().trim();
        if (code.isEmpty()) {
            fieldError(mfaOutline, mfaField);
            return;
        }
        submit(loginIdField.getText().toString().trim(), passwordField.getText().toString(), code);
    }

    private void submit(String loginId, String password, String mfa) {
        setBusy(true);
        RelayAuth.login(currentAccount, server, loginId, password, mfa, (user, error) -> {
            setBusy(false);
            if (error != null) {
                if (error.failure == RelayAuth.Failure.MFA_REQUIRED) {
                    showStep(STEP_MFA, true);
                    return;
                }
                if (error.failure == RelayAuth.Failure.WRONG_MFA) {
                    fieldError(mfaOutline, mfaField);
                } else if (error.failure == RelayAuth.Failure.WRONG_CREDENTIALS) {
                    fieldError(passwordOutline, passwordField);
                    passwordField.setText("");
                }
                showFailure(error);
                return;
            }
            onLoginSuccess(user);
        });
    }

    /** Logged in to Mattermost (UI or debug hook): build the Telegram account and open the chat list. */
    public void onLoginSuccess(TLRPC.TL_user user) {
        RelayConfig.log(RelayConfig.TAG_AUTH, "login complete account=" + currentAccount + " user=" + user.id);
        AndroidUtilities.hideKeyboard(fragmentView);
        RelayAuth.completeLogin(currentAccount, user);
        if (getParentActivity() instanceof LaunchActivity) {
            LaunchActivity launchActivity = (LaunchActivity) getParentActivity();
            if (newAccount) {
                launchActivity.switchToAccount(currentAccount, true, obj -> {
                    MainTabsActivity tabs = new MainTabsActivity();
                    tabs.prepareDialogsActivity(null);
                    return tabs;
                });
                finishFragment();
            } else {
                MainTabsActivity tabs = new MainTabsActivity();
                tabs.prepareDialogsActivity(null);
                presentFragment(tabs, true);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
            }
        }
    }

    // MARK: - Errors

    private void fieldError(OutlineTextContainerView outline, View field) {
        outline.animateError(1f);
        AndroidUtilities.shakeViewSpring(field, 3.5f);
        AndroidUtilities.runOnUIThread(() -> outline.animateError(0f), 2000);
    }

    private void showFailure(RelayAuth.Error error) {
        String host = server != null ? server.displayHost : RelayAuth.displayHost(String.valueOf(RelayAuth.normalizeServerUrl(serverField.getText().toString())));
        switch (error.failure) {
            case INVALID_URL:
                showError(LocaleController.getString(R.string.RelayErrorInvalidUrlTitle), LocaleController.getString(R.string.RelayErrorInvalidUrl));
                break;
            case UNREACHABLE:
                showError(LocaleController.getString(R.string.RelayErrorUnreachableTitle), LocaleController.formatString(R.string.RelayErrorUnreachable, host));
                break;
            case NOT_MATTERMOST:
                showError(LocaleController.getString(R.string.RelayErrorNotMattermostTitle), LocaleController.formatString(R.string.RelayErrorNotMattermost, host));
                break;
            case UNSUPPORTED_VERSION:
                showError(LocaleController.getString(R.string.RelayErrorUnsupportedTitle), LocaleController.formatString(R.string.RelayErrorUnsupported, host, error.detail, ServerVersion.MINIMUM_SUPPORTED.toString()));
                break;
            case WRONG_CREDENTIALS:
                showError(LocaleController.getString(R.string.RelayErrorCredentialsTitle), LocaleController.getString(R.string.RelayErrorCredentials));
                break;
            case WRONG_MFA:
                showError(LocaleController.getString(R.string.RelayErrorMfaTitle), LocaleController.getString(R.string.RelayErrorMfa));
                break;
            default:
                showError(LocaleController.getString(R.string.RelayErrorLoginTitle), error.detail);
                break;
        }
    }

    private void showError(String title, String message) {
        if (getParentActivity() == null) {
            return;
        }
        RelayConfig.log(RelayConfig.TAG_AUTH, "error shown: " + title + " / " + message);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        showDialog(builder.create());
    }

    // MARK: - Fragment

    @Override
    public void onResume() {
        super.onResume();
        visible = new WeakReference<>(this);
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (visible.get() == this) {
            visible = new WeakReference<>(null);
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (step == STEP_MFA) {
            if (invoked) {
                showStep(STEP_CREDENTIALS, true);
            }
            return false;
        }
        if (step == STEP_CREDENTIALS) {
            if (invoked) {
                passwordField.setText("");
                showStep(STEP_SERVER, true);
            }
            return false;
        }
        return newAccount;
    }

    @Override
    public boolean isSwipeBackEnabled(android.view.MotionEvent event) {
        return newAccount && step == STEP_SERVER;
    }

    @Override
    public boolean isLightStatusBar() {
        return ColorUtils.calculateLuminance(Theme.getColor(Theme.key_windowBackgroundWhite, null, true)) > 0.7f;
    }
}
