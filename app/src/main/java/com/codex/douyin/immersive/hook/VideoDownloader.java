package com.codex.douyin.immersive.hook;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
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

    static void download(Activity activity, FeedContentTracker.Snapshot snapshot) {
        if (activity == null || snapshot == null || !snapshot.hasDownloadUrl()) {
            showToast(activity, "当前视频地址暂不可用");
            return;
        }
        Context context = activity.getApplicationContext();
        FeedContentTracker.PlayUrl first = snapshot.playUrls.get(0);
        String key = snapshot.aid + ':' + first.url.hashCode();
        if (!ACTIVE.add(key)) {
            showToast(activity, "当前视频正在下载");
            return;
        }

        String fileName = buildFileName(snapshot.aid);
        showToast(activity, "开始下载无水印视频");
        Log.i(DouyinModule.TAG,
                "download requested: aid=" + snapshot.aid
                        + " candidates=" + snapshot.playUrls.size()
                        + " primary=" + first.source);

        WORKER.execute(() -> {
            DownloadResult result;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                    showToast(context, "下载失败，请稍后重试");
                }
            });
        });
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
                if (bytes <= 0L) {
                    throw new IOException("empty response");
                }

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
            output.write(buffer, 0, read);
            total += read;
        }
        output.flush();
        return total;
    }

    private static String buildFileName(String aid) {
        String safeAid = aid == null
                ? ""
                : aid.replaceAll("[^0-9A-Za-z_-]", "");
        if (safeAid.isEmpty() || "unknown".equalsIgnoreCase(safeAid)) {
            safeAid = "video";
        }
        return "douyin_" + safeAid + '_' + System.currentTimeMillis() + ".mp4";
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
    }
}
