package com.codespace.kub125runnerready;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends Activity implements SensorEventListener, LocationListener {

    private static final String DEFAULT_SERVER = "http://192.168.1.6:8080/track";

    private TextView titleView;
    private TextView timeView;
    private TextView mainView;
    private TextView signalView;
    private TextView gpsView;
    private TextView netView;
    private TextView fileView;
    private KubVisualizer kubVisualizer;

    private SensorManager sensorManager;
    private LocationManager locationManager;

    private Sensor stepCounterSensor;
    private Sensor stepDetectorSensor;
    private Sensor accelerometerSensor;
    private Sensor gyroscopeSensor;
    private Sensor magneticSensor;

    private boolean sensorsStarted = false;
    private boolean locationStarted = false;
    private boolean running = true;
    private boolean sendingNow = false;

    private boolean usingStepCounter = false;
    private boolean usingStepDetector = false;

    private int steps = 0;
    private float stepBase = -1f;
    private float stepCurrent = -1f;

    private long lastAccelStepMs = 0L;
    private long lastPostMs = 0L;
    private long startMs = 0L;

    private double rawSignal = 0.0;
    private double cleanSignal = 0.0;
    private double signalQuality = 0.0;

    private double gyroEnergy = 0.0;
    private double magEnergy = 0.0;

    private double filteredLat = Double.NaN;
    private double filteredLon = Double.NaN;
    private double gpsQuality = 0.0;
    private float gpsAccuracy = -1f;

    private double gpsDistanceMeters = 0.0;
    private double lastDistanceLat = Double.NaN;
    private double lastDistanceLon = Double.NaN;

    private File trackFile;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final KubDenoiser denoiser = new KubDenoiser();
    private final KubLocationFilter locationFilter = new KubLocationFilter();

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            updateScreen();

            if (running) {
                String json = buildJson();
                appendLine(json);

                long now = System.currentTimeMillis();
                if (now - lastPostMs > 5000) {
                    lastPostMs = now;
                    sendToServer(json);
                }
            }

            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        startMs = System.currentTimeMillis();

        createTrackFile();
        buildUi();
        requestPermissionsIfNeeded();
        startEverythingPossible();

        handler.post(loop);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.setBackgroundColor(Color.rgb(5, 7, 10));

        scroll.addView(root);

        titleView = text(22, Color.WHITE);
        titleView.setGravity(Gravity.CENTER);
        titleView.setText("ШАГОМЕР КУБ-125 / ФИЛЬТР ТРЯСКИ");
        root.addView(titleView);

        timeView = text(15, Color.rgb(160, 210, 255));
        mainView = text(16, Color.rgb(235, 245, 255));
        signalView = text(15, Color.rgb(180, 255, 200));
        gpsView = text(15, Color.rgb(255, 230, 170));
        netView = text(15, Color.rgb(210, 190, 255));
        fileView = text(12, Color.rgb(160, 160, 160));

        root.addView(timeView);
        root.addView(mainView);
        root.addView(signalView);
        root.addView(gpsView);
        root.addView(netView);

        kubVisualizer = new KubVisualizer(this);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(430)
        );
        vp.setMargins(0, dp(8), 0, dp(8));
        root.addView(kubVisualizer, vp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button pause = button("ПАУЗА/ПУСК");
        Button reset = button("СБРОС");

        buttons.addView(pause, new LinearLayout.LayoutParams(0, dp(52), 1));
        buttons.addView(reset, new LinearLayout.LayoutParams(0, dp(52), 1));

        root.addView(buttons);

        pause.setOnClickListener(v -> running = !running);
        reset.setOnClickListener(v -> resetRun());

        root.addView(fileView);

        setContentView(scroll);
    }

    private TextView text(int size, int color) {
        TextView tv = new TextView(this);
        tv.setTextSize(size);
        tv.setTextColor(color);
        tv.setPadding(0, dp(3), 0, dp(3));
        return tv;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(25, 55, 85));
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void requestPermissionsIfNeeded() {
        List<String> list = new ArrayList<>();

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            list.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= 29) {
            if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.ACTIVITY_RECOGNITION);
            }
        }

        if (!list.isEmpty()) {
            requestPermissions(list.toArray(new String[0]), 125);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        startEverythingPossible();
    }

    private void startEverythingPossible() {
        startSensors();
        startLocation();
    }

    private void startSensors() {
        if (sensorsStarted) {
            return;
        }

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        if (sensorManager == null) {
            return;
        }

        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        if (stepCounterSensor != null) {
            usingStepCounter = true;
            sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_NORMAL);
        } else if (stepDetectorSensor != null) {
            usingStepDetector = true;
            sensorManager.registerListener(this, stepDetectorSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        if (accelerometerSensor != null) {
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_GAME);
        }

        if (gyroscopeSensor != null) {
            sensorManager.registerListener(this, gyroscopeSensor, SensorManager.SENSOR_DELAY_UI);
        }

        if (magneticSensor != null) {
            sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_UI);
        }

        sensorsStarted = true;
    }

    private void startLocation() {
        if (locationStarted) {
            return;
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        if (locationManager == null) {
            return;
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000,
                        0.5f,
                        this
                );
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        2000,
                        1.0f,
                        this
                );
            }

            locationStarted = true;
        } catch (Exception ignored) {
        }
    }

    private void createTrackFile() {
        try {
            File dir = new File(getExternalFilesDir(null), "KUB125RunnerReady");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            trackFile = new File(dir, "kub125_ready_" + stamp + ".jsonl");
        } catch (Exception e) {
            trackFile = new File(getFilesDir(), "kub125_ready.jsonl");
        }
    }

    private void resetRun() {
        steps = 0;
        gpsDistanceMeters = 0.0;
        lastDistanceLat = Double.NaN;
        lastDistanceLon = Double.NaN;
        startMs = System.currentTimeMillis();

        if (stepCurrent >= 0) {
            stepBase = stepCurrent;
        }

        denoiser.reset();
        locationFilter.reset();
        kubVisualizer.clear();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor == null) {
            return;
        }

        int type = e.sensor.getType();

        if (type == Sensor.TYPE_STEP_COUNTER) {
            stepCurrent = e.values[0];

            if (stepBase < 0) {
                stepBase = stepCurrent;
            }

            steps = Math.max(0, (int) (stepCurrent - stepBase));
            return;
        }

        if (type == Sensor.TYPE_STEP_DETECTOR) {
            if (running) {
                steps++;
            }
            return;
        }

        if (type == Sensor.TYPE_ACCELEROMETER) {
            double ax = e.values[0];
            double ay = e.values[1];
            double az = e.values[2];

            double magnitude = Math.sqrt(ax * ax + ay * ay + az * az);
            double useful = Math.abs(magnitude - 9.80665);

            KubDenoiser.Result r = denoiser.add(useful);

            rawSignal = r.raw;
            cleanSignal = r.clean;
            signalQuality = r.quality;

            kubVisualizer.addSample((float) rawSignal, (float) cleanSignal, (float) signalQuality);

            if (!usingStepCounter && !usingStepDetector) {
                long now = System.currentTimeMillis();

                if (running && cleanSignal > 1.05 && now - lastAccelStepMs > 350) {
                    steps++;
                    lastAccelStepMs = now;
                }
            }

            return;
        }

        if (type == Sensor.TYPE_GYROSCOPE) {
            double x = e.values[0];
            double y = e.values[1];
            double z = e.values[2];
            gyroEnergy = 0.90 * gyroEnergy + 0.10 * Math.sqrt(x * x + y * y + z * z);
            return;
        }

        if (type == Sensor.TYPE_MAGNETIC_FIELD) {
            double x = e.values[0];
            double y = e.values[1];
            double z = e.values[2];
            magEnergy = 0.95 * magEnergy + 0.05 * Math.sqrt(x * x + y * y + z * z);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) {
            return;
        }

        gpsAccuracy = location.hasAccuracy() ? location.getAccuracy() : -1f;

        KubLocationFilter.Point p = locationFilter.update(location);

        filteredLat = p.lat;
        filteredLon = p.lon;
        gpsQuality = p.quality;

        if (running && p.accepted) {
            if (!Double.isNaN(lastDistanceLat) && !Double.isNaN(lastDistanceLon)) {
                float[] d = new float[1];

                Location.distanceBetween(
                        lastDistanceLat,
                        lastDistanceLon,
                        filteredLat,
                        filteredLon,
                        d
                );

                if (d[0] >= 0 && d[0] < 40) {
                    gpsDistanceMeters += d[0];
                }
            }

            lastDistanceLat = filteredLat;
            lastDistanceLon = filteredLon;
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    private void updateScreen() {
        String local = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

        SimpleDateFormat utcFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
        utcFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        String utc = utcFmt.format(new Date());

        long elapsedSec = Math.max(1, (System.currentTimeMillis() - startMs) / 1000);

        double stepDistance = steps * 0.78;
        double finalDistance;

        if (gpsQuality > 0.55 && gpsDistanceMeters > 5) {
            finalDistance = gpsDistanceMeters * 0.75 + stepDistance * 0.25;
        } else {
            finalDistance = Math.max(gpsDistanceMeters, stepDistance);
        }

        double speedKmh = (finalDistance / elapsedSec) * 3.6;

        String phase = running ? "АВТОЗАПИСЬ" : "ПАУЗА";
        String stepMode;

        if (usingStepCounter) {
            stepMode = "аппаратный Step Counter";
        } else if (usingStepDetector) {
            stepMode = "аппаратный Step Detector";
        } else {
            stepMode = "резерв по акселерометру";
        }

        timeView.setText(
                "ФАЗА: " + phase +
                        "\nМестное время: " + local +
                        "\nUTC: " + utc
        );

        mainView.setText(
                "Шаги: " + steps +
                        "\nДистанция: " + fmt(finalDistance / 1000.0) + " км" +
                        "\nСкорость: " + fmt(speedKmh) + " км/ч" +
                        "\nШагомер: " + stepMode
        );

        signalView.setText(
                "Сырой сигнал: " + fmt(rawSignal) +
                        "\nПолезный сигнал после КУБ-фильтра: " + fmt(cleanSignal) +
                        "\nКачество выделения сигнала: " + pct(signalQuality) +
                        "\nГироскоп: " + fmt(gyroEnergy) +
                        "\nМагнитное поле: " + fmt(magEnergy)
        );

        gpsView.setText(
                "GPS: " + coord(filteredLat) + " / " + coord(filteredLon) +
                        "\nТочность GPS: " + (gpsAccuracy >= 0 ? fmt(gpsAccuracy) + " м" : "нет данных") +
                        "\nДоверие GPS: " + pct(gpsQuality)
        );

        netView.setText(
                "Сеть: " + networkName() +
                        "\nДомашний сервер по умолчанию: " + DEFAULT_SERVER
        );

        if (trackFile != null) {
            fileView.setText("Файл записи: " + trackFile.getAbsolutePath());
        }

        kubVisualizer.setStatus(running, networkName(), (float) signalQuality, (float) gpsQuality);
    }

    private String buildJson() {
        SimpleDateFormat utcFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        utcFmt.setTimeZone(TimeZone.getTimeZone("UTC"));

        double stepDistance = steps * 0.78;
        double finalDistance;

        if (gpsQuality > 0.55 && gpsDistanceMeters > 5) {
            finalDistance = gpsDistanceMeters * 0.75 + stepDistance * 0.25;
        } else {
            finalDistance = Math.max(gpsDistanceMeters, stepDistance);
        }

        return "{"
                + "\"utc\":\"" + utcFmt.format(new Date()) + "\","
                + "\"steps\":" + steps + ","
                + "\"distance_km\":" + json(finalDistance / 1000.0) + ","
                + "\"gps_distance_km\":" + json(gpsDistanceMeters / 1000.0) + ","
                + "\"step_distance_km\":" + json(stepDistance / 1000.0) + ","
                + "\"lat\":" + json(filteredLat) + ","
                + "\"lon\":" + json(filteredLon) + ","
                + "\"gps_accuracy_m\":" + json(gpsAccuracy) + ","
                + "\"gps_quality\":" + json(gpsQuality) + ","
                + "\"raw_signal\":" + json(rawSignal) + ","
                + "\"clean_signal\":" + json(cleanSignal) + ","
                + "\"signal_quality\":" + json(signalQuality) + ","
                + "\"gyro_energy\":" + json(gyroEnergy) + ","
                + "\"mag_energy\":" + json(magEnergy) + ","
                + "\"network\":\"" + esc(networkName()) + "\""
                + "}";
    }

    private void appendLine(String s) {
        if (trackFile == null) {
            return;
        }

        try (BufferedWriter w = new BufferedWriter(new FileWriter(trackFile, true))) {
            w.write(s);
            w.newLine();
        } catch (Exception ignored) {
        }
    }

    private void sendToServer(String json) {
        if (sendingNow) {
            return;
        }

        String net = networkName();

        if ("нет сети".equals(net)) {
            return;
        }

        sendingNow = true;

        new Thread(() -> {
            try {
                URL url = new URL(DEFAULT_SERVER);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(2500);
                c.setReadTimeout(2500);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                byte[] body = json.getBytes("UTF-8");

                OutputStream os = c.getOutputStream();
                os.write(body);
                os.flush();
                os.close();

                c.getResponseCode();
                c.disconnect();
            } catch (Exception ignored) {
            }

            runOnUiThread(() -> sendingNow = false);
        }).start();
    }

    private String networkName() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

            if (cm == null) {
                return "нет сети";
            }

            Network n = cm.getActiveNetwork();

            if (n == null) {
                return "нет сети";
            }

            NetworkCapabilities c = cm.getNetworkCapabilities(n);

            if (c == null) {
                return "нет сети";
            }

            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return "Wi‑Fi";
            }

            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return "мобильная сеть";
            }

            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return "Ethernet";
            }

            return "другая сеть";
        } catch (Exception e) {
            return "нет сети";
        }
    }

    private String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0.00";
        }

        return String.format(Locale.US, "%.2f", v);
    }

    private String pct(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            v = 0;
        }

        v = Math.max(0, Math.min(1, v));

        return String.format(Locale.US, "%.1f%%", v * 100.0);
    }

    private String coord(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "нет";
        }

        return String.format(Locale.US, "%.7f", v);
    }

    private String json(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "null";
        }

        return String.format(Locale.US, "%.6f", v);
    }

    private String esc(String s) {
        if (s == null) {
            return "";
        }

        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        handler.removeCallbacksAndMessages(null);

        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }

        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception ignored) {
            }
        }
    }

    public static class KubDenoiser {
        private final double[] buffer = new double[128];
        private int index = 0;
        private int count = 0;
        private double clean = 0.0;
        private double noise = 0.0;

        public void reset() {
            index = 0;
            count = 0;
            clean = 0.0;
            noise = 0.0;

            for (int i = 0; i < buffer.length; i++) {
                buffer[i] = 0.0;
            }
        }

        public Result add(double raw) {
            if (Double.isNaN(raw) || Double.isInfinite(raw)) {
                raw = 0.0;
            }

            raw = Math.max(0.0, Math.min(8.0, raw));

            buffer[index] = raw;
            index = (index + 1) % buffer.length;
            count = Math.min(count + 1, buffer.length);

            double m3 = meanLast(3);
            double m5 = meanLast(5);
            double m8 = meanLast(8);
            double m13 = meanLast(13);
            double m21 = meanLast(21);

            double fib = (m3 * 1.0 + m5 * 2.0 + m8 * 3.0 + m13 * 5.0 + m21 * 8.0) / 19.0;

            double localNoise = Math.abs(raw - fib);
            noise = noise * 0.92 + localNoise * 0.08;

            double alpha;

            if (noise > 1.6) {
                alpha = 0.06;
            } else if (noise > 0.8) {
                alpha = 0.14;
            } else {
                alpha = 0.34;
            }

            clean = clean + alpha * (fib - clean);

            double quality = 1.0 - Math.min(1.0, noise / 2.6);

            return new Result(raw, clean, quality);
        }

        private double meanLast(int n) {
            if (count == 0) {
                return 0.0;
            }

            int actual = Math.min(n, count);
            double sum = 0.0;

            for (int i = 0; i < actual; i++) {
                int p = index - 1 - i;

                while (p < 0) {
                    p += buffer.length;
                }

                sum += buffer[p % buffer.length];
            }

            return sum / actual;
        }

        public static class Result {
            public final double raw;
            public final double clean;
            public final double quality;

            public Result(double raw, double clean, double quality) {
                this.raw = raw;
                this.clean = clean;
                this.quality = quality;
            }
        }
    }

    public static class KubLocationFilter {
        private double lat = Double.NaN;
        private double lon = Double.NaN;
        private long lastMs = 0L;

        public void reset() {
            lat = Double.NaN;
            lon = Double.NaN;
            lastMs = 0L;
        }

        public Point update(Location l) {
            double rLat = l.getLatitude();
            double rLon = l.getLongitude();
            float acc = l.hasAccuracy() ? l.getAccuracy() : 50f;
            long now = l.getTime() > 0 ? l.getTime() : System.currentTimeMillis();

            double accQuality = 1.0 - clamp((acc - 5.0) / 45.0, 0.0, 1.0);

            if (Double.isNaN(lat) || Double.isNaN(lon)) {
                lat = rLat;
                lon = rLon;
                lastMs = now;
                return new Point(lat, lon, accQuality, true);
            }

            float[] d = new float[1];

            Location.distanceBetween(lat, lon, rLat, rLon, d);

            double dt = Math.max(0.5, (now - lastMs) / 1000.0);
            double speed = d[0] / dt;

            boolean spike = speed > 9.5 && acc > 18.0;

            if (spike) {
                return new Point(lat, lon, Math.min(accQuality, 0.25), false);
            }

            double alpha = 0.10 + 0.60 * accQuality;

            if (d[0] < 1.5) {
                alpha *= 0.35;
            }

            lat = lat + alpha * (rLat - lat);
            lon = lon + alpha * (rLon - lon);
            lastMs = now;

            double q = accQuality;

            if (speed > 7.0) {
                q *= 0.72;
            }

            return new Point(lat, lon, q, true);
        }

        private double clamp(double v, double a, double b) {
            return Math.max(a, Math.min(b, v));
        }

        public static class Point {
            public final double lat;
            public final double lon;
            public final double quality;
            public final boolean accepted;

            public Point(double lat, double lon, double quality, boolean accepted) {
                this.lat = lat;
                this.lon = lon;
                this.quality = quality;
                this.accepted = accepted;
            }
        }
    }

    public static class KubVisualizer extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Float> raw = new ArrayList<>();
        private final List<Float> clean = new ArrayList<>();

        private boolean running = true;
        private String network = "нет сети";
        private float signalQuality = 0f;
        private float gpsQuality = 0f;

        public KubVisualizer(Context c) {
            super(c);
        }

        public void clear() {
            raw.clear();
            clean.clear();
            invalidate();
        }

        public void addSample(float r, float c, float q) {
            if (raw.size() > 180) {
                raw.remove(0);
                clean.remove(0);
            }

            raw.add(r);
            clean.add(c);
            signalQuality = q;
            invalidate();
        }

        public void setStatus(boolean running, String network, float signalQuality, float gpsQuality) {
            this.running = running;
            this.network = network;
            this.signalQuality = signalQuality;
            this.gpsQuality = gpsQuality;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);

            int w = getWidth();
            int h = getHeight();

            c.drawColor(Color.rgb(7, 10, 15));

            drawPanels(c, w, h);
            drawKub125(c, w, h);
            drawDifferenceMap(c, w, h);
            drawWaves(c, w, h);
            drawLabels(c, w, h);
        }

        private void drawPanels(Canvas c, int w, int h) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(12, 18, 27));
            c.drawRoundRect(8, 8, w - 8, h - 8, 22, 22, p);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(Color.rgb(40, 80, 120));
            c.drawRoundRect(8, 8, w - 8, h - 8, 22, 22, p);
        }

        private void drawKub125(Canvas c, int w, int h) {
            float centerX = w * 0.50f;
            float startY = 35f;
            float cell = Math.min(17f, w / 30f);

            float q = clamp(signalQuality * 0.70f + gpsQuality * 0.30f);

            for (int z = 0; z < 5; z++) {
                for (int y = 0; y < 5; y++) {
                    for (int x = 0; x < 5; x++) {
                        float px = centerX + (x - 2) * cell * 1.8f - (z - 2) * cell * 1.2f;
                        float py = startY + y * cell * 1.35f + (z - 2) * cell * 0.9f;

                        float dist = Math.abs(x - 2) + Math.abs(y - 2) + Math.abs(z - 2);
                        float pulse = clamp(q - dist * 0.045f);

                        int r;
                        int g;
                        int b;

                        if (running) {
                            r = (int) (30 + 60 * pulse);
                            g = (int) (90 + 150 * pulse);
                            b = (int) (130 + 110 * pulse);
                        } else {
                            r = 55;
                            g = 65;
                            b = 80;
                        }

                        p.setStyle(Paint.Style.FILL);
                        p.setColor(Color.rgb(r, g, b));
                        c.drawRoundRect(px, py, px + cell, py + cell, 4, 4, p);

                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(1.5f);
                        p.setColor(Color.argb(150, 220, 245, 255));
                        c.drawRoundRect(px, py, px + cell, py + cell, 4, 4, p);
                    }
                }
            }
        }

        private void drawDifferenceMap(Canvas c, int w, int h) {
            float left = 28f;
            float top = h * 0.38f;
            float size = Math.min(14f, w / 32f);
            int n = 13;

            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    double dx = x - n / 2.0;
                    double dy = y - n / 2.0;
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    double wave = Math.sin(dist * 1.7 + cleanLast() * 2.0);
                    double value = wave * signalQuality;

                    int color;

                    if (value > 0.25) {
                        color = Color.rgb(230, 245, 255);
                    } else if (value < -0.25) {
                        color = Color.rgb(255, 90, 80);
                    } else {
                        color = Color.rgb(55, 75, 95);
                    }

                    p.setStyle(Paint.Style.FILL);
                    p.setColor(color);

                    float px = left + x * size * 1.25f;
                    float py = top + y * size * 1.25f;

                    c.drawRect(px, py, px + size, py + size, p);
                }
            }
        }

        private void drawWaves(Canvas c, int w, int h) {
            if (raw.size() < 2) {
                return;
            }

            float left = 25f;
            float right = w - 25f;
            float baseY = h * 0.80f;
            float amp = h * 0.15f;
            float span = right - left;

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(Color.argb(80, 255, 255, 255));
            c.drawLine(left, baseY, right, baseY, p);

            p.setStrokeWidth(3f);
            p.setColor(Color.rgb(255, 120, 55));

            for (int i = 1; i < raw.size(); i++) {
                float x1 = left + span * (i - 1) / (raw.size() - 1);
                float x2 = left + span * i / (raw.size() - 1);
                float y1 = baseY - clamp(raw.get(i - 1) / 4f) * amp;
                float y2 = baseY - clamp(raw.get(i) / 4f) * amp;
                c.drawLine(x1, y1, x2, y2, p);
            }

            p.setStrokeWidth(4f);
            p.setColor(Color.rgb(70, 230, 255));

            for (int i = 1; i < clean.size(); i++) {
                float x1 = left + span * (i - 1) / (clean.size() - 1);
                float x2 = left + span * i / (clean.size() - 1);
                float y1 = baseY - clamp(clean.get(i - 1) / 4f) * amp;
                float y2 = baseY - clamp(clean.get(i) / 4f) * amp;
                c.drawLine(x1, y1, x2, y2, p);
            }
        }

        private void drawLabels(Canvas c, int w, int h) {
            p.setStyle(Paint.Style.FILL);

            p.setTextSize(25f);
            p.setColor(Color.WHITE);
            c.drawText("3D КУБ-125: гашение шума", 24, 30, p);

            p.setTextSize(22f);
            p.setColor(Color.rgb(190, 220, 255));
            c.drawText("КАРТА РАЗНИЦЫ: полезный сигнал минус шум", 24, h * 0.36f, p);

            p.setTextSize(22f);
            p.setColor(Color.rgb(255, 160, 100));
            c.drawText("оранжевый — сырой сигнал", 24, h - 52, p);

            p.setColor(Color.rgb(90, 230, 255));
            c.drawText("голубой — очищенный сигнал", 24, h - 25, p);

            p.setTextSize(21f);
            p.setColor(Color.rgb(200, 220, 255));
            String state = running ? "работает" : "пауза";
            c.drawText("Статус: " + state + " | Сеть: " + network, 24, h * 0.32f, p);
        }

        private float cleanLast() {
            if (clean.isEmpty()) {
                return 0f;
            }

            return clean.get(clean.size() - 1);
        }

        private float clamp(float v) {
            return Math.max(0f, Math.min(1f, v));
        }
    }
}
