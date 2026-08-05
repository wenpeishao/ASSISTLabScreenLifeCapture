package com.screenomics;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.android.gms.tasks.Tasks;

/**
 * Lightweight on-device OCR using Google ML Kit.
 * Extracts text from a Bitmap synchronously (~50-100ms per frame).
 */
public class OcrProcessor {
    private static final String TAG = "OcrProcessor";
    private final TextRecognizer recognizer;

    public OcrProcessor() {
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    /**
     * Extract text from bitmap synchronously.
     * @return extracted text, or empty string on failure
     */
    public String extractText(Bitmap bitmap) {
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            com.google.mlkit.vision.text.Text result = Tasks.await(recognizer.process(image));
            return result.getText();
        } catch (Exception e) {
            Log.e(TAG, "OCR failed", e);
            return "";
        }
    }

    public void close() {
        recognizer.close();
    }
}
