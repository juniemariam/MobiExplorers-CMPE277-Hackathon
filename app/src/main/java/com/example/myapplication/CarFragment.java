package com.example.myapplication;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.example.myapplication.utils.FileUtil;
import com.example.myapplication.utils.WavToFloatConverter;

import org.tensorflow.lite.Interpreter;

public class CarFragment extends Fragment {

    private Uri selectedAudioUri;
    private TextView resultTextView;
    private TextView audioFileText;

    private final ActivityResultLauncher<String> audioPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    String mimeType = requireContext().getContentResolver().getType(uri);
                    if (mimeType != null && mimeType.startsWith("audio/")) {
                        selectedAudioUri = uri;
                        String fileName = getFileNameFromUri(requireContext(), uri);
                        audioFileText.setText(fileName);
                    } else {
                        Toast.makeText(getContext(), "Please select a valid audio file", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_car, container, false);

        ImageView audioIcon = view.findViewById(R.id.audio_icon);
        Button callMLButton = view.findViewById(R.id.call_ml_button);
        resultTextView = view.findViewById(R.id.result_text);
        audioFileText = view.findViewById(R.id.audio_file_text);

        audioIcon.setOnClickListener(v -> audioPickerLauncher.launch("audio/*"));

        callMLButton.setOnClickListener(v -> {
            if (selectedAudioUri != null) {
                resultTextView.setText("Running ML model...");
                runMLModel(selectedAudioUri);
            } else {
                Toast.makeText(getContext(), "Please select an audio file first", Toast.LENGTH_SHORT).show();
            }
        });

        return view;
    }

    private void runMLModel(Uri audioUri) {
        try {
            Context context = requireContext();

            float[][][] input = WavToFloatConverter.readAsFloat3D(context, audioUri);
            if (input == null || input[0].length != 16000) {
                resultTextView.setText("Audio must be 1-second WAV (16kHz mono).");
                return;
            }

            float[][] output = new float[1][4]; // Assuming 4-class classifier

            Interpreter interpreter = new Interpreter(FileUtil.loadMappedFile(context, "raw_audio_model.tflite"));
            interpreter.run(input, output);

            int maxIndex = 0;
            float maxConf = 0;
            for (int i = 0; i < output[0].length; i++) {
                if (output[0][i] > maxConf) {
                    maxConf = output[0][i];
                    maxIndex = i;
                }
            }

            String[] labels = FileUtil.loadLabels(context, "labels.txt");
            String predictedClass = labels[maxIndex];
            String fileName = getFileNameFromUri(context, audioUri);

            resultTextView.setText("🎧 Analyzing: " + fileName +
                    "\n\nPredicted Class: " + predictedClass.toUpperCase() +
                    "\nConfidence = " + String.format("%.5f", maxConf));

        } catch (Exception e) {
            resultTextView.setText("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String getFileNameFromUri(Context context, Uri uri) {
        String result = "Audio File Selected";
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    result = cursor.getString(nameIndex);
                }
            }
        } else {
            result = uri.getLastPathSegment();
        }
        return result;
    }
}
