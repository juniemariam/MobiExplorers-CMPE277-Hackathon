package com.example.myapplication.utils;

import android.content.Context;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class FileUtil {
    public static MappedByteBuffer loadMappedFile(Context context, String assetFileName) throws Exception {
        File file = new File(context.getFilesDir(), assetFileName);
        if (!file.exists()) {
            try (InputStream is = context.getAssets().open(assetFileName);
                 FileOutputStream os = new FileOutputStream(file)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }
            }
        }

        try (FileChannel channel = new FileInputStream(file).getChannel()) {
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
        }
    }

    public static String[] loadLabels(Context context, String fileName) throws Exception {
        List<String> labels = new ArrayList<>();
        try (InputStream is = context.getAssets().open(fileName);
             Scanner scanner = new Scanner(is)) {
            while (scanner.hasNextLine()) {
                labels.add(scanner.nextLine());
            }
        }
        return labels.toArray(new String[0]);
    }
}
