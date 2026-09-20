package com.zz.douyin.hook;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class VideoDownloader {
    private static AlertDialog chooser;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "DouyinNoWatermarkDownload");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<String> ACTIVE =
            Collections.synchronizedSet(new HashSet<>());

    private VideoDownloader() {
    }

    static void chooseDownload(Activity activity, FeedContentTracker.Snapshot snapshot) {
        if (!ImmersiveUi.isModuleEnabled() || activity == null
                || activity.isFinishing() || activity.isDestroyed()) return;
        if (snapshot == null || !snapshot.hasDownloadUrl()) {
            showToast(activity, "当前视频地址暂不可用");
            return;
        }
        dismissChooser();
        chooser = new AlertDialog.Builder(activity)
                .setTitle("下载当前内容")
                .setItems(new String[]{"视频（无水印 MP4）", "音频（MP3）"},
                        (dialog, which) -> download(activity, snapshot, which == 1))
                .setNegativeButton("取消", null)
                .create();
        chooser.setOnDismissListener(dialog -> { if (chooser == dialog) chooser = null; });
        chooser.show();
    }

    static void dismissChooser() {
        if (chooser != null) {
            chooser.dismiss();
            chooser = null;
        }
    }

    static void ensureEnabled() throws IOException {
        if (!ImmersiveUi.isModuleEnabled()) throw new IOException("功能总开关已关闭，下载已取消");
    }

    private static void download(Activity activity, FeedContentTracker.Snapshot snapshot, boolean audio) {
        if (!ImmersiveUi.isModuleEnabled()) return;
        if (activity == null || snapshot == null || !snapshot.hasDownloadUrl()) {
            showToast(activity, "当前视频地址暂不可用");
            return;
        }
        Context context = activity.getApplicationContext();
        FeedContentTracker.PlayUrl first = snapshot.playUrls.get(0);
        String key = snapshot.aid + ':' + first.url.hashCode() + ':' + audio;
        if (!ACTIVE.add(key)) {
            showToast(activity, "当前" + (audio ? "音频" : "视频") + "正在下载");
            return;
        }

        String fileName = buildFileName(snapshot.aid, audio);
        showToast(activity, audio ? "开始下载音频，完成后保存为 MP3" : "开始下载无水印视频");
        Log.i(DouyinModule.TAG,
                "download requested: aid=" + snapshot.aid
                        + " candidates=" + snapshot.playUrls.size()
                        + " primary=" + first.source + " format=" + (audio ? "mp3" : "mp4"));

        WORKER.execute(() -> {
            DownloadResult result;
            try {
                ensureEnabled();
                if (audio) {
                    result = downloadAudio(context, snapshot, fileName);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    result = downloadWithMediaStore(context, snapshot, fileName);
                } else {
                    result = enqueueWithDownloadManager(context, snapshot, fileName);
                }
            } catch (Throwable error) {
                Log.e(DouyinModule.TAG,
                        "video download failed unexpectedly: aid=" + snapshot.aid,
                        error);
                result = DownloadResult.failed();
            } finally {
                ACTIVE.remove(key);
            }

            DownloadResult finalResult = result;
            MAIN.post(() -> {
                if (finalResult.completed) {
                    showToast(context,
                            "下载完成：Download/" + finalResult.fileName);
                } else if (finalResult.queued) {
                    showToast(context,
                            "已加入下载队列：Download/" + finalResult.fileName);
                } else {
                    showToast(context, !ImmersiveUi.isModuleEnabled()
                            ? "功能总开关已关闭，下载已取消"
                            : "下载失败" + (finalResult.error == null
                            ? "，请稍后重试" : "：" + finalResult.error));
                }
            });
        });
    }

    private static DownloadResult downloadAudio(Context context,
                                                FeedContentTracker.Snapshot snapshot,
                                                String fileName) throws IOException {
        File source = File.createTempFile("douxianren-source-", ".mp4", context.getCacheDir());
        File mp3 = null;
        try {
            mp3 = File.createTempFile("douxianren-audio-", ".mp3", context.getCacheDir());
            IOException lastError = null;
            for (FeedContentTracker.PlayUrl candidate : snapshot.playUrls) {
                ensureEnabled();
                HttpURLConnection connection = null;
                try {
                    connection = openConnection(candidate.url);
                    int status = connection.getResponseCode();
                    if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
                    long bytes;
                    try (InputStream input = connection.getInputStream();
                         OutputStream output = new FileOutputStream(source)) {
                        bytes = copy(input, output);
                    }
                    long expected = connection.getContentLengthLong();
                    if (bytes == 0 || (expected > 0 && bytes != expected)) {
                        throw new IOException("视频源下载不完整");
                    }
                    MAIN.post(() -> showToast(context, "正在提取音频并转换为 MP3…"));
                    AudioTranscoder.toMp3(source, mp3);
                    ensureEnabled();
                    publishAudio(context, mp3, fileName);
                    Log.i(DouyinModule.TAG, "audio download completed: aid=" + snapshot.aid
                            + " bytes=" + mp3.length() + " file=" + fileName);
                    return DownloadResult.completed(fileName);
                } catch (IOException | RuntimeException error) {
                    lastError = new IOException(error.getMessage(), error);
                    Log.w(DouyinModule.TAG, "audio candidate failed: " + candidate.source, error);
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
            return DownloadResult.failed(lastError == null ? null : lastError.getMessage());
        } finally {
            if (!source.delete()) Log.w(DouyinModule.TAG, "could not delete temporary source");
            if (mp3 != null && !mp3.delete()) Log.w(DouyinModule.TAG, "could not delete temporary MP3");
        }
    }

    private static void publishAudio(Context context, File mp3, String fileName) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (destination == null) throw new IOException("无法创建音频文件");
            boolean published = false;
            try {
                try (InputStream input = new FileInputStream(mp3);
                     OutputStream output = resolver.openOutputStream(destination, "w")) {
                    if (output == null) throw new IOException("无法写入音频文件");
                    copy(input, output);
                }
                ensureEnabled();
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(destination, values, null, null) != 1) {
                    throw new IOException("无法保存音频文件");
                }
                published = true;
            } finally {
                if (!published) resolver.delete(destination, null, null);
            }
        } else {
            File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建 Download 目录");
            File destination = new File(directory, fileName);
            boolean published = false;
            try {
                try (InputStream input = new FileInputStream(mp3);
                     OutputStream output = new FileOutputStream(destination)) {
                    copy(input, output);
                }
                published = true;
                MediaScannerConnection.scanFile(context,
                        new String[]{destination.getAbsolutePath()}, new String[]{"audio/mpeg"}, null);
            } finally {
                if (!published && !destination.delete()) Log.w(DouyinModule.TAG, "partial audio cleanup failed");
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private static DownloadResult downloadWithMediaStore(
            Context context,
            FeedContentTracker.Snapshot snapshot,
            String fileName
    ) {
        ContentResolver resolver = context.getContentResolver();
        Throwable lastError = null;
        for (FeedContentTracker.PlayUrl candidate : snapshot.playUrls) {
            HttpURLConnection connection = null;
            Uri destination = null;
            try {
                ensureEnabled();
                connection = openConnection(candidate.url);
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new IOException("HTTP " + status);
                }
                String contentType = connection.getContentType();
                if (contentType != null
                        && (contentType.startsWith("text/")
                        || contentType.contains("json"))) {
                    throw new IOException("unexpected content type " + contentType);
                }

                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                values.put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS
                );
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                destination = resolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values
                );
                if (destination == null) {
                    throw new IOException("MediaStore insert returned null");
                }

                long bytes;
                try (InputStream input = connection.getInputStream();
                     OutputStream output = resolver.openOutputStream(destination, "w")) {
                    if (output == null) {
                        throw new IOException("MediaStore output stream unavailable");
                    }
                    bytes = copy(input, output);
                }
                long expected = connection.getContentLengthLong();
                if (bytes <= 0L || (expected > 0 && expected != bytes)) {
                    throw new IOException("empty or incomplete response");
                }

                ensureEnabled();
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(destination, values, null, null) != 1) {
                    throw new IOException("failed to publish MediaStore download");
                }
                Log.i(DouyinModule.TAG,
                        "download completed: aid=" + snapshot.aid
                                + " source=" + candidate.source
                                + " host=" + Uri.parse(candidate.url).getHost()
                                + " bytes=" + bytes
                                + " file=" + fileName);
                return DownloadResult.completed(fileName);
            } catch (IOException | RuntimeException error) {
                lastError = error;
                if (destination != null) {
                    try {
                        resolver.delete(destination, null, null);
                    } catch (RuntimeException cleanupError) {
                        Log.w(DouyinModule.TAG,
                                "failed to remove partial download",
                                cleanupError);
                    }
                }
                Log.w(DouyinModule.TAG,
                        "download candidate failed: aid=" + snapshot.aid
                                + " source=" + candidate.source
                                + " host=" + Uri.parse(candidate.url).getHost(),
                        error);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        Log.e(DouyinModule.TAG,
                "all playback download URLs failed: aid=" + snapshot.aid,
                lastError);
        return DownloadResult.failed();
    }

    private static DownloadResult enqueueWithDownloadManager(
            Context context,
            FeedContentTracker.Snapshot snapshot,
            String fileName
    ) {
        DownloadManager manager =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            return DownloadResult.failed();
        }
        FeedContentTracker.PlayUrl candidate = snapshot.playUrls.get(0);
        DownloadManager.Request request =
                new DownloadManager.Request(Uri.parse(candidate.url));
        request.setTitle("抖音无水印视频");
        request.setDescription(fileName);
        request.setMimeType("video/mp4");
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(true);
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        );
        request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileName
        );
        addRequestHeaders(request);
        long downloadId = manager.enqueue(request);
        Log.i(DouyinModule.TAG,
                "download queued: aid=" + snapshot.aid
                        + " source=" + candidate.source
                        + " id=" + downloadId
                        + " file=" + fileName);
        return DownloadResult.queued(fileName);
    }

    private static HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Referer", "https://www.douyin.com/");
        String userAgent = System.getProperty("http.agent");
        if (userAgent != null && !userAgent.trim().isEmpty()) {
            connection.setRequestProperty("User-Agent", userAgent);
        }
        return connection;
    }

    private static void addRequestHeaders(DownloadManager.Request request) {
        request.addRequestHeader("Accept", "*/*");
        request.addRequestHeader("Referer", "https://www.douyin.com/");
        String userAgent = System.getProperty("http.agent");
        if (userAgent != null && !userAgent.trim().isEmpty()) {
            request.addRequestHeader("User-Agent", userAgent);
        }
    }

    private static long copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            ensureEnabled();
            output.write(buffer, 0, read);
            total += read;
        }
        output.flush();
        return total;
    }

    private static String buildFileName(String aid, boolean audio) {
        String safeAid = aid == null
                ? ""
                : aid.replaceAll("[^0-9A-Za-z_-]", "");
        if (safeAid.isEmpty() || "unknown".equalsIgnoreCase(safeAid)) {
            safeAid = "video";
        }
        return "douyin_" + safeAid + '_' + System.currentTimeMillis() + (audio ? ".mp3" : ".mp4");
    }

    private static void showToast(Context context, String message) {
        if (context != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }
    }

    private static final class DownloadResult {
        final boolean completed;
        final boolean queued;
        final String fileName;
        String error;

        private DownloadResult(boolean completed, boolean queued, String fileName) {
            this.completed = completed;
            this.queued = queued;
            this.fileName = fileName;
        }

        static DownloadResult completed(String fileName) {
            return new DownloadResult(true, false, fileName);
        }

        static DownloadResult queued(String fileName) {
            return new DownloadResult(false, true, fileName);
        }

        static DownloadResult failed() {
            return new DownloadResult(false, false, null);
        }

        static DownloadResult failed(String error) {
            DownloadResult result = failed();
            result.error = error;
            return result;
        }
    }
}
