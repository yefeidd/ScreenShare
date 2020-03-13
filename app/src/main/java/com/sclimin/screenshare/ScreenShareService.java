package com.sclimin.screenshare;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.github.faucamp.simplertmp.RtmpHandler;

import net.ossrs.yasea.SrsFlvMuxer;

import java.io.IOException;
import java.net.SocketException;

public class ScreenShareService extends Service implements RtmpHandler.RtmpListener {

    private static final String TAG = "ScreenCaptureService";

    private static final int NOTIFICATION_ID = 0x100;
    private static final String NOTIFICATION_CHANNEL_ID = "notification-channel-0";
    private static final String NOTIFICATION_NAME = "send-stream";
    private static final String KEY_OPT = "opt";

    private static final int OPT_STOP = 1;

    private DisplayMetrics mMetrics;

    private Intent mMediaProjectionData;
    private VirtualDisplay mVirtualDisplay;

    private Handler mHandler;

    private PushStreamThread mThread;

    private SrsFlvMuxer mMuxer;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotification();
        App.service = this;

        if (App.listener != null) {
            App.listener.onServerStateChange();
        }

        mMetrics = getResources().getDisplayMetrics();
        mHandler = new Handler(Looper.getMainLooper());
        mMuxer = new SrsFlvMuxer(new RtmpHandler(this));
        mMuxer.setVideoResolution(mMetrics.widthPixels, mMetrics.heightPixels);
        mMuxer.start("rtmp://" + App.ip + "/live/test");
        Log.i(TAG, "onCreate");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mThread != null) {
            try {
                mThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        stopForeground(true);
        Log.i(TAG, "Destroy");

        App.service = null;
        if (App.listener != null) {
            App.listener.onServerStateChange();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new Binder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent.getIntExtra(KEY_OPT, 0) == OPT_STOP) {
            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
                mVirtualDisplay = null;
            }
            mThread.endOfStream();
        } else {
            int resultCode = intent.getIntExtra("result-code", Activity.RESULT_CANCELED);
            mMediaProjectionData = intent.getParcelableExtra("data");
            if (resultCode == Activity.RESULT_OK && mMediaProjectionData != null
                    && mThread == null) {

                mThread = new PushStreamThread();
                mThread.start();
            }
        }
        return START_STICKY;
    }

    private void createVirtualDisplay(Surface surface) {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            return;
        }

        MediaProjection mp = mpm.getMediaProjection(Activity.RESULT_OK, mMediaProjectionData);
        if (mp == null) {
            return;
        }

        mVirtualDisplay = mp.createVirtualDisplay(
                "My Screan",
                mMetrics.widthPixels,
                mMetrics.heightPixels,
                mMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null);
    }

    private void createNotification() {
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_NAME,
                NotificationManager.IMPORTANCE_LOW);

        NotificationManagerCompat.from(this)
                .createNotificationChannel(channel);

        PendingIntent pi = PendingIntent.getForegroundService(
                this,
                0,
                new Intent(this, ScreenShareService.class)
                        .putExtra(KEY_OPT, OPT_STOP),
                PendingIntent.FLAG_CANCEL_CURRENT
        );
        startForeground(
                NOTIFICATION_ID,
                new NotificationCompat.Builder(this, channel.getId())
                        .setContentIntent(pi)
                        .setContentTitle("屏幕推流")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentText("点击停止屏幕推流")
                        .setWhen(System.currentTimeMillis())
                        .setDefaults(NotificationCompat.DEFAULT_SOUND)
                        .build()
        );
    }

    private class PushStreamThread extends Thread {

        private Surface mSurface;
        private boolean mEndOfStream;

        private int mFrameCountPerGop;

        @Override
        public void run() {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int videoTrack = 0;
            MediaCodec codec = null;

            mMuxer.setVideoResolution(mMetrics.widthPixels, mMetrics.heightPixels);
            try {
                codec = createMediaCodec();
                codec.start();

                mHandler.post(() -> createVirtualDisplay(mSurface));

                while (true) {
                    int index = codec.dequeueOutputBuffer(info, 10);
                    if (index >= 0) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                            mFrameCountPerGop = 0;
                            System.out.println("---> key frame");
                        }
                        else {
                            if (mFrameCountPerGop++ == 30) {
                                codec.setParameters(params);
                            }
                        }
                        mMuxer.writeSampleData(videoTrack, codec.getOutputBuffer(index), info);
                        codec.releaseOutputBuffer(index, false);

                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            return;
                        }
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat format = codec.getOutputFormat();
                        videoTrack = mMuxer.addTrack(format);
                    }

                    if (mEndOfStream) {
                        codec.signalEndOfInputStream();
                        mEndOfStream = false;
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
                mMuxer.stop();
                mHandler.post(ScreenShareService.this::stopSelf);
            }
        }

        private MediaCodec createMediaCodec() throws IOException {
            MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, mMetrics.widthPixels, mMetrics.heightPixels);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 800_000);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mSurface = codec.createInputSurface();
            return codec;
        }

        private void endOfStream() {
            mEndOfStream = true;
        }
    }

    @Override
    public void onRtmpConnecting(String msg) {

    }

    @Override
    public void onRtmpConnected(String msg) {

    }

    @Override
    public void onRtmpVideoStreaming() {

    }

    @Override
    public void onRtmpAudioStreaming() {

    }

    @Override
    public void onRtmpStopped() {

    }

    @Override
    public void onRtmpDisconnected() {

    }

    @Override
    public void onRtmpVideoFpsChanged(double fps) {

    }

    @Override
    public void onRtmpVideoBitrateChanged(double bitrate) {

    }

    @Override
    public void onRtmpAudioBitrateChanged(double bitrate) {

    }

    @Override
    public void onRtmpSocketException(SocketException e) {
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        mThread.endOfStream();
    }

    @Override
    public void onRtmpIOException(IOException e) {

    }

    @Override
    public void onRtmpIllegalArgumentException(IllegalArgumentException e) {

    }

    @Override
    public void onRtmpIllegalStateException(IllegalStateException e) {

    }
}
