package com.screenomics;

import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Reassembles OMI Glass photo notifications (BLE char 19B10005) into a complete JPEG.
 *
 * Framing per notification: bytes[0..1] = uint16 little-endian frame index.
 *   idx == 0      -> start of a new image. The JPEG SOI (0xFFD8) begins at byte 2
 *                    (older fw) or byte 3 (fw >= 2.1.1, byte[2] = orientation). We
 *                    auto-detect the SOI so both framings work.
 *   idx == 0xFFFF -> end of image.
 *   else          -> continuation chunk (JPEG bytes from byte 2); must be in order.
 *
 * finalizeJpeg() validates SOI/EOI markers + plausible size so a dropped/out-of-order
 * notification yields a discarded image rather than a corrupt upload.
 *
 * Methods are synchronized: feed() runs on the BLE binder thread while reset() may
 * be called from the service's main-thread connection lifecycle.
 */
public class GlassPhotoAssembler {
    private static final String TAG = "GlassBle";

    /** Hard cap on a single image; a stream that never sends the end marker
     *  (firmware bug / corrupted 0xFFFF frame) must not grow unbounded. */
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

    private ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private int expected = -1;
    private int orientation = -1;

    /** Orientation byte of the most recently completed image, or -1 if none. */
    public synchronized int getOrientation() {
        return orientation;
    }

    /** Returns a complete JPEG byte[] when an image finishes, else null. */
    public synchronized byte[] feed(byte[] b) {
        if (b == null || b.length < 2) return null;
        int idx = (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);

        if (idx == 0xFFFF) {
            byte[] jpeg = buf.toByteArray();
            int imgOrientation = orientation;
            reset();
            if (jpeg.length == 0) return null; // joined mid-stream; nothing buffered yet
            if (valid(jpeg)) {
                orientation = imgOrientation; // expose THIS image's orientation to the caller
                return jpeg;
            }
            Log.w(TAG, "end-of-image marker but invalid JPEG (len=" + jpeg.length + "), dropping");
            return null;
        }

        if (idx == 0) {
            reset();
            int start = 2;
            // auto-detect JPEG SOI (handles the fw>=2.1.1 orientation byte at [2])
            for (int i = 2; i + 1 < b.length && i < 6; i++) {
                if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0xD8) { start = i; break; }
            }
            if (start == 3) orientation = b[2] & 0xFF;
            if (b.length - start > 0) buf.write(b, start, b.length - start);
            expected = 1;
        } else {
            if (expected == -1) {
                // joined mid-stream before a frame-0 marker; wait for the next image start
                return null;
            }
            if (idx != expected) {
                Log.w(TAG, "frame gap idx=" + idx + " expected=" + expected + ", dropping image");
                reset();
                return null;
            }
            if (buf.size() + b.length > MAX_IMAGE_BYTES) {
                Log.w(TAG, "image exceeded " + MAX_IMAGE_BYTES + " bytes without end marker, dropping");
                reset();
                return null;
            }
            if (b.length - 2 > 0) buf.write(b, 2, b.length - 2);
            expected++;
        }
        return null;
    }

    private boolean valid(byte[] j) {
        return j.length > 100
            && (j[0] & 0xFF) == 0xFF && (j[1] & 0xFF) == 0xD8           // SOI
            && (j[j.length - 2] & 0xFF) == 0xFF && (j[j.length - 1] & 0xFF) == 0xD9; // EOI
    }

    /** Drop any partial image, e.g. on disconnect/reconnect, so chunks from two
     *  different images can never be concatenated into one "valid" JPEG. */
    public synchronized void reset() {
        buf = new ByteArrayOutputStream();
        expected = -1;
        orientation = -1;
    }
}
