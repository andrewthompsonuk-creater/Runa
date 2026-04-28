package com.example.dogrundetector;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.provider.MediaStore;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_VIDEO = 1001;
    private static final int CAPTURE_VIDEO = 1002;
    private static final int SAMPLE_WIDTH = 160;
    private static final int SAMPLE_HEIGHT = 90;
    private static final double SAMPLE_FPS = 5.0;
    private static final int MAX_SECONDS = 10;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Button pickButton;
    private Button cameraButton;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView resultText;

    static {
        System.loadLibrary("dogrundetector");
    }

    private native String analyzeFramesNative(int[] pixels, int frameCount, int width, int height, double fps);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View createContentView() {
        int padding = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(0xFFF8FAFC);

        TextView title = new TextView(this);
        title.setText("Dog Run Detector");
        title.setTextColor(0xFF111827);
        title.setTextSize(26);
        title.setGravity(Gravity.START);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title, fullWidth());

        TextView subtitle = new TextView(this);
        subtitle.setText("Record a 10 second clip or choose a video. The native C++ detector samples the first 10 seconds and flags likely running frames.");
        subtitle.setTextColor(0xFF475569);
        subtitle.setTextSize(15);
        subtitle.setPadding(0, dp(8), 0, dp(18));
        root.addView(subtitle, fullWidth());

        cameraButton = new Button(this);
        cameraButton.setText("Record 10 second video");
        cameraButton.setTextColor(0xFFFFFFFF);
        cameraButton.setAllCaps(false);
        cameraButton.setBackgroundResource(com.example.dogrundetector.R.drawable.button_primary);
        cameraButton.setOnClickListener(v -> recordVideo());
        root.addView(cameraButton, fullWidthWithBottomMargin(dp(10)));

        pickButton = new Button(this);
        pickButton.setText("Choose video file");
        pickButton.setTextColor(0xFF1E40AF);
        pickButton.setAllCaps(false);
        pickButton.setOnClickListener(v -> pickVideo());
        root.addView(pickButton, fullWidth());

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.setMargins(0, dp(20), 0, dp(6));
        root.addView(progressBar, progressParams);

        statusText = new TextView(this);
        statusText.setText("Waiting for a video.");
        statusText.setTextColor(0xFF334155);
        statusText.setTextSize(15);
        statusText.setPadding(0, dp(18), 0, dp(8));
        root.addView(statusText, fullWidth());

        ScrollView scrollView = new ScrollView(this);
        resultText = new TextView(this);
        resultText.setTextColor(0xFF0F172A);
        resultText.setTextSize(14);
        resultText.setLineSpacing(0, 1.15f);
        resultText.setText("Results will appear here.");
        scrollView.addView(resultText);

        LinearLayout.LayoutParams scrollParams = fullWidth();
        scrollParams.weight = 1;
        root.addView(scrollView, scrollParams);
        return root;
    }

    private void recordVideo() {
        Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, MAX_SECONDS);
        intent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
        if (intent.resolveActivity(getPackageManager()) == null) {
            showDone("No camera app found.", "Install or enable a camera app, then try again.");
            return;
        }
        startActivityForResult(intent, CAPTURE_VIDEO);
    }

    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != PICK_VIDEO && requestCode != CAPTURE_VIDEO) || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        if (requestCode == PICK_VIDEO && (data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
        }
        analyzeVideo(uri);
    }

    private void analyzeVideo(Uri uri) {
        progressBar.setVisibility(View.VISIBLE);
        pickButton.setEnabled(false);
        cameraButton.setEnabled(false);
        statusText.setText("Sampling frames...");
        resultText.setText("");

        executor.execute(() -> {
            try {
                FrameBatch batch = sampleVideo(uri);
                runOnUiThread(() -> statusText.setText("Running native detector..."));
                String json = analyzeFramesNative(batch.pixels, batch.frameCount, SAMPLE_WIDTH, SAMPLE_HEIGHT, SAMPLE_FPS);
                String readable = formatResults(json, batch.frameCount);
                runOnUiThread(() -> showDone("Analysis complete.", readable));
            } catch (Exception error) {
                runOnUiThread(() -> showDone("Could not analyze this video.", error.getMessage()));
            }
        });
    }

    private FrameBatch sampleVideo(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        List<int[]> sampledFrames = new ArrayList<>();
        try {
            retriever.setDataSource(this, uri);
            String durationValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = durationValue == null ? MAX_SECONDS * 1000L : Long.parseLong(durationValue);
            long cappedDurationMs = Math.min(durationMs, MAX_SECONDS * 1000L);
            int frameCount = Math.max(1, (int) Math.ceil(cappedDurationMs / 1000.0 * SAMPLE_FPS));

            for (int i = 0; i < frameCount; i++) {
                long timeUs = (long) ((i / SAMPLE_FPS) * 1_000_000L);
                Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame == null) {
                    continue;
                }
                Bitmap scaled = Bitmap.createScaledBitmap(frame, SAMPLE_WIDTH, SAMPLE_HEIGHT, true);
                int[] pixels = new int[SAMPLE_WIDTH * SAMPLE_HEIGHT];
                scaled.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT);
                sampledFrames.add(pixels);
                if (scaled != frame) {
                    scaled.recycle();
                }
                frame.recycle();
            }
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }

        int frameCount = sampledFrames.size();
        if (frameCount == 0) {
            throw new IllegalStateException("No frames could be extracted from the selected video.");
        }

        int[] allPixels = new int[frameCount * SAMPLE_WIDTH * SAMPLE_HEIGHT];
        int offset = 0;
        for (int[] frame : sampledFrames) {
            System.arraycopy(frame, 0, allPixels, offset, frame.length);
            offset += frame.length;
        }
        return new FrameBatch(allPixels, frameCount);
    }

    private String formatResults(String json, int sampledFrameCount) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray frames = root.getJSONArray("frames");
            List<String> lines = new ArrayList<>();
            lines.add(String.format(Locale.US, "Sampled %d frames at %.1f fps.", sampledFrameCount, SAMPLE_FPS));
            lines.add("");

            if (frames.length() == 0) {
                lines.add("No running-dog frames were detected.");
            } else {
                lines.add("Likely running frames:");
                for (int i = 0; i < frames.length(); i++) {
                    JSONObject frame = frames.getJSONObject(i);
                    lines.add(String.format(
                            Locale.US,
                            "%d. %.2fs  score %.2f  speed %.2f",
                            i + 1,
                            frame.getInt("timeMs") / 1000.0,
                            frame.getDouble("score"),
                            frame.getDouble("speed")));
                }
            }

            String note = root.optString("note");
            if (!TextUtils.isEmpty(note)) {
                lines.add("");
                lines.add(note);
            }
            return TextUtils.join("\n", lines);
        } catch (Exception error) {
            return "Native result:\n" + json;
        }
    }

    private void showDone(String status, String result) {
        progressBar.setVisibility(View.GONE);
        pickButton.setEnabled(true);
        cameraButton.setEnabled(true);
        statusText.setText(status);
        resultText.setText(result);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fullWidthWithBottomMargin(int bottomMargin) {
        LinearLayout.LayoutParams params = fullWidth();
        params.setMargins(0, 0, 0, bottomMargin);
        return params;
    }

    private static final class FrameBatch {
        final int[] pixels;
        final int frameCount;

        FrameBatch(int[] pixels, int frameCount) {
            this.pixels = pixels;
            this.frameCount = frameCount;
        }
    }
}
