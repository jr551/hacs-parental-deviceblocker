package lol.rowe.blocker;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PhotoBudgetTest {
    @Test
    public void aLargePhotoIsScaledDownToTheLongestEdge() {
        assertArrayEquals(
                new int[] {1280, 960},
                PhotoBudget.fit(4000, 3000, PhotoBudget.MAX_EDGE));
        assertArrayEquals(
                new int[] {960, 1280},
                PhotoBudget.fit(3000, 4000, PhotoBudget.MAX_EDGE));
    }

    @Test
    public void aPhotoThatIsAlreadySmallEnoughKeepsItsSize() {
        assertArrayEquals(
                new int[] {1280, 960},
                PhotoBudget.fit(1280, 960, PhotoBudget.MAX_EDGE));
        assertArrayEquals(
                new int[] {800, 600},
                PhotoBudget.fit(800, 600, PhotoBudget.MAX_EDGE));
        assertArrayEquals(
                new int[] {1, 600},
                PhotoBudget.fit(0, 600, PhotoBudget.MAX_EDGE));
    }

    @Test
    public void theScaledPhotoKeepsItsAspectRatio() {
        int[] size = PhotoBudget.fit(4032, 3024, PhotoBudget.MAX_EDGE);

        assertArrayEquals(new int[] {1280, 960}, size);
        assertTrue(Math.abs((double) size[0] / size[1] - (double) 4032 / 3024) < 0.01);
    }

    @Test
    public void aLargePhotoStepsDownUntilItFitsTheTarget() throws Exception {
        List<String> attempts = new ArrayList<>();
        PhotoBudget.Encoder encoder = (width, height, quality) -> {
            attempts.add(width + "x" + height + "@" + quality);
            return new byte[width * height * quality / 100];
        };

        byte[] bytes = PhotoBudget.encodeWithinTarget(4000, 3000, encoder);

        assertTrue(bytes.length <= PhotoBudget.TARGET_BYTES);
        // 1280x960 is over the target at every quality, and so is 960x720 until
        // quality 40: the loop must try each of them in order and stop there.
        assertEquals(
                Arrays.asList(
                        "1280x960@70", "1280x960@60", "1280x960@50", "1280x960@40",
                        "960x720@70", "960x720@60", "960x720@50", "960x720@40"),
                attempts);
        assertEquals(960 * 720 * 40 / 100, bytes.length);
    }

    @Test
    public void aPhotoThatAlreadyFitsIsEncodedOnce() throws Exception {
        List<String> attempts = new ArrayList<>();
        PhotoBudget.Encoder encoder = (width, height, quality) -> {
            attempts.add(width + "x" + height + "@" + quality);
            return new byte[width * height * quality / 100];
        };

        byte[] bytes = PhotoBudget.encodeWithinTarget(400, 300, encoder);

        assertEquals(Collections.singletonList("400x300@70"), attempts);
        assertTrue(bytes.length <= PhotoBudget.TARGET_BYTES);
    }

    @Test
    public void aPhotoSmallerThanTheFallbackEdgeIsNotTriedTwice() throws Exception {
        List<String> attempts = new ArrayList<>();
        PhotoBudget.Encoder encoder = (width, height, quality) -> {
            attempts.add(width + "x" + height + "@" + quality);
            return new byte[width * height * quality / 100];
        };

        PhotoBudget.encodeWithinTarget(800, 600, encoder);

        // 800x600 is under both edges, so the fallback edge adds no new size.
        assertEquals(Arrays.asList("800x600@70", "800x600@60"), attempts);
    }

    @Test
    public void theSmallestAttemptIsSentWhenNothingFits() throws Exception {
        List<Integer> sizes = new ArrayList<>();
        PhotoBudget.Encoder encoder = (width, height, quality) -> {
            int size = width * height * quality / 100;
            sizes.add(size);
            return new byte[size];
        };

        byte[] bytes = PhotoBudget.encodeWithinTarget(4000, 3000, encoder);

        assertEquals(Collections.min(sizes).intValue(), bytes.length);
    }

    @Test
    public void aLargeSyntheticPhotoEndsUpUnderTheTarget() throws Exception {
        // A 12 MP photo whose JPEG size tracks its pixels and quality, the way a
        // real camera's does: the loop has to walk down to the fallback edge.
        List<String> attempts = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        PhotoBudget.Encoder encoder = (width, height, quality) -> {
            attempts.add(width + "x" + height + "@" + quality);
            int size = width * height * quality / 100;
            sizes.add(size);
            return new byte[size];
        };

        byte[] bytes = PhotoBudget.encodeWithinTarget(4000, 3000, encoder);

        assertTrue("encoded " + bytes.length + " bytes", bytes.length <= PhotoBudget.TARGET_BYTES);
        assertEquals(attempts.size(), sizes.size());
        // Every attempt before the chosen one was over the target, so the chosen
        // combination really is the first one that fits.
        for (int index = 0; index < sizes.size() - 1; index++) {
            assertTrue(
                    attempts.get(index) + " was under the target",
                    sizes.get(index) > PhotoBudget.TARGET_BYTES);
        }
        assertTrue(sizes.get(sizes.size() - 1) <= PhotoBudget.TARGET_BYTES);
        assertEquals("960x720@40", attempts.get(attempts.size() - 1));
    }
}
