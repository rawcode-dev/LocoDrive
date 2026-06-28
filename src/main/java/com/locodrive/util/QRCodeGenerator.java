package com.locodrive.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.util.HashMap;
import java.util.Map;

/**
 * Generates a QR code JavaFX Image from a URL string using ZXing.
 */
public class QRCodeGenerator {

    /**
     * Generates a QR code image for the given URL.
     *
     * @param url  The URL to encode (e.g. "http://192.168.1.10:8080")
     * @param size Pixel size of the output image (square).
     * @return A JavaFX {@link Image} containing the QR code, or null on failure.
     */
    public static Image generateQR(String url, int size) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(url, BarcodeFormat.QR_CODE, size, size, hints);

            WritableImage image = new WritableImage(size, size);
            PixelWriter pixelWriter = image.getPixelWriter();

            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    // matrix.get(x,y) returns true if the module is dark
                    if (matrix.get(x, y)) {
                        pixelWriter.setArgb(x, y, 0xFF4F9CF9); // accent blue
                    } else {
                        pixelWriter.setArgb(x, y, 0xFF1A1D2E); // dark background
                    }
                }
            }

            return image;

        } catch (WriterException e) {
            System.err.println("QR generation failed: " + e.getMessage());
            return null;
        }
    }
}
