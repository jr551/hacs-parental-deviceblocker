package lol.rowe.blocker;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;

/** Apps a blocked phone may still open, in block-screen order. */
final class AllowedApps {
    static final class Shortcut {
        final String label;
        final String packageName;
        final Drawable icon;

        Shortcut(String label, String packageName, Drawable icon) {
            this.label = label;
            this.packageName = packageName;
            this.icon = icon;
        }
    }

    private static final String[][] CANDIDATES = {
            {"Calls", PolicyDecision.SAMSUNG_DIALER, "com.google.android.dialer", "com.android.dialer"},
            {"WhatsApp", PolicyDecision.WHATSAPP},
            {"Maps", PolicyDecision.GOOGLE_MAPS},
            {"MapMyWalk", PolicyDecision.MAPMYWALK},
            {"Classroom", PolicyDecision.CLASSROOM},
            {"Clock", PolicyDecision.SAMSUNG_CLOCK, "com.google.android.deskclock", "com.android.deskclock"},
            {"Camera", PolicyDecision.SAMSUNG_CAMERA, "com.android.camera2", "com.google.android.GoogleCamera"},
    };

    private AllowedApps() {
    }

    static List<Shortcut> installed(PackageManager packages) {
        List<Shortcut> shortcuts = new ArrayList<>();
        for (String[] candidate : CANDIDATES) {
            String label = candidate[0];
            for (int index = 1; index < candidate.length; index++) {
                String packageName = candidate[index];
                try {
                    if (packages.getLaunchIntentForPackage(packageName) == null) {
                        continue;
                    }
                    shortcuts.add(new Shortcut(
                            label,
                            packageName,
                            packages.getApplicationIcon(packageName)));
                    break;
                } catch (PackageManager.NameNotFoundException ignored) {
                    // Try the next package that provides this app.
                }
            }
        }
        return shortcuts;
    }
}
