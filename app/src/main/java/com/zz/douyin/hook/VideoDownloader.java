package com.zz.douyin.hook;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class VideoDownloader {
    /** 点击下载时回调，用于在播放地址过期后重新读取当前作品。 */
    interface SnapshotSupplier {
        FeedContentTracker.Snapshot get();
    }

    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Mobile Safari/537.36";
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
    private static AlertDialog chooser;

    private VideoDownloader() {
    }

    static void chooseDownload(
            Activity activity,
            FeedContentTracker.Snapshot snapshot,
            SnapshotSupplier refresher
    ) {
        if (!ImmersiveUi.isModuleEnabled() || activity == null
                || activity.isFinishing() || activity.isDestroyed()) return;

        List<String> labels = new ArrayList<>();
        List<Kind> kinds = new ArrayList<>();
        // 图文作品也带一个占位 video（用于背景音乐与过渡动画），按视频/音频下载只会拿到
        // 无效数据，所以图文只给图片入口。
        boolean photo = snapshot != null && snapshot.photoContent;
        if (!photo && snapshot != null && snapshot.hasDownloadUrl()) {
            labels.add("视频（无水印 MP4）");
            kinds.add(Kind.VIDEO);
            labels.add("音频（MP3）");
            kinds.add(Kind.AUDIO);
        }
        int images = snapshot == null ? 0 : snapshot.imageUrls().size();
        if (images > 0) {
            labels.add("图片（无水印 · " + images + " 张）");
            kinds.add(Kind.IMAGE);
        }
        if (labels.isEmpty()) {
            showToast(activity, photo ? "未解析到该图文的图片地址" : "当前内容没有可下载的地址");
            return;
        }

        dismissChooser();
        chooser = new AlertDialog.Builder(activity)
                .setTitle(photo ? "下载当前图文" : "下载当前内容")
                .setItems(labels.toArray(new String[0]),
                        (dialog, which) -> download(activity, snapshot, kinds.get(which), refresher))
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

    private static void download(
            Activity activity,
            FeedContentTracker.Snapshot snapshot,
            Kind kind,
            SnapshotSupplier refresher
    ) {
        if (!ImmersiveUi.isModuleEnabled()) return;
        if (activity == null || snapshot == null) {
            showToast(activity, "当前内容没有可下载的地址");
            return;
        }
        if (kind != Kind.IMAGE && !snapshot.hasDownloadUrl()) {
            showToast(activity, "当前视频地址暂不可用");
            return;
        }
        if (kind == Kind.IMAGE && !snapshot.hasImages()) {
            showToast(activity, "未解析到该图文的图片地址");
            return;
        }

        Context context = activity.getApplicationContext();
        String baseName = buildBaseName(snapshot.aid);
        String key = snapshot.aid + ':' + kind
                + ':' + (snapshot.playUrls.isEmpty() ? "" : snapshot.playUrls.get(0).url.hashCode());
        if (!ACTIVE.add(key)) {
            showToast(activity, "当前" + kind.label + "正在下载");
            return;
        }

        showToast(activity, kind.startMessage);
        Log.i(DouyinModule.TAG,
                "download requested: aid=" + snapshot.aid
                        + " kind=" + kind
                        + " candidates=" + snapshot.playUrls.size()
                        + " images=" + (kind == Kind.IMAGE ? snapshot.imageUrls().size() : 0)
                        + (snapshot.playUrls.isEmpty()
                        ? ""
                        : " primary=" + snapshot.playUrls.get(0).source));

        WORKER.execute(() -> {
            DownloadResult result;
            try {
                ensureEnabled();
                result = switch (kind) {
                    case AUDIO -> downloadAudio(context, snapshot, baseName + ".mp3", refresher);
                    case IMAGE -> downloadImages(context, snapshot, baseName);
                    case VIDEO -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? downloadWithMediaStore(context, snapshot, baseName + ".mp4", refresher)
                            : enqueueWithDownloadManager(context, snapshot, baseName + ".mp4");
                };
            } catch (Throwable error) {
                Log.e(DouyinModule.TAG,
                        "video download failed unexpectedly: aid=" + snapshot.aid,
                        error);
                result = DownloadResult.failed(describe(error));
            } finally {
                ACTIVE.remove(key);
            }

            DownloadResult finalResult = result;
            MAIN.post(() -> {
                if (finalResult.message != null) {
                    showToast(context, finalResult.message);
                } else if (finalResult.completed) {
                    showToast(context, "下载完成：Download/" + finalResult.fileName);
                } else if (finalResult.queued) {
                    showToast(context, "已加入下载队列：Download/" + finalResult.fileName);
                } else {
                    showToast(context, !ImmersiveUi.isModuleEnabled()
                            ? "功能总开关已关闭，下载已取消"
                            : "下载失败" + (finalResult.error == null
                            ? "，请稍后重试" : "：" + finalResult.error));
                }
            });
        });
    }

    private static DownloadResult downloadImages(
            Context context,
            FeedContentTracker.Snapshot snapshot,
            String baseName
    ) throws IOException {
        List<FeedContentTracker.ImageUrl> candidates = snapshot.imageUrls();
        int total = candidates.size();
        int saved = 0;
        List<String> failures = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            ensureEnabled();
            int position = index + 1;
            MAIN.post(() -> showToast(context, "正在保存图片 " + position + "/" + total + "…"));
            FeedContentTracker.ImageUrl image = candidates.get(index);
            String lastError = null;
            boolean savedThis = false;
            for (String url : image.urls) {
                File temp = null;
                try {
                    ensureEnabled();
                    temp = File.createTempFile(
                            "douxianren-image-",
                            ".tmp",
                            context.getCacheDir()
                    );
                    String contentType = downloadToFile(url, temp);
                    if (isGzip(temp)) {
                        File expanded = gunzip(context, temp);
                        if (!temp.delete()) {
                            Log.w(DouyinModule.TAG, "could not delete gzipped image");
                        }
                        temp = expanded;
                    }
                    String mime = sniffImageMime(temp);
                    if (mime == null) {
                        // 未知容器（例如服务端临时改用的新格式）时以 CDN 声明的类型为准。
                        mime = imageMimeFrom(contentType);
                    }
                    if (mime == null) {
                        throw new IOException("图片数据无效（" + probe(temp)
                                + " type=" + contentType + "）");
                    }
                    String fileName = total == 1
                            ? baseName + extensionOf(mime)
                            : baseName + '_' + position + extensionOf(mime);
                    publishFile(context, temp, fileName, mime);
                    Log.i(DouyinModule.TAG,
                            "image saved: index=" + position + "/" + total
                                    + " host=" + hostOf(url)
                                    + " bytes=" + temp.length()
                                    + " mime=" + mime
                                    + " file=" + fileName);
                    saved++;
                    savedThis = true;
                    break;
                } catch (IOException | RuntimeException error) {
                    lastError = describe(error);
                    Log.w(DouyinModule.TAG,
                            "image candidate failed: index=" + position + "/" + total
                                    + " host=" + hostOf(url)
                                    + " urls=" + image.urls.size(),
                            error);
                } finally {
                    if (temp != null && !temp.delete()) {
                        Log.w(DouyinModule.TAG, "could not delete temporary image");
                    }
                }
            }
            if (!savedThis) {
                failures.add(position + "：" + (lastError == null ? "图片下载失败" : lastError));
            }
        }
        Log.i(DouyinModule.TAG, "image download finished: aid=" + snapshot.aid
                + " saved=" + saved + "/" + total
                + (failures.isEmpty() ? "" : " failed=" + failures));
        if (saved == 0) {
            return DownloadResult.failed(failures.isEmpty()
                    ? "没有可保存的图片"
                    : failures.get(0));
        }
        if (saved < total) {
            return DownloadResult.message("已保存 " + saved + "/" + total + " 张图片到 Download");
        }
        return DownloadResult.message("已保存 " + total + " 张无水印图片到 Download");
    }

    private static boolean isGzip(File file) {
        byte[] head = new byte[2];
        int read;
        try (InputStream input = new FileInputStream(file)) {
            read = input.read(head);
        } catch (IOException error) {
            return false;
        }
        return read == 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B;
    }

    private static File gunzip(Context context, File source) throws IOException {
        File expanded = File.createTempFile(
                "douxianren-image-",
                ".raw",
                context.getCacheDir()
        );
        boolean ok = false;
        try (InputStream input = new GZIPInputStream(new FileInputStream(source));
             OutputStream output = new FileOutputStream(expanded)) {
            copy(input, output);
            ok = true;
        } finally {
            if (!ok && !expanded.delete()) {
                Log.w(DouyinModule.TAG, "could not delete partial gunzip output");
            }
        }
        return expanded;
    }

    private static String probe(File file) {
        byte[] head = new byte[12];
        int read;
        try (InputStream input = new FileInputStream(file)) {
            read = input.read(head);
        } catch (IOException error) {
            return "unreadable";
        }
        StringBuilder builder = new StringBuilder("bytes=").append(file.length())
                .append(" head=");
        for (int index = 0; index < Math.max(read, 0); index++) {
            int value = head[index] & 0xFF;
            builder.append(Character.forDigit(value >> 4, 16))
                    .append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    private static String hostOf(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? "unknown" : host;
        } catch (RuntimeException error) {
            return "unknown";
        }
    }

    private static DownloadResult downloadAudio(
            Context context,
            FeedContentTracker.Snapshot snapshot,
            String fileName,
            SnapshotSupplier refresher
    ) throws IOException {
        File source = File.createTempFile("douxianren-source-", ".mp4", context.getCacheDir());
        File mp3 = null;
        try {
            mp3 = File.createTempFile("douxianren-audio-", ".mp3", context.getCacheDir());
            IOException lastError = null;
            List<FeedContentTracker.PlayUrl> attempts = new ArrayList<>(snapshot.playUrls);
            boolean refreshed = false;
            for (int index = 0; index < attempts.size(); index++) {
                FeedContentTracker.PlayUrl candidate = attempts.get(index);
                ensureEnabled();
                HttpURLConnection connection = null;
                try {
                    connection = openConnection(candidate.url, true);
                    int status = connection.getResponseCode();
                    if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
                    ensureProgressiveSource(candidate.url, connection.getContentType());
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
                    publishFile(context, mp3, fileName, "audio/mpeg");
                    Log.i(DouyinModule.TAG, "audio download completed: aid=" + snapshot.aid
                            + " bytes=" + mp3.length() + " file=" + fileName);
                    return DownloadResult.completed(fileName);
                } catch (IOException | RuntimeException error) {
                    lastError = new IOException(describe(error), error);
                    Log.w(DouyinModule.TAG, "audio candidate failed: " + candidate.source, error);
                    if (!refreshed && index == attempts.size() - 1) {
                        refreshed = true;
                        attempts.addAll(freshPlayUrls(snapshot, refresher));
                    }
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
            return DownloadResult.failed(lastError == null ? null : describe(lastError));
        } finally {
            if (!source.delete()) Log.w(DouyinModule.TAG, "could not delete temporary source");
            if (mp3 != null && !mp3.delete()) Log.w(DouyinModule.TAG, "could not delete temporary MP3");
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private static DownloadResult downloadWithMediaStore(
            Context context,
            FeedContentTracker.Snapshot snapshot,
            String fileName,
            SnapshotSupplier refresher
    ) {
        ContentResolver resolver = context.getContentResolver();
        Throwable lastError = null;
        List<FeedContentTracker.PlayUrl> attempts = new ArrayList<>(snapshot.playUrls);
        boolean refreshed = false;
        for (int index = 0; index < attempts.size(); index++) {
            FeedContentTracker.PlayUrl candidate = attempts.get(index);
            HttpURLConnection connection = null;
            Uri destination = null;
            try {
                ensureEnabled();
                connection = openConnection(candidate.url, true);
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new IOException("HTTP " + status);
                }
                String contentType = connection.getContentType();
                ensureProgressiveSource(candidate.url, contentType);
                if (contentType != null
                        && (contentType.startsWith("text/")
                        || contentType.contains("json"))) {
                    throw new IOException("返回的不是视频数据");
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
                    throw new IOException("无法在 Download 目录创建文件");
                }

                long bytes;
                try (InputStream input = connection.getInputStream();
                     OutputStream output = resolver.openOutputStream(destination, "w")) {
                    if (output == null) {
                        throw new IOException("无法写入视频文件");
                    }
                    bytes = copy(input, output);
                }
                long expected = connection.getContentLengthLong();
                if (bytes <= 0L || (expected > 0 && expected != bytes)) {
                    throw new IOException("视频数据不完整");
                }

                ensureEnabled();
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(destination, values, null, null) != 1) {
                    throw new IOException("无法保存视频文件");
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
                if (!refreshed && index == attempts.size() - 1) {
                    refreshed = true;
                    attempts.addAll(freshPlayUrls(snapshot, refresher));
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        Log.e(DouyinModule.TAG,
                "all playback download URLs failed: aid=" + snapshot.aid,
                lastError);
        return DownloadResult.failed(lastError == null ? null : describe(lastError));
    }

    private static DownloadResult enqueueWithDownloadManager(
            Context context,
            FeedContentTracker.Snapshot snapshot,
            String fileName
    ) {
        DownloadManager manager =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            return DownloadResult.failed("系统下载服务不可用");
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

    /** 重新读取一次当前作品，只返回与现有候选不同的地址，用于地址过期后的重试。 */
    private static List<FeedContentTracker.PlayUrl> freshPlayUrls(
            FeedContentTracker.Snapshot snapshot,
            SnapshotSupplier refresher
    ) {
        if (refresher == null) return Collections.emptyList();
        FeedContentTracker.Snapshot refreshed = refresher.get();
        if (refreshed == null || refreshed.playUrls.isEmpty()) {
            return Collections.emptyList();
        }
        List<FeedContentTracker.PlayUrl> fresh = new ArrayList<>();
        for (FeedContentTracker.PlayUrl candidate : refreshed.playUrls) {
            boolean known = false;
            for (FeedContentTracker.PlayUrl existing : snapshot.playUrls) {
                if (existing.url.equals(candidate.url)) {
                    known = true;
                    break;
                }
            }
            if (!known) fresh.add(candidate);
        }
        if (!fresh.isEmpty()) {
            Log.i(DouyinModule.TAG,
                    "refreshed " + fresh.size() + " expired download URLs for aid="
                            + snapshot.aid);
        }
        return fresh;
    }

    private static String downloadToFile(String url, File target) throws IOException {
        IOException lastError = null;
        for (boolean appAgent : new boolean[]{true, false}) {
            HttpURLConnection connection = null;
            try {
                connection = openConnection(url, appAgent);
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
                String contentType = connection.getContentType();
                if (contentType != null && (contentType.startsWith("text/")
                        || contentType.contains("json"))) {
                    throw new IOException("返回的不是图片数据");
                }
                long bytes;
                try (InputStream input = connection.getInputStream();
                     OutputStream output = new FileOutputStream(target)) {
                    bytes = copy(input, output);
                }
                long expected = connection.getContentLengthLong();
                if (bytes <= 0L || (expected > 0 && expected != bytes)) {
                    throw new IOException("图片数据不完整");
                }
                return contentType;
            } catch (IOException | RuntimeException error) {
                lastError = new IOException(describe(error), error);
                Log.w(DouyinModule.TAG,
                        "image download attempt failed: agent=" + (appAgent ? "app" : "browser"),
                        error);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw lastError == null ? new IOException("图片下载失败") : lastError;
    }

    private static void publishFile(
            Context context,
            File source,
            String fileName,
            String mimeType
    ) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToMediaStore(context, source, fileName, mimeType);
            return;
        }
        File directory = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
        );
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建 Download 目录");
        }
        File destination = new File(directory, fileName);
        boolean published = false;
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            copy(input, output);
            published = true;
        } finally {
            if (!published && !destination.delete()) {
                Log.w(DouyinModule.TAG, "partial file cleanup failed");
            }
        }
        MediaScannerConnection.scanFile(
                context,
                new String[]{destination.getAbsolutePath()},
                new String[]{mimeType},
                null
        );
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private static void publishToMediaStore(
            Context context,
            File source,
            String fileName,
            String mimeType
    ) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (destination == null) throw new IOException("无法在 Download 目录创建文件");
        boolean published = false;
        try {
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = resolver.openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("无法写入文件");
                copy(input, output);
            }
            ensureEnabled();
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(destination, values, null, null) != 1) {
                throw new IOException("无法保存文件");
            }
            published = true;
        } finally {
            if (!published) resolver.delete(destination, null, null);
        }
    }

    private static void ensureProgressiveSource(String url, String contentType) throws IOException {
        String lowerUrl = url.toLowerCase();
        String lowerType = contentType == null ? "" : contentType.toLowerCase();
        if (lowerUrl.contains(".m3u8")
                || lowerType.contains("mpegurl")
                || lowerType.contains("dash+xml")) {
            throw new IOException("该作品是流式地址，暂不支持保存");
        }
    }

    private static String sniffImageMime(File file) {
        byte[] header = new byte[16];
        int read;
        try (InputStream input = new FileInputStream(file)) {
            read = input.read(header);
        } catch (IOException error) {
            return null;
        }
        if (read < 12) return null;
        if ((header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8) return "image/jpeg";
        if ((header[0] & 0xFF) == 0x89
                && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') {
            return "image/png";
        }
        if (header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B'
                && header[11] == 'P') {
            return "image/webp";
        }
        if (header[0] == 'G' && header[1] == 'I' && header[2] == 'F'
                && header[3] == '8') {
            return "image/gif";
        }
        if (header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') {
            String brand = new String(header, 8, 4, StandardCharsets.US_ASCII);
            if (brand.startsWith("avif")) return "image/avif";
            if (brand.startsWith("heic") || brand.startsWith("heix")
                    || brand.startsWith("mif1")) {
                return "image/heic";
            }
        }
        return null;
    }

    private static String imageMimeFrom(String contentType) {
        if (contentType == null) return null;
        String value = contentType.trim().toLowerCase();
        int separator = value.indexOf(';');
        if (separator >= 0) {
            value = value.substring(0, separator).trim();
        }
        return value.startsWith("image/") ? value : null;
    }

    private static String extensionOf(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/avif" -> ".avif";
            case "image/heic", "image/heif" -> ".heic";
            case "image/gif" -> ".gif";
            case "image/bmp" -> ".bmp";
            default -> ".jpg";
        };
    }

    private static HttpURLConnection openConnection(String url, boolean appAgent)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Referer", "https://www.douyin.com/");
        String userAgent = appAgent ? appUserAgent() : BROWSER_USER_AGENT;
        if (userAgent != null && !userAgent.isBlank()) {
            connection.setRequestProperty("User-Agent", userAgent);
        }
        return connection;
    }

    private static String appUserAgent() {
        String agent = System.getProperty("http.agent");
        return agent == null || agent.isBlank() ? BROWSER_USER_AGENT : agent;
    }

    private static void addRequestHeaders(DownloadManager.Request request) {
        request.addRequestHeader("Accept", "*/*");
        request.addRequestHeader("Referer", "https://www.douyin.com/");
        String userAgent = appUserAgent();
        if (userAgent != null && !userAgent.isBlank()) {
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

    private static String buildBaseName(String aid) {
        String safeAid = aid == null
                ? ""
                : aid.replaceAll("[^0-9A-Za-z_-]", "");
        if (safeAid.isEmpty() || "unknown".equalsIgnoreCase(safeAid)) {
            safeAid = "video";
        }
        return "douyin_" + safeAid + '_' + System.currentTimeMillis();
    }

    private static String describe(Throwable error) {
        if (error == null) return "未知错误";
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private static void showToast(Context context, String message) {
        if (context != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }
    }

    private enum Kind {
        VIDEO("视频", "开始下载无水印视频"),
        AUDIO("音频", "开始下载音频，完成后保存为 MP3"),
        IMAGE("图片", "开始保存无水印图片");

        final String label;
        final String startMessage;

        Kind(String label, String startMessage) {
            this.label = label;
            this.startMessage = startMessage;
        }
    }

    private static final class DownloadResult {
        final boolean completed;
        final boolean queued;
        final String fileName;
        String error;
        String message;

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

        /** 图片是批量保存，用自定义文案代替单文件路径提示。 */
        static DownloadResult message(String message) {
            DownloadResult result = new DownloadResult(true, false, null);
            result.message = message;
            return result;
        }
    }
}
