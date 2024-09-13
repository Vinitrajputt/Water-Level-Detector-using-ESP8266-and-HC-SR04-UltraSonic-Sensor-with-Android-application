package com.vinitrajputt.watermonitor;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class MainActivity extends AppCompatActivity {

    private ProgressBar progressBar;
    private TextView waterLevelText;
    private TextView lastUpdatedTime;
    private TextView approxWaterAvailableText;
    private TextView pumpStatusText;
    private TextView timeRemainingText;

    // Tank dimensions (editable)
    private float tankHeightCm = 128.87f; // Total depth in cm
    private float maxWaterLevelCm = 118.87f; // Max water level in cm (considered full)
    private float tankLengthCm = 195.58f; // Length in cm
    private float tankWidthCm = 195.58f;  // Width in cm

    // Sensor offset (height of sensor from full water level)
    private float sensorOffsetCm = 10.0f;

    // Time window for pump status and data validity (in milliseconds)
    private long timeWindowMillis = 15 * 60 * 1000; // 15 minutes
    private long pumpStatusTimeWindowMillis = 30 * 60 * 1000; // 30 minutes

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize views
        progressBar = findViewById(R.id.waterLevelProgress);
        waterLevelText = findViewById(R.id.waterLevelPercentage);
        lastUpdatedTime = findViewById(R.id.lastUpdatedTime);
        approxWaterAvailableText = findViewById(R.id.waterLevelVolume);
        pumpStatusText = findViewById(R.id.pumpRunning);
        timeRemainingText = findViewById(R.id.timeRemaining);

        // Fetch data from Firebase
        fetchSensorData();
    }

    private void fetchSensorData() {
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("sensorData");
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String today = dateFormat.format(new Date());

        ref.child(today).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    try {
                        Map<String, Object> readingsMap = (Map<String, Object>) dataSnapshot.getValue();
                        if (readingsMap != null) {
                            List<Long> timestamps = readingsMap.keySet().stream()
                                    .map(Long::parseLong)
                                    .collect(Collectors.toList());

                            Collections.sort(timestamps, Collections.reverseOrder());

                            if (!timestamps.isEmpty()) {
                                long lastTimestamp = timestamps.get(0);
                                float lastReadingDistance = Float.parseFloat(readingsMap.get(String.valueOf(lastTimestamp)).toString());

                                // Apply sensor offset correction
                                lastReadingDistance = lastReadingDistance - sensorOffsetCm;

                                updateLastUpdatedTime(lastTimestamp);
                                long currentTime = System.currentTimeMillis() / 1000; // Convert to seconds

                                if (currentTime - lastTimestamp > timeWindowMillis) {
                                    // Data older than 15 minutes
                                    setErrorState();
                                } else {
                                    int waterLevelPercentage = calculateWaterLevelPercentage(lastReadingDistance);
                                    updateProgressBar(waterLevelPercentage);
                                    updateApproxWaterAvailable(lastReadingDistance);
                                    updatePumpStatusAndTimeRemaining(readingsMap, currentTime, timestamps);
                                }
                            } else {
                                setErrorState(); // No valid timestamps
                            }
                        } else {
                            setErrorState(); // No valid readingsMap
                        }
                    } catch (ClassCastException e) {
                        Log.e("MainActivity", "ClassCastException: " + e.getMessage());
                        setErrorState();
                    }
                } else {
                    setErrorState(); // No data for today
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e("MainActivity", "Firebase data fetch failed: " + databaseError.getMessage());
            }
        });
    }

    // Method to calculate water level percentage based on sensor distance (after offset correction)
    private int calculateWaterLevelPercentage(float distance) {
        float waterHeight = maxWaterLevelCm - distance; // Actual water height in cm

        if (waterHeight < 0) {
            return 0; // Empty tank
        }

        if (waterHeight > maxWaterLevelCm) {
            return 100; // Overflow (shouldn't happen ideally)
        }

        return (int) ((waterHeight * 100) / maxWaterLevelCm);
    }

    // Method to update the progress bar and display the water level percentage
    private void updateProgressBar(int waterLevelPercentage) {
        progressBar.setProgress(waterLevelPercentage);
        waterLevelText.setText(waterLevelPercentage + "%");
    }

    // Method to update the last updated time TextView
    private void updateLastUpdatedTime(long timestamp) {
        Date date = new Date(timestamp * 1000L);
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy hh:mm:ss a", Locale.getDefault());
        String formattedDate = sdf.format(date);
        lastUpdatedTime.setText("Last updated: " + formattedDate);
    }

    // Method to calculate and update the approximate water available text
    private void updateApproxWaterAvailable(float distance) {
        float waterHeight = maxWaterLevelCm - distance; // Actual water height in cm
        float waterVolumeLiters = waterHeight * tankLengthCm * tankWidthCm / 1000; // Volume in liters
        approxWaterAvailableText.setText(String.format("%.2f", waterVolumeLiters) + " Liters approx.");
    }

    // Method to update the pump status and remaining time
    private void updatePumpStatusAndTimeRemaining(Map<String, Object> readingsMap, long currentTime, List<Long> timestamps) {
        List<Long> validTimestamps = timestamps.stream()
                .filter(timestamp -> currentTime - timestamp <= pumpStatusTimeWindowMillis)
                .collect(Collectors.toList());

        if (validTimestamps.size() < 2) {
            pumpStatusText.setText("Pump Status: Unknown");
            timeRemainingText.setText("Time Remaining: -");
            return;
        }

        float latestDistance = Float.parseFloat(readingsMap.get(String.valueOf(validTimestamps.get(0))).toString()) - sensorOffsetCm;
        float previousDistance = Float.parseFloat(readingsMap.get(String.valueOf(validTimestamps.get(validTimestamps.size() - 1))).toString()) - sensorOffsetCm;


        if (latestDistance < previousDistance) {
            pumpStatusText.setText("Pump Status: ON (Filling)");

            // Calculate average fill rate over last 30 minutes
            float totalDistanceChange = previousDistance - latestDistance;
            long totalTimeSeconds = validTimestamps.get(0) - validTimestamps.get(validTimestamps.size() - 1);
            float avgFillRateCmPerSecond = totalDistanceChange / totalTimeSeconds;

            // Calculate time remaining to fill to the top (100%)
            float remainingHeightCm = maxWaterLevelCm - (maxWaterLevelCm - latestDistance); // Distance from current level to full
            long timeRemainingSeconds = (long) (remainingHeightCm / avgFillRateCmPerSecond);
            timeRemainingText.setText("Time Remaining: " + formatTime(timeRemainingSeconds));

        } else if (latestDistance > previousDistance) {
            pumpStatusText.setText("Pump Status: OFF (Draining)");
            timeRemainingText.setText("Time Remaining: -");
        } else {
            pumpStatusText.setText("Pump Status: Stable (No Movement)");
            timeRemainingText.setText("Time Remaining: -");
        }
    }


    // Helper method to format time in HH:mm:ss format
    private String formatTime(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, remainingSeconds);
    }

    // Method to set default error state
    private void setErrorState() {
        progressBar.setProgress(0);
        waterLevelText.setText("Sensor Error -1");
        approxWaterAvailableText.setText("Unknown");
        pumpStatusText.setText("Pump Status: Unknown");
        timeRemainingText.setText("Time Remaining: -");
        lastUpdatedTime.setText("Last updated: -");
    }
}