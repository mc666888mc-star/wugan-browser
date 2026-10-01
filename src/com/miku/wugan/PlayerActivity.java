package com.miku.wugan;

import android.Manifest;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

/**
 * 内置视频播放器：VideoView + MediaController（进度拖动/播放暂停），原生支持 m3u8/HLS。
 * v9：右上角下载视频按钮。
 * v10：双击左/右半屏快退/快进 10 秒；倍速按钮（0.5x~2x 循环）；锁定按钮（锁住后只留解锁键，防误触）。
 */
public class PlayerActivity extends Activity {

    private static final int SKIP_MS = 10_000;
    private static final float[] SPEEDS = {0.5f, 1.0f, 1.25f, 1.5f, 2.0f};
    private static final String[] SPEED_LABELS =
            {"0.5x", "1.0x", "1.25x", "1.5x", "2.0x"};

    private VideoView videoView;
    private MediaController mediaController;
    private MediaPlayer mediaPlayer;
    private LinearLayout topBar;
    private Button rotateButton;
    private Button speedButton;
    private View touchBlocker;
    private Button unlockButton;
    private TextView skipHint;

    private boolean landscape = false;
    private boolean locked = false;
    private int speedIdx = 1; // 默认 1.0x
    private String pendingDownloadUrl;
    private final Handler handler = new Handler();
    private final Runnable hideSkipHint = new Runnable() {
        @Override public void run() {
            skipHint.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_player);

        videoView = findViewById(R.id.video_view);
        topBar = findViewById(R.id.top_bar);
        rotateButton = findViewById(R.id.rotate_button);
        speedButton = findViewById(R.id.speed_button);
        Button lockButton = findViewById(R.id.lock_button);
        Button downloadButton = findViewById(R.id.download_button);
        touchBlocker = findViewById(R.id.touch_blocker);
        unlockButton = findViewById(R.id.unlock_button);
        skipHint = findViewById(R.id.skip_hint);

        mediaController = new MediaController(this);
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);

        // 双击左/右半屏快退/快进（不消费事件，单击仍能唤出 MediaController）
        final GestureDetector gestures = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (locked) {
                            return true;
                        }
                        int half = videoView.getWidth() / 2;
                        skipBy(e.getX() < half ? -SKIP_MS : SKIP_MS);
                        return true;
                    }
                });
        findViewById(R.id.player_root).setOnTouchListener(
                new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        gestures.onTouchEvent(event);
                        return false;
                    }
                });

        rotateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                landscape = !landscape;
                setRequestedOrientation(landscape
                        ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                rotateButton.setText(landscape ? R.string.to_portrait : R.string.rotate);
            }
        });

        speedButton.setText(SPEED_LABELS[speedIdx]);
        speedButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                speedIdx = (speedIdx + 1) % SPEEDS.length;
                speedButton.setText(SPEED_LABELS[speedIdx]);
                applySpeed();
            }
        });

        lockButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setLocked(true);
            }
        });
        unlockButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setLocked(false);
            }
        });

        String url = getIntent().getStringExtra("url");
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_play_url), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        final String playUrl = url;
        downloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT < 29
                        && checkSelfPermission(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    pendingDownloadUrl = playUrl;
                    requestPermissions(new String[]{
                            Manifest.permission.WRITE_EXTERNAL_STORAGE}, 9002);
                    return;
                }
                enqueueDownload(playUrl);
            }
        });
        videoView.setVideoURI(Uri.parse(url));
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mediaPlayer = mp;
                applySpeed();
                videoView.start();
            }
        });
        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Toast.makeText(PlayerActivity.this,
                        getString(R.string.play_failed), Toast.LENGTH_LONG).show();
                return true;
            }
        });
    }

    /** 快进/快退（毫秒，可为负） */
    private void skipBy(int deltaMs) {
        int pos = videoView.getCurrentPosition();
        int target = pos + deltaMs;
        if (target < 0) {
            target = 0;
        }
        int dur = videoView.getDuration();
        if (dur > 0 && target > dur) {
            target = dur;
        }
        videoView.seekTo(target);
        if (mediaController != null) {
            mediaController.hide();
        }
        skipHint.setText(deltaMs < 0 ? "-10s" : "+10s");
        skipHint.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideSkipHint);
        handler.postDelayed(hideSkipHint, 600);
    }

    private void applySpeed() {
        if (mediaPlayer == null) {
            return;
        }
        try {
            PlaybackParams pp = mediaPlayer.getPlaybackParams();
            pp.setSpeed(SPEEDS[speedIdx]);
            mediaPlayer.setPlaybackParams(pp);
        } catch (Exception ignored) {
            // 直播流等不支持变速的场景静默忽略
        }
    }

    private void setLocked(boolean lock) {
        locked = lock;
        topBar.setVisibility(lock ? View.GONE : View.VISIBLE);
        touchBlocker.setVisibility(lock ? View.VISIBLE : View.GONE);
        unlockButton.setVisibility(lock ? View.VISIBLE : View.GONE);
        if (lock && mediaController != null) {
            mediaController.hide();
        }
    }

    private void enqueueDownload(String playUrl) {
        Downloader.Task task = Downloader.get(this).enqueue(playUrl);
        Toast.makeText(this, getString(R.string.dl_enqueued, task.fileName),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == 9002 && pendingDownloadUrl != null
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enqueueDownload(pendingDownloadUrl);
        }
        pendingDownloadUrl = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView != null) {
            videoView.pause();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(hideSkipHint);
        if (videoView != null) {
            videoView.stopPlayback();
        }
        super.onDestroy();
    }
}
