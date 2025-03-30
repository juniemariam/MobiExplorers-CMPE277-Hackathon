package com.example.myapplication.utils;

import android.content.Context;
import android.net.Uri;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class WavToFloatConverter {
    public static float[][][] readAsFloat3D(Context context, Uri uri) {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            byte[] header = new byte[44];
            if (is.read(header) != 44) return null;

            byte[] audioBytes = new byte[16000 * 2];  // 16-bit PCM
            int read = is.read(audioBytes);
            if (read < 16000 * 2) return null;

            float[][][] result = new float[1][16000][1];
            ByteBuffer bb = ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 16000; i++) {
                result[0][i][0] = bb.getShort() / 32768.0f;
            }
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
