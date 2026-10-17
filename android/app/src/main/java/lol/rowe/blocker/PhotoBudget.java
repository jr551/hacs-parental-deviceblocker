package lol.rowe.blocker;

/**
 * The size budget for the challenge photo. A chore photo only has to be
 * recognisable, so the phone keeps it far below Home Assistant's 5 MiB cap:
 * the largest edge at the highest quality first, then lower qualities, then a
 * smaller edge.
 */
final class PhotoBudget {
    /** The longest edge of the uploaded photo. */
    static final int MAX_EDGE = 1280;
    /** The smaller edge tried when even the lowest quality is too big. */
    static final int FALLBACK_EDGE = 960;
    /** What the phone aims for; Home Assistant's own cap is the backstop. */
    static final int TARGET_BYTES = 300 * 1024;
    /** JPEG qualities tried in order, highest first. */
    static final int[] QUALITIES = {70, 60, 50, 40};

    /** Encodes one candidate; the real one scales and compresses a bitmap. */
    interface Encoder {
        byte[] encode(int width, int height, int quality) throws Exception;
    }

    private PhotoBudget() {
    }

    /**
     * The first encoding that fits {@link #TARGET_BYTES}: {@link #MAX_EDGE} at
     * the highest quality, then lower qualities, then {@link #FALLBACK_EDGE}.
     * The smallest attempt is returned when nothing fits, so the caller always
     * has something to send.
     */
    static byte[] encodeWithinTarget(int width, int height, Encoder encoder) throws Exception {
        byte[] smallest = null;
        int[] previous = null;
        for (int edge : new int[] {MAX_EDGE, FALLBACK_EDGE}) {
            int[] size = fit(width, height, edge);
            if (previous != null && previous[0] == size[0] && previous[1] == size[1]) {
                // A photo already smaller than the fallback edge has one size only.
                continue;
            }
            previous = size;
            for (int quality : QUALITIES) {
                byte[] bytes = encoder.encode(size[0], size[1], quality);
                if (smallest == null || bytes.length < smallest.length) {
                    smallest = bytes;
                }
                if (bytes.length <= TARGET_BYTES) {
                    return bytes;
                }
            }
        }
        return smallest;
    }

    /**
     * The size to scale a photo to so its longest edge is at most {@code edge}.
     * A photo that is already small enough keeps its own size.
     */
    static int[] fit(int width, int height, int edge) {
        if (width <= 0 || height <= 0 || edge <= 0) {
            return new int[] {Math.max(1, width), Math.max(1, height)};
        }
        int longest = Math.max(width, height);
        if (longest <= edge) {
            return new int[] {width, height};
        }
        double scale = (double) edge / longest;
        return new int[] {
                Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale)),
        };
    }
}
