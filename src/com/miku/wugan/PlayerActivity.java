package com.miku.wugan;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

/**
 * 内置视频播放器：VideoView + MediaController（自带进度拖动/快进），
 * 原生支持 m3u8/HLS。右上角"横屏"按钮一键切换横竖屏。
 */
public class PlayerActivity extends Activity {

    private VideoView videoView;
    private Button rotateButton;
    private boolean landscape = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_player);

        videoView = findViewById(R.id.video_view);
        rotateButton = findViewById(R.id.rotate_button);

        MediaController mc = new MediaController(this);
        mc.setAnchorView(videoView);
        videoView.setMediaController(mc);

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

        String url = getIntent().getStringExtra("url");
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "没有可播放的地址", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        videoView.setVideoURI(Uri.parse(url));
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                videoView.start();
            }
        });
        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Toast.makeText(PlayerActivity.this,
                        "播放失败，视频源可能已失效或不支持", Toast.LENGTH_LONG).show();
                return true;
            }
        });
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
        if (videoView != null) {
            videoView.stopPlayback();
        }
        super.onDestroy();
    }
}
