package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.owpengram.PanicWipeController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/**
 * Two-mode emergency wipe screen.
 *
 * <p>Panic Device removes everything that belongs to this installation.
 * Panic All additionally asks Telegram to delete the account. The local wipe
 * always runs, even when the server request needs a confirmation code.</p>
 */
public class PanicModeActivity extends BaseFragment {

    private static final int COLOR_DANGER = 0xFFE53935;
    private static final int COLOR_WARNING = 0xFFFFA000;

    @Override
    public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString(R.string.PanicModeTitle));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(14), AndroidUtilities.dp(18), AndroidUtilities.dp(24));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView intro = new TextView(context);
        intro.setText(LocaleController.getString(R.string.PanicModeIntro));
        intro.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        intro.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        intro.setLineSpacing(AndroidUtilities.dp(2), 1f);
        content.addView(intro, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView warning = new TextView(context);
        warning.setText(LocaleController.getString(R.string.PanicWarningTitle));
        warning.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        warning.setTypeface(Typeface.DEFAULT_BOLD);
        warning.setTextColor(COLOR_DANGER);
        LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        warningParams.topMargin = AndroidUtilities.dp(16);
        content.addView(warning, warningParams);

        content.addView(createActionCard(context, R.string.PanicDevice, R.string.PanicDeviceInfo, COLOR_DANGER, false),
                cardParams(10));
        content.addView(createActionCard(context, R.string.PanicAll, R.string.PanicAllInfo, COLOR_DANGER, true),
                cardParams(10));

        content.addView(sectionTitle(context, R.string.PanicWhyTitle), titleParams(22));
        content.addView(body(context, R.string.PanicWhy), bodyParams());

        content.addView(body(context, R.string.PanicEncryptionNote), bodyParams());
        content.addView(body(context, R.string.PanicServerNote), bodyParams());

        fragmentView = scrollView;
        return fragmentView;
    }

    private LinearLayout.LayoutParams cardParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = AndroidUtilities.dp(topMargin);
        return params;
    }

    private LinearLayout.LayoutParams titleParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = AndroidUtilities.dp(topMargin);
        return params;
    }

    private LinearLayout.LayoutParams bodyParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = AndroidUtilities.dp(8);
        return params;
    }

    private TextView sectionTitle(Context context, int resId) {
        TextView view = new TextView(context);
        view.setText(LocaleController.getString(resId));
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        return view;
    }

    private TextView body(Context context, int resId) {
        TextView view = new TextView(context);
        view.setText(LocaleController.getString(resId));
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        view.setLineSpacing(AndroidUtilities.dp(3), 1f);
        return view;
    }

    private View createActionCard(Context context, int titleRes, int infoRes, int accentColor, boolean all) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        card.setClickable(true);
        card.setFocusable(true);

        GradientDrawable background = new GradientDrawable();
        background.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        background.setCornerRadius(AndroidUtilities.dp(14));
        background.setStroke(AndroidUtilities.dp(1), (accentColor & 0x00FFFFFF) | 0x55000000);
        card.setBackground(background);

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(titleRes));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(accentColor);
        card.addView(title);

        TextView info = new TextView(context);
        info.setText(LocaleController.getString(infoRes));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        info.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        info.setLineSpacing(AndroidUtilities.dp(2), 1f);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoParams.topMargin = AndroidUtilities.dp(6);
        card.addView(info, infoParams);

        card.setOnClickListener(v -> showConfirm(all));
        return card;
    }

    private void showConfirm(boolean all) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourceProvider);
        builder.setTitle(LocaleController.getString(all ? R.string.PanicAll : R.string.PanicDevice));
        builder.setMessage(LocaleController.getString(R.string.PanicConfirmMessage));
        builder.setPositiveButton(LocaleController.getString(R.string.PanicConfirm), (dialog, which) -> runPanic(activity, all));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void runPanic(Activity activity, boolean all) {
        FrameLayout progressView = new FrameLayout(activity);
        ProgressBar progressBar = new ProgressBar(activity);
        progressView.addView(progressBar, new FrameLayout.LayoutParams(
                AndroidUtilities.dp(44), AndroidUtilities.dp(44), Gravity.CENTER));

        TextView label = new TextView(activity);
        label.setText(LocaleController.getString(R.string.PanicWorking));
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        label.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        label.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        labelParams.topMargin = AndroidUtilities.dp(76);
        progressView.addView(label, labelParams);

        AlertDialog progressDialog = new AlertDialog.Builder(activity, resourceProvider)
                .setView(progressView)
                .create();
        progressDialog.setCancelable(false);
        progressDialog.show();

        new Thread(() -> {
            final boolean serverRequested = PanicWipeController.wipe(activity.getApplicationContext(), currentAccount, all);
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    if (progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                } catch (Throwable ignore) {
                }
                if (activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
                    return;
                }
                try {
                    MessagesController.getInstance(currentAccount).performLogout(0);
                } catch (Throwable ignore) {
                }
                showFinished(activity, all, serverRequested);
            });
        }, "panic-wipe").start();
    }

    private void showFinished(Activity activity, boolean all, boolean serverRequested) {
        String message = LocaleController.getString(all ? R.string.PanicDoneAll : R.string.PanicDoneDevice);
        if (all && !serverRequested) {
            message += "\n\n" + LocaleController.getString(R.string.PanicServerRequestFailed);
        }
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourceProvider);
            builder.setTitle(LocaleController.getString(R.string.PanicDoneTitle));
            builder.setMessage(message);
            builder.setPositiveButton(LocaleController.getString(R.string.PanicUninstall), (dialog, which) -> uninstall(activity));
            builder.setNegativeButton(LocaleController.getString(R.string.PanicClose), null);
            builder.show();
        } catch (Throwable e) {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        }
    }

    private void uninstall(Activity activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Throwable e) {
            Toast.makeText(activity, LocaleController.getString(R.string.PanicClose), Toast.LENGTH_SHORT).show();
        }
    }
}
