package com.trashcam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.hardware.camera2.*;
import android.media.*;
import android.os.*;
import android.provider.MediaStore;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TrashCam";
    private static final int REQUEST_PERMISSIONS = 1;

    // ─── Trash resolution presets ─────────────────────────────────────────────
    // Video: [width, height]
    private static final int[][] VIDEO_PRESETS = {
        {4,   4},      // 0 - pure chaos
        {8,   8},      // 1
        {16,  16},     // 2
        {32,  32},     // 3
        {64,  64},     // 4
        {128, 128}     // 5 - "HD"
    };

    // Photo uses same
    private static final int[][] PHOTO_PRESETS = {
        {4,   4},
        {8,   8},
        {16,  16},
        {32,  32},
        {64,  64},
        {128, 128}
    };

    // Audio sample rates (Hz) - criminally low
    private static final int[] AUDIO_RATES = {
        200,    // 0 - basically silence
        400,    // 1
        800,    // 2
        1600,   // 3
        4000,   // 4
        8000    // 5 - "okay"
    };

    // ─── UI ───────────────────────────────────────────────────────────────────
    private TextureView textureView;
    private ImageView pixelatedOverlay;
    private SeekBar videoResSeek, audioQualitySeek, photoResSeek;
    private TextView videoResValue, audioQualityValue, photoResValue;
    private TextView resLabel, recIndicator;
    private com.google.android.material.button.MaterialButton btnPhoto, btnRecord, btnFolder, btnFrameDrop, btnSwitchCam;

    // ─── Camera2 ──────────────────────────────────────────────────────────────
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);
    private String cameraId;

    // ─── State ────────────────────────────────────────────────────────────────
    private int videoResIndex = 0;
    private int audioRateIndex = 0;
    private int photoResIndex = 0;
    private boolean isRecording = false;
    private boolean isFrameDropEnabled = false;
    private boolean useFrontCamera = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable previewUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updatePixelatedPreview();
            mainHandler.postDelayed(this, 66); // ~15 fps preview
        }
    };

    private void updatePixelatedPreview() {
        if (textureView.isAvailable()) {
            Bitmap bmp = textureView.getBitmap();
            if (bmp != null) {
                // Use video res for preview as it's the most common "crappy" target
                int[] res = VIDEO_PRESETS[videoResIndex];
                Bitmap trash = Bitmap.createScaledBitmap(bmp, res[0], res[1], false);
                pixelatedOverlay.setImageBitmap(trash);
                // No need to upscale, ImageView scaleType="fitCenter" will do it (pixelated by default if tiny)
                bmp.recycle();
            }
        }
    }

    // ─── Recording ────────────────────────────────────────────────────────────
    private Thread recordingThread;
    private volatile boolean stopRecording = false;
    // We capture frames from texture + raw audio separately, mux into WAV+raw video, saved as separate files
    // (MediaMuxer can't handle arbitrary tiny resolutions, so we save raw frames + raw audio separately)

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Immersive fullscreen
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        bindViews();
        setupSliders();
        setupButtons();

        checkPermissions();
    }

    private void bindViews() {
        textureView    = findViewById(R.id.textureView);
        pixelatedOverlay = findViewById(R.id.pixelatedOverlay);
        videoResSeek   = findViewById(R.id.videoResSeek);
        audioQualitySeek = findViewById(R.id.audioQualitySeek);
        photoResSeek   = findViewById(R.id.photoResSeek);
        videoResValue  = findViewById(R.id.videoResValue);
        audioQualityValue = findViewById(R.id.audioQualityValue);
        photoResValue  = findViewById(R.id.photoResValue);
        resLabel       = findViewById(R.id.resLabel);
        recIndicator   = findViewById(R.id.recIndicator);
        btnPhoto       = findViewById(R.id.btnPhoto);
        btnRecord      = findViewById(R.id.btnRecord);
        btnFolder      = findViewById(R.id.btnFolder);
        btnFrameDrop   = findViewById(R.id.btnFrameDrop);
        btnSwitchCam   = findViewById(R.id.btnSwitchCam);
    }

    // ─── Sliders ──────────────────────────────────────────────────────────────
    private void setupSliders() {
        videoResSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean user) {
                videoResIndex = p;
                int[] r = VIDEO_PRESETS[p];
                videoResValue.setText(r[0] + "×" + r[1]);
                resLabel.setText(r[0] + "×" + r[1]);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        audioQualitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean user) {
                audioRateIndex = p;
                audioQualityValue.setText(AUDIO_RATES[p] + " Hz");
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        photoResSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean user) {
                photoResIndex = p;
                int[] r = PHOTO_PRESETS[p];
                photoResValue.setText(r[0] + "×" + r[1]);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        // Init labels
        videoResValue.setText("4×4");
        audioQualityValue.setText("200 Hz");
        photoResValue.setText("4×4");
    }

    // ─── Buttons ──────────────────────────────────────────────────────────────
    private void setupButtons() {
        btnPhoto.setOnClickListener(v -> takeTrashPhoto());
        btnRecord.setOnClickListener(v -> {
            if (isRecording) stopVideoRecording();
            else startVideoRecording();
        });
        btnFolder.setOnClickListener(v -> chooseFolder());
        btnFrameDrop.setOnClickListener(v -> toggleFrameDrop());
        btnSwitchCam.setOnClickListener(v -> switchCamera());
    }

    private void switchCamera() {
        useFrontCamera = !useFrontCamera;
        btnSwitchCam.setText(useFrontCamera ? "FRONT" : "REAR");
        closeCamera();
        openCamera();
    }

    private void toggleFrameDrop() {
        isFrameDropEnabled = !isFrameDropEnabled;
        if (isFrameDropEnabled) {
            btnFrameDrop.setText("ON");
            btnFrameDrop.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFFFF3333));
            btnFrameDrop.setTextColor(0xFFFF3333);
        } else {
            btnFrameDrop.setText("OFF");
            btnFrameDrop.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFF888888));
            btnFrameDrop.setTextColor(0xFF888888);
        }
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, 100);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            android.net.Uri treeUri = data.getData();
            if (treeUri != null) {
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getSharedPreferences("TrashCam", MODE_PRIVATE).edit()
                        .putString("save_uri", treeUri.toString()).apply();
                Toast.makeText(this, "Save location updated!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ─── Permissions ──────────────────────────────────────────────────────────
    private void checkPermissions() {
        List<String> needed = new ArrayList<>();
        String[] perms = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        };
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                needed.add(p);
        }
        if (!needed.isEmpty())
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQUEST_PERMISSIONS);
        else
            setupTexture();
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQUEST_PERMISSIONS) {
            boolean ok = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
            if (ok) setupTexture();
            else Toast.makeText(this, "Need camera + mic permissions", Toast.LENGTH_LONG).show();
        }
    }

    // ─── TextureView setup ────────────────────────────────────────────────────
    private void setupTexture() {
        if (textureView.isAvailable()) openCamera();
        else textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) { openCamera(); }
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {}
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) { return true; }
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
        });
    }

    // ─── Camera2 open ─────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private void openCamera() {
        startBackgroundThread();
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String[] idList = manager.getCameraIdList();
            if (idList.length == 0) throw new RuntimeException("No cameras available");
            
            // Search for chosen camera
            cameraId = idList[0];
            int targetFacing = useFrontCamera ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            
            for (String id : idList) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == targetFacing) {
                    cameraId = id;
                    break;
                }
            }

            if (!cameraOpenCloseLock.tryAcquire(5000, TimeUnit.MILLISECONDS))
                throw new RuntimeException("Timeout waiting for camera lock");
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "openCamera error", e);
            Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        public void onOpened(@NonNull CameraDevice cam) {
            cameraOpenCloseLock.release();
            cameraDevice = cam;
            createPreviewSession();
        }
        public void onDisconnected(@NonNull CameraDevice cam) {
            cameraOpenCloseLock.release();
            cam.close();
            cameraDevice = null;
        }
        public void onError(@NonNull CameraDevice cam, int error) {
            cameraOpenCloseLock.release();
            cam.close();
            cameraDevice = null;
        }
    };

    private void createPreviewSession() {
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            // Use a real preview resolution for the SurfaceTexture itself
            // (we'll downsample in software when saving)
            st.setDefaultBufferSize(640, 480);
            Surface previewSurface = new Surface(st);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(previewSurface);

            cameraDevice.createCaptureSession(
                Collections.singletonList(previewSurface),
                new CameraCaptureSession.StateCallback() {
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        if (cameraDevice == null) return;
                        captureSession = session;
                        try {
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            captureSession.setRepeatingRequest(
                                    previewRequestBuilder.build(), null, backgroundHandler);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "preview session error", e);
                        }
                    }
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        Toast.makeText(MainActivity.this, "Camera config failed", Toast.LENGTH_SHORT).show();
                    }
                },
                backgroundHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "createPreviewSession", e);
        }
    }

    // ─── PHOTO ────────────────────────────────────────────────────────────────
    private void takeTrashPhoto() {
        Bitmap preview = textureView.getBitmap();
        if (preview == null) { Toast.makeText(this, "No preview", Toast.LENGTH_SHORT).show(); return; }

        int[] res = PHOTO_PRESETS[photoResIndex];
        int tw = res[0], th = res[1];

        Bitmap trash = Bitmap.createScaledBitmap(preview, tw, th, false);

        new Thread(() -> {
            try {
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String filename = "TRASH_" + tw + "x" + th + "_" + ts + ".jpg";

                String savedUriStr = getSharedPreferences("TrashCam", MODE_PRIVATE).getString("save_uri", null);
                OutputStream os;
                String location;

                if (savedUriStr != null) {
                    DocumentFile pickedDir = DocumentFile.fromTreeUri(this, Uri.parse(savedUriStr));
                    DocumentFile file = pickedDir.createFile("image/jpeg", filename);
                    os = getContentResolver().openOutputStream(file.getUri());
                    location = "Custom Folder";
                } else {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/TrashCam");
                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    os = getContentResolver().openOutputStream(uri);
                    location = "Gallery (DCIM/TrashCam)";
                }

                if (os != null) {
                    trash.compress(Bitmap.CompressFormat.JPEG, 100, os);
                    os.close();
                    runOnUiThread(() -> Toast.makeText(this,
                        "Saved " + tw + "×" + th + " trash photo to " + location + " 🗑️", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "photo save error", e);
                runOnUiThread(() -> Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ─── VIDEO RECORDING ──────────────────────────────────────────────────────
    // Strategy: capture TextureView frames in a loop + AudioRecord in parallel
    // → downsample each frame → write raw RGB frames to file
    // → write raw PCM audio to WAV
    // Both saved separately (gallery for video needs a container; we'll make an
    // MJPEG-in-AVI or just save as individual JPEG frames + WAV for maximum jank)
    // For simplicity and compatibility: we save a proper .mp4 using MediaRecorder
    // but at the lowest resolution MediaRecorder accepts, then we note in the UI.
    // For TRUE sub-16x16 we save frames as individual JPEGs zipped + WAV separately.

    private void startVideoRecording() {
        if (cameraDevice == null) return;
        isRecording = true;
        stopRecording = false;

        int sampleRate = AUDIO_RATES[audioRateIndex];

        runOnUiThread(() -> {
            recIndicator.setVisibility(View.VISIBLE);
            btnRecord.setText("■ STOP");
            btnRecord.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF333333));
            btnPhoto.setEnabled(false);
        });

        recordingThread = new Thread(() -> recordFramesAndAudio(sampleRate));
        recordingThread.start();
    }

    private void recordFramesAndAudio(int sampleRate) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String filename = "TRASH_" + ts + ".mp4";
        String savedUriStr = getSharedPreferences("TrashCam", MODE_PRIVATE).getString("save_uri", null);

        OutputStream os = null;
        File tempFile = null;

        try {
            if (savedUriStr != null) {
                DocumentFile root = DocumentFile.fromTreeUri(this, Uri.parse(savedUriStr));
                DocumentFile file = root.createFile("video/mp4", filename);
                // MediaMuxer needs a file path or FileDescriptor. SAF gives us a Uri.
                // We'll record to a temp file and then copy it to the SAF Uri.
                tempFile = new File(getExternalCacheDir(), "temp_vid.mp4");
            } else {
                File outDir = new File(getExternalFilesDir(null), "TrashCam");
                outDir.mkdirs();
                tempFile = new File(outDir, filename);
            }

            final File finalMuxFile = tempFile;
            MediaMuxer muxer = new MediaMuxer(finalMuxFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // ─── Video Encoder Setup (176x144 QCIF is safe and crappy) ───────
            int videoW = 176, videoH = 144;
            MediaFormat vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoW, videoH);
            vFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
            vFormat.setInteger(MediaFormat.KEY_BIT_RATE, 256000);
            vFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 10);
            vFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            MediaCodec vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            vCodec.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            vCodec.start();

            // ─── Audio Encoder Setup (AAC 44.1kHz) ───────────────────────────
            MediaFormat aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1);
            aFormat.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
            aFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);

            MediaCodec aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            aCodec.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            aCodec.start();

            // ─── Audio Capture ───────────────────────────────────────────────
            int minBufSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize * 4);
            audioRecord.startRecording();

            boolean muxerStarted = false;
            int vTrack = -1, aTrack = -1;
            MediaCodec.BufferInfo vInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();

            long startTime = System.nanoTime();

            // Audio thread
            Thread audioThread = new Thread(() -> {
                byte[] buf = new byte[minBufSize];
                short[] sBuf = new short[minBufSize / 2];
                int trashRatio = 44100 / sampleRate;
                
                while (!stopRecording) {
                    int read = audioRecord.read(sBuf, 0, sBuf.length);
                    if (read > 0) {
                        // Apply trash effect: hold sample value to simulate low rate
                        for (int i = 0; i < read; i++) {
                            int base = (i / trashRatio) * trashRatio;
                            sBuf[i] = sBuf[Math.min(base, read - 1)];
                        }
                        
                        ByteBuffer b = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN);
                        for(int i = 0; i < read; i++) b.putShort(sBuf[i]);
                        byte[] pcm = b.array();

                        int offset = 0;
                        while (offset < pcm.length && !stopRecording) {
                            int inputIdx = aCodec.dequeueInputBuffer(10000);
                            if (inputIdx >= 0) {
                                ByteBuffer inputBuf = aCodec.getInputBuffer(inputIdx);
                                if (inputBuf != null) {
                                    inputBuf.clear();
                                    int toWrite = Math.min(pcm.length - offset, inputBuf.remaining());
                                    inputBuf.put(pcm, offset, toWrite);
                                    long pts = (System.nanoTime() - startTime) / 1000;
                                    aCodec.queueInputBuffer(inputIdx, 0, toWrite, pts, 0);
                                    offset += toWrite;
                                }
                            } else {
                                // Wait a bit for an input buffer to become available
                                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                            }
                        }
                    }
                }
                // EOS
                int idx = aCodec.dequeueInputBuffer(10000);
                if (idx >= 0) aCodec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            });
            audioThread.start();

            // Frame Loop
            byte[] yuvBuf = new byte[videoW * videoH * 3 / 2];
            int[] argb = new int[videoW * videoH];
            int frameCount = 0;
            Random random = new Random();

            while (!stopRecording) {
                if (isFrameDropEnabled && random.nextInt(100) < 30) {
                    // Skip frame (30% chance)
                    Thread.sleep(100);
                    continue;
                }

                Bitmap frame = textureView.getBitmap();
                if (frame != null) {
                    // Double scale for trash look (Dynamic resolution)
                    int[] currentRes = VIDEO_PRESETS[videoResIndex];
                    int tw = currentRes[0];
                    int th = currentRes[1];

                    Bitmap trash = Bitmap.createScaledBitmap(frame, tw, th, false);
                    Bitmap qvga = Bitmap.createScaledBitmap(trash, videoW, videoH, false);
                    qvga.getPixels(argb, 0, videoW, 0, 0, videoW, videoH);
                    encodeYUV420SP(yuvBuf, argb, videoW, videoH);
                    
                    int inputIdx = vCodec.dequeueInputBuffer(10000);
                    if (inputIdx >= 0) {
                        ByteBuffer inputBuf = vCodec.getInputBuffer(inputIdx);
                        inputBuf.clear();
                        inputBuf.put(yuvBuf);
                        long pts = (System.nanoTime() - startTime) / 1000;
                        vCodec.queueInputBuffer(inputIdx, 0, yuvBuf.length, pts, 0);
                    }
                    frame.recycle(); trash.recycle(); qvga.recycle();
                    frameCount++;
                }

                // Process output buffers
                while (true) {
                    int outIdx = vCodec.dequeueOutputBuffer(vInfo, 0);
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        vTrack = muxer.addTrack(vCodec.getOutputFormat());
                        if (vTrack >= 0 && aTrack >= 0 && !muxerStarted) { muxer.start(); muxerStarted = true; }
                    } else if (outIdx >= 0) {
                        if (muxerStarted) muxer.writeSampleData(vTrack, vCodec.getOutputBuffer(outIdx), vInfo);
                        vCodec.releaseOutputBuffer(outIdx, false);
                    } else break;
                }
                while (true) {
                    int outIdx = aCodec.dequeueOutputBuffer(aInfo, 0);
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        aTrack = muxer.addTrack(aCodec.getOutputFormat());
                        if (vTrack >= 0 && aTrack >= 0 && !muxerStarted) { muxer.start(); muxerStarted = true; }
                    } else if (outIdx >= 0) {
                        if (muxerStarted) muxer.writeSampleData(aTrack, aCodec.getOutputBuffer(outIdx), aInfo);
                        aCodec.releaseOutputBuffer(outIdx, false);
                    } else break;
                }
                
                Thread.sleep(100); // ~10fps
            }

            // Cleanup
            audioThread.join();
            vCodec.stop(); vCodec.release();
            aCodec.stop(); aCodec.release();
            audioRecord.stop(); audioRecord.release();
            if (muxerStarted) muxer.stop();
            muxer.release();

            // Copy to final destination if using SAF
            if (savedUriStr != null) {
                DocumentFile root = DocumentFile.fromTreeUri(this, Uri.parse(savedUriStr));
                DocumentFile finalFile = root.createFile("video/mp4", filename);
                try (InputStream in = new FileInputStream(tempFile);
                     OutputStream out = getContentResolver().openOutputStream(finalFile.getUri())) {
                    byte[] b = new byte[8192];
                    int len;
                    while ((len = in.read(b)) > 0) out.write(b, 0, len);
                }
                tempFile.delete();
            }

            final int fc = frameCount;
            runOnUiThread(() -> Toast.makeText(this, "Saved " + fc + " frame trash video!", Toast.LENGTH_SHORT).show());

        } catch (Exception e) {
            Log.e(TAG, "Recording error", e);
            runOnUiThread(() -> Toast.makeText(this, "Video error: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private void encodeYUV420SP(byte[] yuv420sp, int[] argb, int width, int height) {
        int yIndex = 0;
        int uvIndex = width * height;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int pixel = argb[j * width + i];
                int R = (pixel & 0xff0000) >> 16;
                int G = (pixel & 0xff00) >> 8;
                int B = (pixel & 0xff);
                int Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                int U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                int V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;
                yuv420sp[yIndex++] = (byte) Math.max(0, Math.min(255, Y));
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv420sp[uvIndex++] = (byte) Math.max(0, Math.min(255, U));
                    yuv420sp[uvIndex++] = (byte) Math.max(0, Math.min(255, V));
                }
            }
        }
    }

    private void writeWavToStream(OutputStream os, byte[] pcm, int sampleRate) throws IOException {
        int numChannels   = 1;
        int bitsPerSample = 16;
        int byteRate      = sampleRate * numChannels * bitsPerSample / 8;
        int blockAlign    = numChannels * bitsPerSample / 8;
        int dataSize      = pcm.length;
        int chunkSize     = 36 + dataSize;

        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes());
        header.putInt(chunkSize);
        header.put("WAVE".getBytes());
        header.put("fmt ".getBytes());
        header.putInt(16);
        header.putShort((short)1);
        header.putShort((short)numChannels);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort((short)blockAlign);
        header.putShort((short)bitsPerSample);
        header.put("data".getBytes());
        header.putInt(dataSize);

        os.write(header.array());
        os.write(pcm);
    }

    private void stopVideoRecording() {
        stopRecording = true;
        isRecording = false;
        if (recordingThread != null) {
            try { recordingThread.join(5000); } catch (InterruptedException ignored) {}
        }
        runOnUiThread(() -> {
            recIndicator.setVisibility(View.GONE);
            btnRecord.setText("● REC");
            btnRecord.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFFFF3333));
            btnPhoto.setEnabled(true);
        });
    }

    // ─── Background thread ────────────────────────────────────────────────────
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try { backgroundThread.join(); } catch (InterruptedException e) { Log.e(TAG, "", e); }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────
    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (textureView.isAvailable()) openCamera();
        else setupTexture();
        mainHandler.post(previewUpdateRunnable);
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(previewUpdateRunnable);
        if (isRecording) stopVideoRecording();
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            if (cameraDevice != null)   { cameraDevice.close();   cameraDevice = null; }
        } catch (InterruptedException e) {
            Log.e(TAG, "closeCamera interrupted", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }
}
