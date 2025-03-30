package com.example.myapplication;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.animation.Easing;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.PercentFormatter;
import com.github.mikephil.charting.utils.ColorTemplate;
import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LlmFragment extends Fragment {

    private EditText promptEditText;
    private Button submitButton;
    private Button selectPdfButton;
    private PieChart pieChart;
    private TextView jsonResponseTextView;
    private final Executor executor = Executors.newSingleThreadExecutor();
    private String pdfContent = "";
    private boolean isPdfMode = false;

    // Flag to track if a request is in progress
    private boolean isRequestInProgress = false;

    // API key - consider moving this to a secure storage or environment variable
    private static final String API_KEY = "AIzaSyAfJEwb8nsTB8MQ1xZIiRVS1uq6lgospuw";
    private static final int PDF_PICK_CODE = 1000;

    // Maximum tokens to send in a single request
    private static final int MAX_CONTENT_LENGTH = 7500; // Reduced slightly for safety

    // Rate limiting parameters
    private static final int MAX_REQUESTS_PER_MINUTE = 10;
    private final List<Long> requestTimestamps = new ArrayList<>();

    // Semantic chunk size for processing large PDFs
    private static final int CHUNK_SIZE = 6000;
    private static final int CHUNK_OVERLAP = 500;

    private static final String MODEL_NAME = "gemini-1.5-pro";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_llm, container, false);

        promptEditText = view.findViewById(R.id.promptEditText);
        submitButton = view.findViewById(R.id.submitButton);
        pieChart = view.findViewById(R.id.pieChart);
        jsonResponseTextView = view.findViewById(R.id.jsonResponseTextView);
        selectPdfButton = view.findViewById(R.id.selectPdfButton);

        setupPieChart();

        selectPdfButton.setOnClickListener(v -> openPdfPicker());
        submitButton.setOnClickListener(v -> handlePromptSubmission());

        return view;
    }

    private void openPdfPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/pdf");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PDF_PICK_CODE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PDF_PICK_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri pdfUri = data.getData();
                extractTextFromPdf(pdfUri);
            }
        }
    }

    private void extractTextFromPdf(Uri pdfUri) {
        try (InputStream inputStream = getContext().getContentResolver().openInputStream(pdfUri)) {
            PdfReader reader = new PdfReader(inputStream);
            PdfDocument pdfDocument = new PdfDocument(reader);

            StringBuilder contentBuilder = new StringBuilder();
            for (int i = 1; i <= pdfDocument.getNumberOfPages(); i++) {
                contentBuilder.append(PdfTextExtractor.getTextFromPage(pdfDocument.getPage(i))).append("\n");
            }
            pdfDocument.close();

            // Keep original content instead of removing filler words to preserve context
            pdfContent = contentBuilder.toString().trim();
            isPdfMode = true;

            Toast.makeText(getContext(), "PDF loaded successfully (" +
                    (pdfContent.length() > 1000 ? Math.round(pdfContent.length() / 1000) + "K" : pdfContent.length()) +
                    " characters)", Toast.LENGTH_SHORT).show();
            submitButton.setText("Ask about PDF");
            promptEditText.setHint("Ask a question about the PDF content");

        } catch (IOException e) {
            Toast.makeText(getContext(), "Error reading PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void handlePromptSubmission() {
        String prompt = promptEditText.getText().toString().trim();
        if (prompt.isEmpty()) {
            Toast.makeText(getContext(), "Please enter a prompt", Toast.LENGTH_SHORT).show();
            return;
        }

        // Prevent multiple concurrent requests
        if (isRequestInProgress) {
            Toast.makeText(getContext(), "A request is already in progress", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check rate limiting
        if (!checkRateLimit()) {
            Toast.makeText(getContext(), "Rate limit exceeded. Please wait a moment before trying again.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        isRequestInProgress = true;
        submitButton.setEnabled(false);
        jsonResponseTextView.setText("Loading...");

        if (isPdfMode) {
            fetchResponseWithPdfContext(prompt);
        } else {
            fetchDataFromGemini(prompt);
        }
    }

    private boolean checkRateLimit() {
        // Clean up old timestamps (older than 1 minute)
        long currentTime = System.currentTimeMillis();
        requestTimestamps.removeIf(timestamp ->
                currentTime - timestamp > TimeUnit.MINUTES.toMillis(1));

        // Check if we've reached the limit
        if (requestTimestamps.size() >= MAX_REQUESTS_PER_MINUTE) {
            return false;
        }

        // Add current timestamp and return true
        requestTimestamps.add(currentTime);
        return true;
    }

    private void fetchDataFromGemini(String userPrompt) {
        // Add a specific instruction to format response as JSON for pie chart
        String formattedPrompt = "Generate a JSON response with key-value pairs representing categories and their numeric values. " +
                "These values will be used to create a pie chart. " +
                "Make sure the response is a valid JSON object. " +
                "For example: {\"Category1\": 30, \"Category2\": 20, \"Category3\": 50}.\n\n" +
                "User query: " + userPrompt;

        // Create a GenerativeModel instance with the pro model
        GenerativeModel generativeModel = new GenerativeModel(MODEL_NAME, API_KEY);
        GenerativeModelFutures model = GenerativeModelFutures.from(generativeModel);

        Content content = new Content.Builder().addText(formattedPrompt).build();

        ListenableFuture<GenerateContentResponse> response = model.generateContent(content);
        Futures.addCallback(response, new FutureCallback<GenerateContentResponse>() {
            @Override
            public void onSuccess(GenerateContentResponse result) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    submitButton.setEnabled(true);
                    isRequestInProgress = false;

                    String responseText = result.getText();
                    jsonResponseTextView.setText("Response:\n\n" + responseText);

                    try {
                        // Try to extract JSON from the response if it's wrapped in code blocks
                        String jsonStr = extractJsonFromResponse(responseText);
                        JSONObject jsonObject = new JSONObject(jsonStr);
                        generatePieChart(jsonObject);
                    } catch (JSONException e) {
                        jsonResponseTextView.setText(jsonResponseTextView.getText() +
                                "\n\nError parsing JSON: " + e.getMessage());
                    }
                });
            }

            @Override
            public void onFailure(Throwable t) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    submitButton.setEnabled(true);
                    isRequestInProgress = false;
                    jsonResponseTextView.setText("Error: " + t.getMessage());
                });
            }
        }, executor);
    }

    private String extractJsonFromResponse(String response) {
        // Check if the response contains JSON code blocks
        if (response.contains("```json")) {
            int start = response.indexOf("```json") + 7;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).trim();
            }
        }

        // Check if the response is just JSON without code blocks
        if (response.trim().startsWith("{") && response.trim().endsWith("}")) {
            return response.trim();
        }

        return response;
    }

    private void generatePieChart(JSONObject jsonObject) {
        List<PieEntry> entries = new ArrayList<>();
        try {
            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                float value = (float) jsonObject.getDouble(key);
                entries.add(new PieEntry(value, key));
            }

            PieDataSet dataSet = new PieDataSet(entries, "Data Categories");
            setupPieDataSet(dataSet);  // Apply our custom styling

            PieData pieData = new PieData(dataSet);
            pieChart.setData(pieData);
            pieChart.invalidate();  // Refresh the chart

            // Optional: display a message if there's no data
            pieChart.setNoDataText("No data available");
            pieChart.setNoDataTextColor(Color.GRAY);

        } catch (JSONException e) {
            Toast.makeText(getContext(), "Error generating pie chart: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setupPieDataSet(PieDataSet dataSet) {
        // Colors
        dataSet.setColors(ColorTemplate.MATERIAL_COLORS);  // Use material design colors

        // Slice spacing
        dataSet.setSliceSpace(2f);  // Space between slices

        // Value text formatting
        dataSet.setValueTextSize(11f);
        dataSet.setValueTextColor(Color.WHITE);
        dataSet.setValueTypeface(Typeface.DEFAULT_BOLD);
        dataSet.setValueFormatter(new PercentFormatter(pieChart));  // Format as percentage

        // Value line settings (the lines connecting slices to labels)
        dataSet.setValueLinePart1OffsetPercentage(80f);  // Line first segment length
        dataSet.setValueLinePart1Length(0.2f);
        dataSet.setValueLinePart2Length(0.4f);
        dataSet.setValueLineColor(Color.DKGRAY);  // Line color
        dataSet.setValueLineWidth(1f);  // Line width
        dataSet.setYValuePosition(PieDataSet.ValuePosition.INSIDE_SLICE);  // Label position
        dataSet.setXValuePosition(PieDataSet.ValuePosition.OUTSIDE_SLICE);

        // Highlight
        dataSet.setHighlightEnabled(true);  // Enable highlighting on selection
        dataSet.setSelectionShift(5f);  // Extrude slices when selected
    }

    private void setupPieChart() {
        // Basic chart settings
        pieChart.setUsePercentValues(true);  // Display percentages instead of values
        pieChart.getDescription().setEnabled(false);  // Disable chart description
        pieChart.setExtraOffsets(5, 10, 5, 5);  // Add padding around the chart

        // Center text settings
        pieChart.setCenterText("Results");  // Text in the middle of the chart
        pieChart.setCenterTextSize(16f);  // Font size for center text
        pieChart.setCenterTextColor(Color.DKGRAY);  // Color for center text
        pieChart.setCenterTextTypeface(Typeface.DEFAULT_BOLD);  // Font style

        // Customize the hole (empty space) in the center
        pieChart.setDrawHoleEnabled(true);  // Enable the center hole
        pieChart.setHoleColor(Color.WHITE);  // Set hole background color
        pieChart.setHoleRadius(40f);  // Set hole size (percentage of chart radius)
        pieChart.setTransparentCircleRadius(45f);  // Sets transparent circle radius
        pieChart.setTransparentCircleColor(Color.WHITE);  // Color of transparent circle
        pieChart.setTransparentCircleAlpha(110);  // Transparency of the inner circle

        // Legend settings
        Legend legend = pieChart.getLegend();
        legend.setEnabled(true);  // Show the legend
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setDrawInside(false);
        legend.setTextSize(12f);
        legend.setFormSize(10f);  // Size of the legend forms/shapes
        legend.setFormToTextSpace(4f);  // Space between form and text
        legend.setXEntrySpace(10f);  // Space between entries
        legend.setYEntrySpace(0f);  // Space between rows in legend
        legend.setWordWrapEnabled(true);  // Enable word wrapping

        // Entry label styling (the text on the chart slices)
        pieChart.setDrawEntryLabels(true);  // Show labels on chart slices
        pieChart.setEntryLabelColor(Color.BLACK);  // Color of slice labels
        pieChart.setEntryLabelTextSize(12f);  // Size of slice labels
        pieChart.setEntryLabelTypeface(Typeface.DEFAULT);  // Font style for labels

        // Rotation and interaction
        pieChart.setRotationEnabled(true);  // Enable chart rotation by touch
        pieChart.setRotationAngle(0);  // Initial rotation angle
        pieChart.setHighlightPerTapEnabled(true);  // Highlight slice when tapped

        // Animation
        pieChart.animateY(1400, Easing.EaseInOutQuad);  // Animate chart building

        // Additional settings
        pieChart.setDrawCenterText(true);  // Show center text
        pieChart.setDrawMarkers(false);  // Hide markers
        pieChart.setMinAngleForSlices(0.5f);  // Minimum angle for any slice

        // Set interaction type (gesture or programmatic)
        pieChart.setTouchEnabled(true);  // Enable touch gestures
        pieChart.setDragDecelerationFrictionCoef(0.9f);  // Slows down rotation movement

        // Set maximum angle of slice highlighting
        pieChart.setMaxAngle(360);  // Maximum angle for the whole chart
    }

    private void fetchResponseWithPdfContext(String userPrompt) {
        if (pdfContent.length() <= MAX_CONTENT_LENGTH) {
            // If PDF content is small enough, send it in a single request
            String formattedPrompt = "Answer the following question based on this PDF document content:\n\n" +
                    "PDF CONTENT:\n" + pdfContent + "\n\n" +
                    "QUESTION: " + userPrompt + "\n\n" +
                    "Provide a clear and concise answer based only on the information in the document.";

            sendSingleRequest(formattedPrompt);
        } else {
            // For large PDFs, use semantic searching
            semanticSearchInPdf(userPrompt);
        }
    }

    private void semanticSearchInPdf(String userPrompt) {
        // Create chunks from the PDF content with overlap
        List<String> chunks = createChunks(pdfContent, CHUNK_SIZE, CHUNK_OVERLAP);

        // First, search for keywords to find potentially relevant chunks
        List<String> relevantChunks = findRelevantChunks(chunks, userPrompt);

        if (relevantChunks.isEmpty()) {
            // If no relevant chunks found, use the beginning and end of the document
            relevantChunks.add(pdfContent.substring(0, Math.min(pdfContent.length(), MAX_CONTENT_LENGTH / 2)));
            if (pdfContent.length() > MAX_CONTENT_LENGTH / 2) {
                relevantChunks.add(pdfContent.substring(Math.max(0, pdfContent.length() - MAX_CONTENT_LENGTH / 2)));
            }
        }

        // Combine relevant chunks (up to the max content length)
        String combinedContent = combineChunks(relevantChunks, MAX_CONTENT_LENGTH);

        // Send the request with the combined chunks
        String formattedPrompt = "Answer the following question based on this PDF document excerpt:\n\n" +
                "PDF CONTENT:\n" + combinedContent + "\n\n" +
                "QUESTION: " + userPrompt + "\n\n" +
                "Provide a clear and concise answer based only on the information in the document. " +
                "If the answer is not contained in the provided excerpt, state that clearly.";

        sendSingleRequest(formattedPrompt);
    }

    private List<String> createChunks(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int length = text.length();

        for (int i = 0; i < length; i += (chunkSize - overlap)) {
            int end = Math.min(i + chunkSize, length);
            chunks.add(text.substring(i, end));

            if (end == length) break;
        }

        return chunks;
    }

    private List<String> findRelevantChunks(List<String> chunks, String query) {
        // Extract keywords from the query (nouns, verbs, adjectives)
        List<String> keywords = extractKeywords(query);

        // Track relevance score for each chunk
        Map<String, Integer> chunkScores = new HashMap<>();

        // Score each chunk based on keyword occurrences
        for (String chunk : chunks) {
            int score = 0;
            String lowerChunk = chunk.toLowerCase();

            for (String keyword : keywords) {
                // Count occurrences of the keyword
                int count = countOccurrences(lowerChunk, keyword.toLowerCase());
                score += count;
            }

            if (score > 0) {
                chunkScores.put(chunk, score);
            }
        }

        // Sort chunks by relevance score (descending)
        List<Map.Entry<String, Integer>> sortedEntries = new ArrayList<>(chunkScores.entrySet());
        sortedEntries.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));

        // Take the top N most relevant chunks
        List<String> relevantChunks = new ArrayList<>();
        int maxChunks = 3; // Adjust based on average chunk size

        for (int i = 0; i < Math.min(maxChunks, sortedEntries.size()); i++) {
            relevantChunks.add(sortedEntries.get(i).getKey());
        }

        return relevantChunks;
    }

    private List<String> extractKeywords(String query) {
        List<String> keywords = new ArrayList<>();

        // Split by spaces and punctuation
        String[] words = query.split("[\\s.,;:!?()\\[\\]{}\"']+");

        // Common English stop words to filter out
        List<String> stopWords = Arrays.asList(
                "a", "an", "the", "and", "or", "but", "is", "are", "was", "were",
                "be", "been", "being", "have", "has", "had", "do", "does", "did",
                "to", "at", "by", "for", "with", "about", "against", "between",
                "into", "through", "during", "before", "after", "above", "below",
                "from", "up", "down", "in", "out", "on", "off", "over", "under",
                "again", "further", "then", "once", "here", "there", "when", "where",
                "why", "how", "all", "any", "both", "each", "few", "more", "most",
                "other", "some", "such", "no", "nor", "not", "only", "own", "same",
                "so", "than", "too", "very", "can", "will", "just", "should", "now"
        );

        // Add words that aren't stop words and are at least 3 chars
        for (String word : words) {
            if (word.length() >= 3 && !stopWords.contains(word.toLowerCase())) {
                keywords.add(word);
            }
        }

        return keywords;
    }

    private int countOccurrences(String text, String word) {
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);

        int count = 0;
        while (matcher.find()) {
            count++;
        }

        return count;
    }

    private String combineChunks(List<String> chunks, int maxLength) {
        StringBuilder combined = new StringBuilder();

        int totalLength = 0;
        for (String chunk : chunks) {
            if (totalLength + chunk.length() > maxLength) {
                // If adding this chunk would exceed the limit, truncate it
                int remainingSpace = maxLength - totalLength;
                if (remainingSpace > 100) { // Only add if we have meaningful space left
                    combined.append("\n\n[...]\n\n").append(chunk.substring(0, remainingSpace));
                }
                break;
            }

            // Add separator between chunks
            if (totalLength > 0) {
                combined.append("\n\n[...]\n\n");
            }

            combined.append(chunk);
            totalLength += chunk.length() + 8; // +8 for the separator
        }

        return combined.toString();
    }

    private void sendSingleRequest(String formattedPrompt) {
        // Use gemini-1.5-pro for better context handling
        GenerativeModel generativeModel = new GenerativeModel(MODEL_NAME, API_KEY);
        GenerativeModelFutures model = GenerativeModelFutures.from(generativeModel);

        Content content = new Content.Builder().addText(formattedPrompt).build();

        ListenableFuture<GenerateContentResponse> response = model.generateContent(content);
        Futures.addCallback(response, new FutureCallback<GenerateContentResponse>() {
            @Override
            public void onSuccess(GenerateContentResponse result) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    submitButton.setEnabled(true);
                    isRequestInProgress = false;
                    jsonResponseTextView.setText("Response:\n\n" + result.getText());
                });
            }

            @Override
            public void onFailure(Throwable t) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    submitButton.setEnabled(true);
                    isRequestInProgress = false;
                    String errorMsg = t.getMessage();

                    // Handle quota error with specific message
                    if (errorMsg != null && errorMsg.contains("quota")) {
                        jsonResponseTextView.setText("Error: You've exceeded your current quota for the Gemini API. " +
                                "Please wait before making more requests or check your API usage limits.");

                        // Add rate limiting suggestion
                        Toast.makeText(getContext(),
                                "You may need to implement rate limiting or wait before trying again",
                                Toast.LENGTH_LONG).show();
                    } else {
                        jsonResponseTextView.setText("Error: " + errorMsg);
                    }
                });
            }
        }, executor);
    }


}