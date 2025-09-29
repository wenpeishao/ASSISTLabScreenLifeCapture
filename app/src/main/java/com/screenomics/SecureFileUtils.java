package com.screenomics;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class SecureFileUtils {
    private static final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generate secure filename matching server format
     * Format: {short_id}_{created_at}_{type}_{iv}.{extension}
     *
     * @param enrollmentHash 8-character hex hash from enrollment (short_id)
     * @param type data type - mapped to server types (image, data, text)
     * @param extension file extension (e.g., "png", "json")
     * @param iv initialization vector bytes (16 bytes for AES-256-CBC)
     * @return secure filename
     */
    public static String generateSecureFilename(String enrollmentHash, String type, String extension, byte[] iv) {
        // Map app types to server expected types
        String serverType = mapToServerType(type);

        // Generate timestamp in ISO8601 format with timezone (server expects colons)
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
        String timestamp = sdf.format(new Date());

        // Replace colons and timezone format for filename compatibility
        // Convert "2025-09-29T12:08:12-0500" to "2025-09-29T120812-0500"
        timestamp = timestamp.replaceAll(":", "");

        // Convert IV to hex string
        String ivHex = bytesToHex(iv);

        return enrollmentHash + "_" + timestamp + "_" + serverType + "_" + ivHex + "." + extension;
    }

    /**
     * Legacy method for backward compatibility - generates IV internally
     */
    public static String generateSecureFilename(String enrollmentHash, String type, String extension) {
        byte[] iv = generateSecureIV();
        return generateSecureFilename(enrollmentHash, type, extension, iv);
    }

    /**
     * Map app data types to server expected types
     */
    private static String mapToServerType(String appType) {
        switch(appType.toLowerCase()) {
            case "screenshot":
            case "video":
                return "image";
            case "metadata":
                return "metadata";
            case "gps":
                return "gps";
            case "log":
                return "text";
            default:
                return "file";
        }
    }

    /**
     * Generate 16 random bytes for AES-256-CBC IV
     * @return 16 random bytes suitable for CBC IV
     */
    public static byte[] generateSecureIV() {
        byte[] iv = new byte[16];
        secureRandom.nextBytes(iv);
        return iv;
    }

    /**
     * Convert byte array to lowercase hex string
     * @param bytes input bytes
     * @return hex string representation
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}