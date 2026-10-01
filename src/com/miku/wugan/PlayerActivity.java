package com.miku.wugan;

import android.Manifest;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

/**
 * 内置视频播放器：VideoView + MediaController（进度拖动/播放暂停），原生支持 m3u8/HLS。
 * v9：右上角下载视频按钮。
 * v10：双击左/右半屏快退/快进 10 秒；倍速按钮（0.5x~2x 循环）；锁定按钮（锁住后只留解锁键，防误触）。
 * v11：新布局——左上标题 + 右侧竖排圆钮（播放/暂停、倍速、锁定、画中画、旋转、下载）；
 *     画中画（Android 8.0+，小窗继续播）。
 */
public class PlayerActivity extends Activity {

    private static final int SKIP_MS = 10_000;
    private static final float[] SPEEDS = {0.5f, 1.0f, 1.25f, 1.5f, 2.0f};
    private static final String[] SPEED_LABELS =
            {"0.5x", "1.0x", "1.25x", "1.5x", "2.0x"};

    private VideoView videoView;
    private MediaController mediaController;
    private MediaPlayer mediaPlayer;
    private LinearLayout sideBar;
    private TextView titleView;
    private ImageButton playButton;
    private Button speedButton;
    private View touchBlocker;
    private ImageButton unlockButton;
    private TextView skipHint;

    private boolean landscape = false;
    private boolean locked = false;
    private boolean inPip = false;
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
        sideBar = findViewById(R.id.side_bar);
        titleView = findViewById(R.id.title_view);
        playButton = findViewById(R.id.play_button);
        speedButton = findViewById(R.id.speed_button);
        ImageButton lockButton = findViewById(R.id.lock_button);
        ImageButton pipButton = findViewById(R.id.pip_button);
        ImageButton rotateButton = findViewById(R.id.rotate_button);
        ImageButton downloadButton = findViewById(R.id.download_button);
        touchBlocker = findViewById(R.id.touch_blocker);
        unlockButton = findViewById(R.id.unlock_button);
        skipHint = findViewById(R.id.skip_hint);

        // v11.0：标题（页面标题优先，取不到就用链接文件名）
        String title = getIntent().getStringExtra("title");
        String url = getIntent().getStringExtra("url");
        if (title == null || title.isEmpty()) {
            title = fileNameOf(url);
        }
        if (title == null || title.isEmpty()) {
            titleView.setVisibility(View.GONE);
        } else {
            titleView.setText(title);
        }

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

        // v11.0：播放/暂停圆钮
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
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

        // v11.0：画中画
        pipButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enterPip();
            }
        });

        rotateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                landscape = !landscape;
                setRequestedOrientation(landscape
                        ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
        });

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
                updatePlayIcon();
            }
        });
        videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                updatePlayIcon();
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

    /** 播放/暂停切换 */
    private void togglePlay() {
        if (videoView.isPlaying()) {
            videoView.pause();
        } else {
            videoView.start();
        }
        updatePlayIcon();
    }

    private void updatePlayIcon() {
        boolean playing = videoView != null && videoView.isPlaying();
        playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playButton.setContentDescription(getString(
                playing ? R.string.player_pause : R.string.player_play));
    }

    /** 从链接里抠文件名当标题 */
    private static String fileNameOf(String url) {
        if (url == null) {
            return null;
        }
        int q = url.indexOf('?');
        String noQuery = q >= 0 ? url.substring(0, q) : url;
        int s = noQuery.lastIndexOf('/');
        String name = s >= 0 ? noQuery.substring(s + 1) : noQuery;
        try {
            name = java.net.URLDecoder.decode(name, "UTF-8");
        } catch (Exception ignored) {
        }
        return name.isEmpty() ? null : name;
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
        sideBar.setVisibility(lock ? View.GONE : View.VISIBLE);
        titleView.setVisibility(lock ? View.GONE : View.VISIBLE);
        touchBlocker.setVisibility(lock ? View.VISIBLE : View.GONE);
        unlockButton.setVisibility(lock ? View.VISIBLE : View.GONE);
        if (lock && mediaController != null) {
            mediaController.hide();
        }
    }

    /** v11.0：进画中画（小窗继续播） */
    private void enterPip() {
        try {
            PictureInPictureParams p = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .build();
            enterPictureInPictureMode(p);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.pip_failed),
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
                                             Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        inPip = isInPictureInPictureMode;
        int v = inPip ? View.GONE : View.VISIBLE;
        sideBar.setVisibility(locked || inPip ? View.GONE : v);
        titleView.setVisibility(locked || inPip ? View.GONE : v);
        unlockButton.setVisibility(locked && !inPip ? View.VISIBLE : View.GONE);
        if (inPip && mediaController != null) {
            mediaController.hide();
        }
    }

    private void enqueueDownload(String playUrl) {
        Downloader.Task task = Downloader.get(this).enqueue(playUrl);
        // v10.8：下载通知权限（就地申请一次，拒绝也不影响下载）
        DownloadNotifier.ensurePermission(this);
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
        // 画中画时不暂停，小窗继续播
        if (!inPip && videoView != null) {
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
