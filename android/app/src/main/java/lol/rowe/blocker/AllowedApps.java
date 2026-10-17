package lol.rowe.blocker;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collection;
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

    /** The block-screen grid: how many columns fit, and how wide each cell is. */
    static final class Grid {
        final int columns;
        final int cellWidth;

        private Grid(int columns, int cellWidth) {
            this.columns = columns;
            this.cellWidth = cellWidth;
        }
    }

    /**
     * The grid that keeps every shortcut inside {@code availableWidth}: three or
     * four columns, whichever fits {@code targetCellWidth}, with the cells
     * sharing the width evenly so a long label can never widen the grid past the
     * screen. {@code margin} is the space on each side of a cell.
     */
    static Grid grid(int availableWidth, int targetCellWidth, int margin) {
        int columns = Math.max(3, Math.min(4, availableWidth / Math.max(1, targetCellWidth)));
        int cellWidth = Math.max(1, (availableWidth - columns * 2 * margin) / columns);
        return new Grid(columns, cellWidth);
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

    /**
     * The shortcuts to show on the block screen. Home Assistant's allow-list
     * wins when it is non-empty; an empty list keeps the built-in defaults.
     */
    static List<Shortcut> installed(
            PackageManager packages, Collection<String> allowedFromServer) {
        if (allowedFromServer == null || allowedFromServer.isEmpty()) {
            return installedDefaults(packages);
        }
        List<Shortcut> shortcuts = new ArrayList<>();
        for (String candidate : allowedFromServer) {
            if (candidate == null || candidate.trim().isEmpty()) {
                continue;
            }
            String packageName = candidate.trim();
            try {
                if (packages.getLaunchIntentForPackage(packageName) == null) {
                    continue;
                }
                ApplicationInfo info = packages.getApplicationInfo(packageName, 0);
                shortcuts.add(new Shortcut(
                        packages.getApplicationLabel(info).toString(),
                        packageName,
                        packages.getApplicationIcon(info)));
            } catch (PackageManager.NameNotFoundException ignored) {
                // The parent listed an app this phone does not have.
            }
        }
        return shortcuts;
    }

    private static List<Shortcut> installedDefaults(PackageManager packages) {
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
