/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2020.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.android.billingclient.api.ProductDetails;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;

public class BuildVars {

    public static boolean DEBUG_VERSION = BuildConfig.DEBUG_VERSION;
    public static boolean LOGS_ENABLED = BuildConfig.DEBUG_VERSION;
    public static boolean DEBUG_PRIVATE_VERSION = BuildConfig.DEBUG_PRIVATE_VERSION;
    public static boolean USE_CLOUD_STRINGS = true;
    public static boolean CHECK_UPDATES = true;
    public static boolean NO_SCOPED_STORAGE = Build.VERSION.SDK_INT <= 29;
    public static String BUILD_VERSION_STRING = BuildConfig.BUILD_VERSION_STRING;
    public static int OWPENGRAM_BUILD = BuildConfig.OWPENGRAM_BUILD;

    /**
     * Human-readable release version for the numeric OWPENGRAM_BUILD code.
     *
     * Legacy releases used a plain number (O19 = 19). New releases use a
     * major.letter pair encoded as major * 100 + letter index: O20.a = 2001,
     * O20.z = 2026, O21.a = 2101. Keeping the code numeric preserves Android's
     * monotonically increasing versionCode while the tag and UI can use a
     * letter for follow-up releases within the same major.
     */
    public static String getOwpenGramVersion() {
        final int code = OWPENGRAM_BUILD;
        if (code <= 0) {
            return "0";
        }
        if (code < 100) {
            return String.valueOf(code);
        }
        final int letter = code % 100;
        if (letter < 1 || letter > 26) {
            return String.valueOf(code);
        }
        return (code / 100) + "." + (char) ('a' + letter - 1);
    }

    public static int APP_ID = 3893395;
    public static String APP_HASH = "e2adbe11ee0f092ced6043c4e051fdda";

    // SafetyNet key for Google Identity SDK, set it to empty to disable
    public static String SAFETYNET_KEY = "AIzaSyDqt8P-7F7CPCseMkOiVRgb1LY8RN1bvH8";
    public static String PLAYSTORE_APP_URL = "https://play.google.com/store/apps/details?id=org.telegram.messenger";
    public static String HUAWEI_STORE_URL = "https://appgallery.huawei.com/app/C101184875";
    public static String GOOGLE_AUTH_CLIENT_ID = "760348033671-81kmi3pi84p11ub8hp9a1funsv0rn2p9.apps.googleusercontent.com";

    public static String HUAWEI_APP_ID = "101184875";

    // OwpenGram has no FCM push infra — keep MTProto alive in background via foreground service.
    public static boolean FORCE_BACKGROUND_WORK = false;

    // You can use this flag to disable Google Play Billing (If you're making fork and want to be in Google Play)
    public static boolean IS_BILLING_UNAVAILABLE = false;

    // works only on official app ids, disable on your forks
    public static boolean SUPPORTS_PASSKEYS = true;

    static {
        if (ApplicationLoader.applicationContext != null) {
            SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("systemConfig", Context.MODE_PRIVATE);
            // Debug builds log by default, but the explicit preference must win so
            // the user can turn logging off and keep it off across restarts.
            LOGS_ENABLED = sharedPreferences.getBoolean("logsEnabled", DEBUG_VERSION);
        }
        // The handler must be installed even when this class happens to be loaded before
        // ApplicationLoader.onCreate() has stored the context: the old version skipped
        // installation forever in that case, which is why some crashes left no trace.
        // dumpCrashToFile() re-reads the context at crash time and bails out safely if it
        // is still missing.
        final Thread.UncaughtExceptionHandler pastHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
            if (LOGS_ENABLED) {
                FileLog.fatal(exception, false);
            }
            dumpCrashToFile(thread, exception);
            if (pastHandler != null) {
                pastHandler.uncaughtException(thread, exception);
            }
        });
    }

    /**
     * Release builds run with logging disabled, so a crash used to leave no usable trace.
     * Always write the last crash to the app's external files dir, which can be read
     * without root: Android/data/messanger.ee.owpengram/files/crash-last.txt
     */
    private static void dumpCrashToFile(Thread thread, Throwable exception) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            StringWriter buffer = new StringWriter();
            PrintWriter writer = new PrintWriter(buffer);
            writer.println("thread: " + (thread != null ? thread.getName() : "unknown"));
            writer.println("time: " + new Date());
            writer.println("build: " + BUILD_VERSION_STRING + " (O" + getOwpenGramVersion() + ")");
            writer.println("device: " + Build.MANUFACTURER + " " + Build.MODEL
                    + ", Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
            writer.println();
            exception.printStackTrace(writer);
            writer.flush();
            byte[] data = buffer.toString().getBytes(StandardCharsets.UTF_8);
            // Always keep a copy in internal storage: Android/data is not reachable
            // from a file manager on newer releases, and Settings -> Logging reads
            // the crash from here so it can be copied out of the app.
            writeCrashFile(new File(context.getFilesDir(), "crash-last.txt"), data);
            File external = context.getExternalFilesDir(null);
            if (external != null) {
                writeCrashFile(new File(external, "crash-last.txt"), data);
            }
        } catch (Throwable ignored) {
            // Never let crash reporting crash the crash handler.
        }
    }

    private static void writeCrashFile(File file, byte[] data) {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(data);
            output.getFD().sync();
        } catch (Throwable ignored) {
        }
    }

    public static boolean useInvoiceBilling() {
        return BillingController.billingClientEmpty || DEBUG_VERSION && false || ApplicationLoader.isStandaloneBuild() || isBetaApp() && false || isHuaweiStoreApp() || hasDirectCurrency();
    }

    private static boolean hasDirectCurrency() {
        if (!BillingController.getInstance().isReady() || BillingController.PREMIUM_PRODUCT_DETAILS == null) {
            return false;
        }
        for (ProductDetails.SubscriptionOfferDetails offerDetails : BillingController.PREMIUM_PRODUCT_DETAILS.getSubscriptionOfferDetails()) {
            for (ProductDetails.PricingPhase phase : offerDetails.getPricingPhases().getPricingPhaseList()) {
                for (String cur : MessagesController.getInstance(UserConfig.selectedAccount).directPaymentsCurrency) {
                    if (Objects.equals(phase.getPriceCurrencyCode(), cur)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Boolean betaApp;
    public static boolean isBetaApp() {
        if (betaApp == null) {
            betaApp = ApplicationLoader.applicationContext != null && "org.telegram.messenger.beta".equals(ApplicationLoader.applicationContext.getPackageName());
        }
        return betaApp;
    }


    public static boolean isHuaweiStoreApp() {
        return ApplicationLoader.isHuaweiStoreBuild();
    }

    public static String getSmsHash() {
        return ApplicationLoader.isStandaloneBuild() ? "w0lkcmTZkKh" : (DEBUG_VERSION ? "O2P2z+/jBpJ" : "oLeq9AcOZkT");
    }
}
