package com.screenomics;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * State-machine tests for the OMI Glass BLE photo reassembler.
 * Framing: bytes[0..1] = uint16 LE frame index; 0 = start (SOI at byte 2, or
 * byte 3 with an orientation byte at [2]); 0xFFFF = end; else in-order chunk.
 */
public class GlassPhotoAssemblerTest {

    private GlassPhotoAssembler assembler;

    @Before
    public void setUp() {
        assembler = new GlassPhotoAssembler();
    }

    // ---- helpers ----

    private static byte[] frame(int idx, byte[] payload) {
        byte[] f = new byte[2 + payload.length];
        f[0] = (byte) (idx & 0xFF);
        f[1] = (byte) ((idx >> 8) & 0xFF);
        System.arraycopy(payload, 0, f, 2, payload.length);
        return f;
    }

    private static final byte[] END_MARKER = new byte[]{(byte) 0xFF, (byte) 0xFF};

    /** A synthetic JPEG: SOI + deterministic filler + EOI, length > 100. */
    private static byte[] jpeg(int length) {
        byte[] j = new byte[length];
        j[0] = (byte) 0xFF; j[1] = (byte) 0xD8;                      // SOI
        for (int i = 2; i < length - 2; i++) j[i] = (byte) (i % 251);
        j[length - 2] = (byte) 0xFF; j[length - 1] = (byte) 0xD9;    // EOI
        return j;
    }

    /** Feed an image split into chunks of {@code chunkSize}, without the end marker. */
    private void feedImage(byte[] img, int chunkSize, boolean withOrientation, int orientation) {
        int off = 0;
        int idx = 0;
        while (off < img.length) {
            int n = Math.min(chunkSize, img.length - off);
            byte[] payload = Arrays.copyOfRange(img, off, off + n);
            if (idx == 0 && withOrientation) {
                byte[] withOrient = new byte[payload.length + 1];
                withOrient[0] = (byte) orientation;
                System.arraycopy(payload, 0, withOrient, 1, payload.length);
                payload = withOrient;
            }
            assertNull("no image should complete before the end marker",
                    assembler.feed(frame(idx, payload)));
            off += n;
            idx++;
        }
    }

    // ---- tests ----

    @Test
    public void assemblesMultiChunkImage() {
        byte[] img = jpeg(150);
        feedImage(img, 60, false, -1);
        byte[] out = assembler.feed(END_MARKER);
        assertNotNull("end marker should complete the image", out);
        assertArrayEquals(img, out);
        assertEquals("legacy framing carries no orientation", -1, assembler.getOrientation());
    }

    @Test
    public void detectsOrientationByteFraming() {
        byte[] img = jpeg(200);
        feedImage(img, 80, true, 3);
        byte[] out = assembler.feed(END_MARKER);
        assertNotNull(out);
        assertArrayEquals(img, out);
        assertEquals("fw>=2.1.1 framing: byte[2] of frame 0 is the orientation", 3,
                assembler.getOrientation());
    }

    @Test
    public void frameGapDropsImage() {
        byte[] img = jpeg(180);
        assembler.feed(frame(0, Arrays.copyOfRange(img, 0, 60)));
        // skip idx 1 entirely
        assertNull(assembler.feed(frame(2, Arrays.copyOfRange(img, 60, 120))));
        assertNull("gapped image must be dropped, not returned", assembler.feed(END_MARKER));
    }

    @Test
    public void midStreamJoinWaitsForNextStart() {
        byte[] img = jpeg(150);
        // join mid-image: continuation before any frame 0
        assertNull(assembler.feed(frame(5, Arrays.copyOfRange(img, 0, 60))));
        assertNull("nothing buffered -> end marker yields nothing", assembler.feed(END_MARKER));
        // the next complete image still assembles
        feedImage(img, 60, false, -1);
        assertArrayEquals(img, assembler.feed(END_MARKER));
    }

    @Test
    public void resetDropsPartialImage() {
        byte[] imgA = jpeg(150);
        byte[] imgB = jpeg(150);
        assembler.feed(frame(0, Arrays.copyOfRange(imgA, 0, 60))); // partial image A
        assembler.reset();                                          // e.g. reconnect
        feedImage(imgB, 60, false, -1);
        byte[] out = assembler.feed(END_MARKER);
        assertArrayEquals("chunks must never merge across a reset", imgB, out);
    }

    @Test
    public void invalidJpegRejected() {
        // complete stream but the payload has no trailing EOI
        byte[] notJpeg = jpeg(150);
        notJpeg[148] = 0; notJpeg[149] = 0;
        feedImage(notJpeg, 60, false, -1);
        assertNull("missing EOI must be dropped", assembler.feed(END_MARKER));
    }

    @Test
    public void tinyImageRejected() {
        byte[] tiny = new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, (byte) 0xFF, (byte) 0xD9};
        assembler.feed(frame(0, tiny));
        assertNull("images <= 100 bytes are implausible and dropped", assembler.feed(END_MARKER));
    }

    @Test
    public void oversizeStreamDropped() {
        // A stream that keeps sending chunks without an end marker must be capped
        // (2MB), then the eventual end marker yields nothing.
        byte[] chunk = new byte[65_000];
        Arrays.fill(chunk, (byte) 0x42);
        byte[] first = new byte[65_000];
        first[0] = (byte) 0xFF; first[1] = (byte) 0xD8;
        assembler.feed(frame(0, first));
        for (int idx = 1; idx <= 40; idx++) { // ~2.6MB total, crosses the 2MB cap
            assembler.feed(frame(idx, chunk));
        }
        assertNull("oversize image must be dropped at the cap", assembler.feed(END_MARKER));
    }

    @Test
    public void backToBackImagesAssembleIndependently() {
        byte[] imgA = jpeg(120);
        byte[] imgB = jpeg(300);
        feedImage(imgA, 50, false, -1);
        assertArrayEquals(imgA, assembler.feed(END_MARKER));
        feedImage(imgB, 50, true, 1);
        assertArrayEquals(imgB, assembler.feed(END_MARKER));
        assertEquals(1, assembler.getOrientation());
    }

    @Test
    public void shortOrNullFramesIgnored() {
        assertNull(assembler.feed(null));
        assertNull(assembler.feed(new byte[0]));
        assertNull(assembler.feed(new byte[]{0}));
    }

    @Test
    public void newStartDiscardsPreviousPartial() {
        byte[] imgA = jpeg(150);
        byte[] imgB = jpeg(150);
        for (int i = 2; i < 148; i++) imgB[i] = (byte) 7; // make B distinguishable
        assembler.feed(frame(0, Arrays.copyOfRange(imgA, 0, 60))); // partial A
        feedImage(imgB, 60, false, -1);                             // fresh frame 0
        byte[] out = assembler.feed(END_MARKER);
        assertArrayEquals("a new frame 0 must discard the stale partial", imgB, out);
    }

    /** Sanity for the helper itself. */
    @Test
    public void helperProducesValidJpegMarkers() {
        byte[] j = jpeg(101);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(j, 0, j.length);
        byte[] out = b.toByteArray();
        assertEquals((byte) 0xFF, out[0]);
        assertEquals((byte) 0xD8, out[1]);
        assertEquals((byte) 0xFF, out[out.length - 2]);
        assertEquals((byte) 0xD9, out[out.length - 1]);
    }
}
