package com.example.myapplication;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Toast;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.Volley;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import android.widget.Button;
import android.util.Log;


public class ApiFragment extends Fragment {

    private LineChart chart;
    private RequestQueue requestQueue;

    private static final String GDP_URL = "https://api.worldbank.org/v2/country/WLD/indicator/NY.GDP.MKTP.KD.ZG?format=json";
    private static final String CO2_URL = "https://api.worldbank.org/v2/country/WLD/indicator/EN.GHG.CO2.AG.MT.CE.AR5?format=json";
    private static final String AGRI_LAND_URL = "https://api.worldbank.org/v2/country/WLD/indicator/AG.LND.AGRI.ZS?format=json";

    public ApiFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_api, container, false);


        chart = view.findViewById(R.id.chart);
        Button btnGDP = view.findViewById(R.id.btn_gdp);
        Button btnCO2 = view.findViewById(R.id.btn_co2);
        Button btnAgriLand = view.findViewById(R.id.btn_agri_land);

        requestQueue = Volley.newRequestQueue(requireContext());


        btnGDP.setOnClickListener(v -> fetchData(GDP_URL, "GDP Growth (%)"));
        btnCO2.setOnClickListener(v -> fetchData(CO2_URL, "CO2 Emissions"));
        btnAgriLand.setOnClickListener(v -> fetchData(AGRI_LAND_URL, "Agricultural Land (%)"));

        return view;
    }

    private void fetchData(String url, String label) {
        JsonArrayRequest jsonArrayRequest = new JsonArrayRequest(
                Request.Method.GET, url, null,
                new Response.Listener<JSONArray>() {
                    @Override
                    public void onResponse(JSONArray response) {
                        try {
                            Log.d("ApiFragment", "Raw API Response: " + response.toString()); // Log the entire response

                            if (response.length() > 1) {
                                JSONArray dataArray = response.getJSONArray(1);

                                if (dataArray.length() == 0) {
                                    showToast("No data available");
                                    return;
                                }

                                List<Entry> entries = new ArrayList<>();
                                List<String> years = new ArrayList<>();

                                for (int i = 0; i < dataArray.length(); i++) {
                                    JSONObject dataObject = dataArray.getJSONObject(i);

                                    if (dataObject.has("date") && !dataObject.isNull("date") && dataObject.has("value") && !dataObject.isNull("value")) {
                                        try {
                                            int year = dataObject.getInt("date");
                                            double value = dataObject.getDouble("value");

                                            Log.d("ApiFragment", "Year: " + year + " Value: " + value);

                                            entries.add(new Entry(year, (float) value));
                                            years.add(String.valueOf(year));
                                        } catch (JSONException e) {
                                            Log.e("ApiFragment", "Error parsing entry at index " + i, e);
                                        }
                                    } else {
                                        Log.w("ApiFragment", "Skipping entry at index " + i + " due to missing 'date' or 'value'");
                                    }
                                }

                                if (entries.isEmpty()) {
                                    showToast("No valid data to display");
                                    return;
                                }

                                Collections.reverse(entries);
                                Collections.reverse(years);

                                updateChart(entries, label, years);
                            } else {
                                showToast("No data available");
                            }
                        } catch (JSONException e) {
                            Log.e("ApiFragment", "Error parsing response", e);
                            showToast("Error parsing data from server");
                        }
                    }
                },
                error -> {
                    Log.e("ApiFragment", "Error fetching data", error);
                    showToast("Error fetching data");
                });

        requestQueue.add(jsonArrayRequest);
    }

    private void updateChart(List<Entry> entries, String label, List<String> years) {
        List<Entry> normalizedEntries = new ArrayList<>();
        List<String> xAxisLabels = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            Entry originalEntry = entries.get(i);
            normalizedEntries.add(new Entry(i, originalEntry.getY()));
            xAxisLabels.add(years.get(i));
        }

        LineDataSet dataSet = new LineDataSet(normalizedEntries, label);
        dataSet.setColor(getResources().getColor(android.R.color.holo_blue_dark));
        dataSet.setLineWidth(2f);
        dataSet.setCircleRadius(4f);
        dataSet.setCircleColor(getResources().getColor(android.R.color.holo_blue_dark));


        dataSet.setDrawValues(false);

        LineData lineData = new LineData(dataSet);
        chart.setData(lineData);
        chart.invalidate();

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setLabelRotationAngle(45f);

        xAxis.setValueFormatter(new IndexAxisValueFormatter(xAxisLabels));

        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setGranularity(1f);

        chart.getAxisRight().setEnabled(false);
    }

    private void showToast(String message) {
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }
}
