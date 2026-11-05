package com.zz.douyin.hook;

import android.content.SharedPreferences;
import android.util.Log;

import com.zz.douyin.FilterPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 固定视频清晰度。
 *
 * <p>抖音把多码率模型经 {@code TTVideoEngine.setVideoModel(IVideoModel)} 交给播放引擎后，
 * 由引擎内的档位策略（GearStrategy／DefaultABRModule）按网络、设备等条件挑选并中途切换
 * {@code VideoInfo} 档位。本钩子不动模型本身，只把引擎的档位查询全部钉死：
 *
 * <ul>
 *   <li>{@code getVideoInfoList()} 只返回目标清晰度的档位；</li>
 *   <li>所有返回 {@code VideoInfo} 的查询（含混淆名的方法）一律替换为目标档位，
 *       并尽量保持引擎原本要求的编码格式；</li>
 *   <li>{@code getSupportResolutions()} 只暴露目标清晰度；</li>
 *   <li>进入模型时顺手关闭 {@code VideoRef.mEnableAdaptive}，抑制自适应标记。</li>
 * </ul>
 *
 * <p>清晰度档位的识别优先用 {@code VideoInfo.mVHeight}，缺失时依次从
 * {@code mResolutionStr}／{@code mDefinition}／{@code mQualityDesc} 等字符串解析，
 * 最后才退回 {@code mQualityType} 的粗略映射。所有反射步骤独立容错，
 * 任何一步失败都退回抖音原生行为，不影响播放。
 */
final class QualityHooks {
    private static final String[] ENGINE_CLASSES = {
            "com.ss.ttvideoengine.TTVideoEngine",
            "com.ss.ttvideoengine.TTVideoEngineImpl",
            "com.ss.ttvideoengine.TTVideoEngineImplV2"
    };

    private static final String[] MODEL_ENTRY_METHODS = {
            "setVideoModel",
            "setPlayItem",
            "setPreloaderItem"
    };

    private static final String VIDEO_INFO_CLASS = "com.ss.ttvideoengine.model.VideoInfo";
    /** 稳定类名的模型基类，安装期直接挂钩，不依赖引擎入口触发。 */
    private static final String VIDEO_MODEL_CLASS = "com.ss.ttvideoengine.model.VideoModel";
    /** 应用层码率模型：字段名（bitRate／hitBitrate）未混淆，预加载画质在这里预选。 */
    private static final String SIM_MODEL_CLASS =
            "com.ss.android.ugc.playerkit.simapicommon.model.SimVideoUrlModel";

    /** 从字符串里解析清晰度（"720p"、"1080P"、"normal_540" 等）。 */
    private static final Pattern HEIGHT_PATTERN = Pattern.compile("(\\d{3,4})");
    private static final Pattern K_PATTERN = Pattern.compile("(\\d)k", Pattern.CASE_INSENSITIVE);

    /** 钉死结果按模型实例缓存；值为 {@code NO_PIN} 表示已尝试但无法钉死。 */
    private static final List<Object> NO_PIN = Collections.emptyList();
    private static final Map<Object, List<Object>> PINNED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final java.util.Set<Class<?>> HOOKED_MODEL_CLASSES =
            Collections.newSetFromMap(new WeakHashMap<>());
    /** 已做过模型发现的播放器实例，避免每次 play 回调都遍历对象图。 */
    private static final java.util.Set<Object> DISCOVERED_PLAYERS =
            Collections.newSetFromMap(new WeakHashMap<>());
    /** 已钉死过 bitRate 字段的 SimVideoUrlModel 实例。 */
    private static final Map<Object, Boolean> PINNED_SIM =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** 钉死过程的重入守卫：防止被钩方法在哈希表运算中递归触发。 */
    private static final ThreadLocal<Boolean> IN_SIM_PIN =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** bitRate 等字段的按类与名字缓存，避免高频重试时反复反射。 */
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** 入口钩子诊断样本：前若干次调用把方法名和参数类型打出来。 */
    private static volatile int entrySamples;

    private static volatile DouyinModule module;
    private static volatile String qualitySetting = FilterPreferences.DEFAULT_VIDEO_QUALITY;
    private static volatile boolean moduleEnabled = true;
    private static volatile boolean guardLogged;

    private QualityHooks() {
    }

    static void install(
            DouyinModule host,
            ClassLoader loader,
            SharedPreferences preferences
    ) {
        module = host;
        readPreferences(preferences);
        if (preferences != null) {
            preferences.registerOnSharedPreferenceChangeListener(
                    (prefs, key) -> readPreferences(prefs)
            );
        }

        int hooked = 0;
        for (String className : ENGINE_CLASSES) {
            try {
                Class<?> type = Class.forName(className, false, loader);
                hooked += hookEngineEntry(type);
            } catch (ClassNotFoundException ignored) {
                host.log(Log.DEBUG, DouyinModule.TAG, "engine class absent: " + className);
            } catch (Throwable error) {
                host.log(Log.WARN, DouyinModule.TAG,
                        "quality hook failed for " + className, error);
            }
        }

        // 模型基类直接安装期挂钩：即使引擎入口换了方法名，只要引擎查询
        // VideoModel 的档位列表，钉死就会生效。
        try {
            Class<?> modelType = Class.forName(VIDEO_MODEL_CLASS, false, loader);
            hookModelClass(modelType);
        } catch (ClassNotFoundException error) {
            host.log(Log.WARN, DouyinModule.TAG,
                    "video model class absent: " + VIDEO_MODEL_CLASS);
        } catch (Throwable error) {
            host.log(Log.WARN, DouyinModule.TAG,
                    "video model hook failed", error);
        }

        // 应用层模型：预加载的画质在 SimVideoUrlModel 里就被预选，
        // 引擎层钉死覆盖不到，这里直接把 bitRate 列表替换成目标档位。
        try {
            Class<?> simType = Class.forName(SIM_MODEL_CLASS, false, loader);
            hooked += hookSimUrlModel(simType);
        } catch (ClassNotFoundException error) {
            host.log(Log.WARN, DouyinModule.TAG, "sim url model absent");
        } catch (Throwable error) {
            host.log(Log.WARN, DouyinModule.TAG, "sim url model hook failed", error);
        }

        host.log(Log.INFO, DouyinModule.TAG,
                "installed " + hooked + " video-quality entry hooks (setting="
                        + qualitySetting + ")");
    }

    /** SimVideoUrlModel 的无参方法都作为触发点：首次调用时把档位列表钉死到字段里。 */
    private static int hookSimUrlModel(Class<?> type) {
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (method.getParameterCount() != 0
                    || Modifier.isStatic(method.getModifiers())
                    || Modifier.isAbstract(method.getModifiers())
                    || Modifier.isNative(method.getModifiers())) {
                continue;
            }
            String name = method.getName();
            // hashCode/toString 会被哈希表、日志等基础机制高频调用，
            // 挂钩它们既拖速度又容易和 PINNED_SIM 的键运算形成重入。
            if ("hashCode".equals(name) || "toString".equals(name)) {
                continue;
            }
            module.hook(method)
                    .setId("douyin-quality-sim-" + method.toGenericString())
                    .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!IN_SIM_PIN.get()) {
                            IN_SIM_PIN.set(Boolean.TRUE);
                            try {
                                pinSimModelOnce(chain.getThisObject());
                            } finally {
                                IN_SIM_PIN.set(Boolean.FALSE);
                            }
                        }
                        return chain.proceed();
                    });
            count++;
        }
        return count;
    }

    private static void pinSimModelOnce(Object model) {
        if (model == null || !enforcing()) {
            return;
        }
        synchronized (PINNED_SIM) {
            if (PINNED_SIM.containsKey(model)) {
                return;
            }
        }
        Field bitRateField = cachedField(model.getClass(), "bitRate");
        if (bitRateField == null) {
            // 结构变了就没有可钉死的字段，标记完成避免反复反射。
            synchronized (PINNED_SIM) {
                PINNED_SIM.put(model, Boolean.TRUE);
            }
            return;
        }
        Object raw;
        try {
            raw = bitRateField.get(model);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return;
        }
        if (!(raw instanceof List<?> variants) || variants.isEmpty()) {
            // 列表还没填好，等下一次方法调用再试。
            return;
        }
        synchronized (PINNED_SIM) {
            PINNED_SIM.put(model, Boolean.TRUE);
        }
        if (variants.size() < 2) {
            return;
        }

        int target = targetHeight();
        int bestHeight = -1;
        for (Object variant : variants) {
            int height = simEntryHeight(variant);
            if (height <= 0) {
                continue;
            }
            if (target == Integer.MAX_VALUE) {
                bestHeight = Math.max(bestHeight, height);
            } else if (height == target) {
                bestHeight = target;
                break;
            } else if (height < target) {
                if (bestHeight < 0 || bestHeight > target || bestHeight < height) {
                    bestHeight = height;
                }
            } else if (bestHeight < 0 || bestHeight > target) {
                if (bestHeight < 0 || height < bestHeight) {
                    bestHeight = height;
                }
            }
        }
        if (bestHeight <= 0) {
            return;
        }

        List<Object> kept = new ArrayList<>();
        for (Object variant : variants) {
            if (simEntryHeight(variant) == bestHeight) {
                kept.add(variant);
            }
        }
        if (kept.isEmpty() || kept.size() == variants.size()) {
            return;
        }

        Object hit = pickSimHit(kept);
        try {
            bitRateField.set(model, new ArrayList<>(kept));
            Field hitField = cachedField(model.getClass(), "hitBitrate");
            // 列表项与 hitBitrate 字段类型不一致（如 DASH 条目）时跳过，
            // bitRate 列表过滤本身就是主要钉死手段。
            if (hit != null && hitField != null && hitField.getType().isInstance(hit)) {
                hitField.set(model, hit);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(DouyinModule.TAG, "quality sim pin failed", error);
            return;
        }
        StringBuilder available = new StringBuilder();
        for (Object variant : variants) {
            if (available.length() > 0) {
                available.append(',');
            }
            available.append(simEntryHeight(variant)).append('p');
        }
        Log.i(DouyinModule.TAG, "quality pinned(sim) target=" + describeTarget(target)
                + " kept=" + bestHeight + "p variants=" + kept.size() + "/" + variants.size()
                + " available=[" + available + "]");
    }

    private static int simEntryHeight(Object entry) {
        Object value = invokeNoArg(entry, "getVideoHeight");
        if (value instanceof Integer height && height > 0) {
            return height;
        }
        return -1;
    }

    /** 按类与字段名缓存查找：键是 Class 对象，其 hashCode 不在挂钩范围内。 */
    private static Field cachedField(Class<?> type, String name) {
        synchronized (FIELD_CACHE) {
            Map<String, Field> byName = FIELD_CACHE.get(type);
            if (byName != null && byName.containsKey(name)) {
                return byName.get(name);
            }
        }
        Field field = findField(type, name);
        if (field != null) {
            try {
                field.setAccessible(true);
            } catch (RuntimeException ignored) {
                field = null;
            }
        }
        synchronized (FIELD_CACHE) {
            FIELD_CACHE.computeIfAbsent(type, ignored -> new HashMap<>())
                    .put(name, field);
        }
        return field;
    }

    /** 同一清晰度多个变体时，选码率最高的作为 hitBitrate。 */
    private static Object pickSimHit(List<Object> kept) {
        Object best = null;
        int bestBitrate = Integer.MIN_VALUE;
        for (Object candidate : kept) {
            Object value = invokeNoArg(candidate, "getBitRate");
            int bitrate = value instanceof Integer number ? number : Integer.MIN_VALUE;
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate;
                best = candidate;
            }
        }
        return best;
    }

    private static void readPreferences(SharedPreferences preferences) {
        moduleEnabled = FilterPreferences.readModuleEnabled(preferences);
        qualitySetting = FilterPreferences.readVideoQuality(preferences);
    }

    private static int hookEngineEntry(Class<?> type) {
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (method.getParameterCount() != 1
                    || Modifier.isStatic(method.getModifiers())
                    || Modifier.isAbstract(method.getModifiers())) {
                continue;
            }
            String name = method.getName();
            boolean entry = false;
            for (String candidate : MODEL_ENTRY_METHODS) {
                entry |= candidate.equals(name);
            }
            if (!entry) {
                continue;
            }
            module.hook(method)
                    .setId("douyin-quality-entry-" + method.toGenericString())
                    .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object argument = chain.getArgs().get(0);
                        Object model = extractVideoModel(argument);
                        if (entrySamples < 10) {
                            entrySamples++;
                            Log.i(DouyinModule.TAG, "quality entry: " + method.getName()
                                    + " arg=" + (argument == null
                                    ? "null"
                                    : argument.getClass().getName())
                                    + (model == null ? "" : " model=true"));
                        }
                        if (model != null) {
                            disableAdaptive(model);
                            hookModelClass(model.getClass());
                        }
                        return chain.proceed();
                    });
            count++;
        }
        return count;
    }

    /**
     * 播放跟踪发现的播放器实例（如 MTTVideoEngine，继承自 TTVideoEngine）。
     * 从它身上取出当前 VideoModel 并挂上查询钩子；抖音升级把模型入口换成
     * 别的方法名时，这条运行时发现的路径仍然有效。
     */
    static void onEngineObserved(Object player) {
        DouyinModule host = module;
        if (host == null || player == null) {
            return;
        }
        if (!guardLogged) {
            guardLogged = true;
            Log.i(DouyinModule.TAG, "quality guard state: setting=" + qualitySetting
                    + ", enforcing=" + enforcing());
        }
        if (!enforcing()) {
            return;
        }
        synchronized (DISCOVERED_PLAYERS) {
            if (!DISCOVERED_PLAYERS.add(player)) {
                return;
            }
        }
        try {
            discoverModels(player, 0, java.util.Collections.synchronizedMap(
                    new java.util.IdentityHashMap<>()));
        } catch (Throwable error) {
            Log.w(DouyinModule.TAG, "quality engine discovery failed", error);
        }
    }

    /** 沿对象图找出实现了 getVideoInfoList 的模型并挂钩，深度和数量都有限制。 */
    private static void discoverModels(
            Object node,
            int depth,
            Map<Object, Boolean> seen
    ) {
        if (node == null || depth > 3 || seen.size() > 120) {
            return;
        }
        if (seen.put(node, Boolean.TRUE) != null) {
            return;
        }
        Class<?> type = node.getClass();
        if (looksLikeVideoModel(node)) {
            hookModelClass(type);
            return;
        }
        Object model = invokeNoArg(node, "getVideoModel");
        if (looksLikeVideoModel(model)) {
            hookModelClass(model.getClass());
        }
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    discoverModels(field.get(node), depth + 1, seen);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // 对象图随版本变化，读不出来的字段直接跳过。
                }
            }
        }
    }

    /** PlayItem／PreloaderItem 这类包装对象会包一层 VideoModel，逐个尝试拆出真正的模型。 */
    private static Object extractVideoModel(Object candidate) {
        if (looksLikeVideoModel(candidate)) {
            return candidate;
        }
        return unwrapModelContainer(candidate);
    }

    private static Object unwrapModelContainer(Object container) {
        if (container == null) {
            return null;
        }
        Object nested = invokeNoArg(container, "getVideoModel");
        if (looksLikeVideoModel(nested)) {
            return nested;
        }
        for (Class<?> type = container.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(container);
                    if (looksLikeVideoModel(value)) {
                        return value;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // 包装结构随版本变化，字段读不出来就跳过。
                }
            }
        }
        return null;
    }

    private static boolean looksLikeVideoModel(Object value) {
        return value != null && findNoArgMethod(value.getClass(), "getVideoInfoList") != null;
    }

    private static void hookModelClass(Class<?> type) {
        DouyinModule host = module;
        if (host == null) {
            return;
        }
        Class<?> videoInfoType;
        try {
            videoInfoType = Class.forName(VIDEO_INFO_CLASS, false, type.getClassLoader());
        } catch (ClassNotFoundException error) {
            return;
        }

        synchronized (HOOKED_MODEL_CLASSES) {
            if (!HOOKED_MODEL_CLASSES.add(type)) {
                return;
            }
        }

        int count = 0;
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        || Modifier.isAbstract(method.getModifiers())
                        || Modifier.isNative(method.getModifiers())) {
                    continue;
                }
                String name = method.getName();
                try {
                    if ("getVideoInfoList".equals(name) && method.getParameterCount() == 0) {
                        hookVideoInfoList(method);
                        count++;
                    } else if ("getSupportResolutions".equals(name)
                            && method.getParameterCount() == 0
                            && method.getReturnType().isArray()) {
                        hookSupportResolutions(method);
                        count++;
                    } else if (method.getReturnType() == videoInfoType) {
                        hookVideoInfoQuery(method);
                        count++;
                    }
                } catch (Throwable error) {
                    host.log(Log.WARN, DouyinModule.TAG,
                            "model hook failed: " + method, error);
                }
            }
        }
        Log.i(DouyinModule.TAG,
                "quality model hooks installed: " + type.getName() + " (" + count + ")");
    }

    /** 模型档位查询的样本数：前几次把可用档位打出来，便于实机诊断。 */
    private static volatile int querySamples;

    private static void hookVideoInfoList(Method method) {
        module.hook(method)
                .setId("douyin-quality-list-" + method.toGenericString())
                .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (!(result instanceof List<?> original) || original.isEmpty()) {
                        return result;
                    }
                    if (querySamples < 5) {
                        querySamples++;
                        Log.i(DouyinModule.TAG, describeVariants(
                                chain.getThisObject(), original));
                    }
                    if (original.size() < 2) {
                        return result;
                    }
                    List<Object> pinned = ensurePinned(chain.getThisObject(), original);
                    // 返回可变副本：引擎侧万一要排序或增删，也不会碰到缓存本体。
                    return pinned == null ? result : new ArrayList<>(pinned);
                });
    }

    private static String describeVariants(Object model, List<?> variants) {
        StringBuilder heights = new StringBuilder();
        for (Object variant : variants) {
            if (heights.length() > 0) {
                heights.append(',');
            }
            heights.append(heightOf(variant)).append('p');
        }
        return "quality model queried: class=" + model.getClass().getName()
                + " variants=" + variants.size() + " heights=[" + heights + "]"
                + " setting=" + qualitySetting;
    }

    private static void hookSupportResolutions(Method method) {
        module.hook(method)
                .setId("douyin-quality-resolutions-" + method.toGenericString())
                .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (!(result instanceof Object[] original) || original.length < 2) {
                        return result;
                    }
                    Object model = chain.getThisObject();
                    List<Object> pinned = pinnedOf(model);
                    if (pinned == null || pinned.isEmpty()) {
                        return result;
                    }
                    Object replacement = resolutionsOf(pinned, original.getClass()
                            .getComponentType());
                    return replacement == null ? result : replacement;
                });
    }

    private static void hookVideoInfoQuery(Method method) {
        module.hook(method)
                .setId("douyin-quality-info-" + method.toGenericString())
                .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (result == null) {
                        return null;
                    }
                    List<Object> pinned = pinnedOf(chain.getThisObject());
                    if (pinned == null || pinned.isEmpty() || pinned.contains(result)) {
                        return result;
                    }
                    Object replacement = pickReplacement(pinned, result);
                    return replacement == null ? result : replacement;
                });
    }

    // ------------------------------------------------------------------ 钉死结果

    private static List<Object> pinnedOf(Object model) {
        if (model == null || !enforcing()) {
            return null;
        }
        synchronized (PINNED) {
            if (PINNED.containsKey(model)) {
                List<Object> cached = PINNED.get(model);
                return cached == NO_PIN ? null : cached;
            }
        }
        // 尚未计算：通过 getVideoInfoList 触发一次钉死计算（会走上面的钩子）。
        invokeNoArg(model, "getVideoInfoList");
        synchronized (PINNED) {
            List<Object> cached = PINNED.get(model);
            return cached == null || cached == NO_PIN ? null : cached;
        }
    }

    private static List<Object> ensurePinned(Object model, List<?> original) {
        if (!enforcing()) {
            return null;
        }
        synchronized (PINNED) {
            if (PINNED.containsKey(model)) {
                List<Object> cached = PINNED.get(model);
                return cached == NO_PIN ? null : cached;
            }
        }

        List<Object> pinned = computePinned(original);
        synchronized (PINNED) {
            PINNED.put(model, pinned == null ? NO_PIN : pinned);
        }
        if (pinned != null) {
            Log.i(DouyinModule.TAG, describePin(targetHeight(), original, pinned));
        }
        return pinned;
    }

    private static String describePin(int target, List<?> original, List<?> pinned) {
        StringBuilder available = new StringBuilder();
        for (Object variant : original) {
            if (available.length() > 0) {
                available.append(',');
            }
            available.append(heightOf(variant)).append('p');
        }
        Object sample = pinned.get(0);
        String codec = stringField(sample, "mCodecType");
        return "quality pinned target=" + describeTarget(target)
                + " kept=" + heightOf(sample) + "p"
                + (codec == null ? "" : "/" + codec)
                + " variants=" + pinned.size() + "/" + original.size()
                + " available=[" + available + "]";
    }

    private static String describeTarget(int target) {
        return target == Integer.MAX_VALUE ? "max" : target + "p";
    }

    private static boolean enforcing() {
        return moduleEnabled && !FilterPreferences.QUALITY_AUTO.equals(qualitySetting);
    }

    private static int targetHeight() {
        if (FilterPreferences.QUALITY_MAX.equals(qualitySetting)) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(qualitySetting);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * 从原始档位里挑出目标清晰度：优先精确匹配；没有就取不高于目标的最大档；
     * 再没有（视频最高清晰度低于目标）就取可用的最低档。同一清晰度保留全部
     * 编码／帧率变体，编码选择仍交给引擎。
     */
    private static List<Object> computePinned(List<?> variants) {
        int target = targetHeight();
        if (target <= 0 || variants == null || variants.size() < 2) {
            return null;
        }

        // 选择档位：精确匹配 > 不高于目标的最大档 > 高于目标的最小档。
        int bestHeight = -1;
        for (Object variant : variants) {
            int height = heightOf(variant);
            if (height <= 0) {
                continue;
            }
            if (target == Integer.MAX_VALUE) {
                bestHeight = Math.max(bestHeight, height);
            } else if (height == target) {
                bestHeight = target;
                break;
            } else if (height < target) {
                if (bestHeight < 0 || bestHeight > target || bestHeight < height) {
                    bestHeight = height;
                }
            } else if (bestHeight < 0 || bestHeight > target) {
                if (bestHeight < 0 || height < bestHeight) {
                    bestHeight = height;
                }
            }
        }
        if (bestHeight <= 0) {
            return null;
        }

        List<Object> kept = new ArrayList<>();
        for (Object variant : variants) {
            if (heightOf(variant) == bestHeight) {
                kept.add(variant);
            }
        }
        if (kept.isEmpty() || kept.size() == variants.size()) {
            return null;
        }
        return Collections.unmodifiableList(kept);
    }

    /** 引擎想要的档位不在钉死集合里时，挑一个同编码、同清晰度的替代品。 */
    private static Object pickReplacement(List<Object> pinned, Object requested) {
        String codec = stringField(requested, "mCodecType");
        Object best = null;
        int bestBitrate = Integer.MIN_VALUE;
        for (Object candidate : pinned) {
            if (codec != null && codec.equals(stringField(candidate, "mCodecType"))) {
                return candidate;
            }
            int bitrate = intOrMin(candidate, "mBitrate", "mBitrateVer2");
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate;
                best = candidate;
            }
        }
        return best;
    }

    private static Object resolutionsOf(List<Object> pinned, Class<?> componentType) {
        List<Object> distinct = new ArrayList<>();
        for (Object variant : pinned) {
            Object resolution = readField(variant, "mResolution");
            if (resolution == null || distinct.contains(resolution)) {
                continue;
            }
            distinct.add(resolution);
        }
        if (distinct.isEmpty()) {
            return null;
        }
        Object array = java.lang.reflect.Array.newInstance(componentType, distinct.size());
        for (int index = 0; index < distinct.size(); index++) {
            java.lang.reflect.Array.set(array, index, distinct.get(index));
        }
        return array;
    }

    // ------------------------------------------------------------------ 档位识别

    private static int heightOf(Object variant) {
        Integer height = intField(variant, "mVHeight");
        if (height == null || height <= 0) {
            height = intField(variant, "mVHeightVer2");
        }
        if (height != null && height > 0) {
            return height;
        }
        String[] textFields = {
                "mResolutionStr", "mDefinition", "mDefinitionVer2",
                "mQuality", "mQualityVer2", "mQualityDesc"
        };
        for (String field : textFields) {
            int parsed = parseHeight(stringField(variant, field));
            if (parsed > 0) {
                return parsed;
            }
        }
        Object resolution = readField(variant, "mResolution");
        if (resolution != null) {
            int parsed = parseHeight(stringField(resolution, "resolution"));
            if (parsed > 0) {
                return parsed;
            }
            parsed = parseHeight(resolution.toString());
            if (parsed > 0) {
                return parsed;
            }
        }
        Integer qualityType = intField(variant, "mQualityType");
        return qualityType == null ? -1 : qualityTypeToHeight(qualityType);
    }

    private static int parseHeight(String value) {
        if (value == null || value.isEmpty()) {
            return -1;
        }
        Matcher k = K_PATTERN.matcher(value);
        if (k.find()) {
            try {
                switch (Integer.parseInt(k.group(1))) {
                    case 2:
                        return 1440;
                    case 4:
                        return 2160;
                    case 8:
                        return 4320;
                    default:
                        break;
                }
            } catch (NumberFormatException ignored) {
                // 继续按普通数字解析。
            }
        }
        Matcher matcher = HEIGHT_PATTERN.matcher(value);
        if (matcher.find()) {
            try {
                int height = Integer.parseInt(matcher.group(1));
                if (height >= 144 && height <= 4320) {
                    return height;
                }
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * 抖音 quality_type 的粗略映射，只在拿不到实际分辨率时兜底：
     * 5=360p、10=480p、15=540p、20/21=720p、25/26=1080p、30=2K、40+=4K。
     */
    private static int qualityTypeToHeight(int qualityType) {
        switch (qualityType) {
            case 5:
                return 360;
            case 10:
                return 480;
            case 15:
                return 540;
            case 20:
            case 21:
                return 720;
            case 25:
            case 26:
                return 1080;
            case 30:
                return 1440;
            case 40:
            case 45:
                return 2160;
            default:
                return -1;
        }
    }

    /** 关掉 VideoRef 上的自适应标记，尽力而为，失败不影响主流程。 */
    private static void disableAdaptive(Object model) {
        if (!enforcing()) {
            return;
        }
        for (String refField : new String[]{"videoRef", "vodVideoRef"}) {
            Object ref = readField(model, refField);
            if (ref == null) {
                continue;
            }
            try {
                Field adaptive = findField(ref.getClass(), "mEnableAdaptive");
                if (adaptive != null && adaptive.getType() == boolean.class) {
                    adaptive.setAccessible(true);
                    adaptive.setBoolean(ref, false);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 标记字段消失就跳过，档位查询钉死仍然生效。
            }
        }
    }

    // ------------------------------------------------------------------ 反射工具

    private static Method findNoArgMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) {
                    return method;
                }
            }
        }
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Object invokeNoArg(Object instance, String name) {
        Method method = findNoArgMethod(instance.getClass(), name);
        if (method == null) {
            return null;
        }
        try {
            method.setAccessible(true);
            return method.invoke(instance);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static Object readField(Object instance, String name) {
        Field field = findField(instance.getClass(), name);
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(name)) {
                    return field;
                }
            }
        }
        return null;
    }

    private static Integer intField(Object instance, String name) {
        Object value = readField(instance, name);
        return value instanceof Integer number ? number : null;
    }

    private static String stringField(Object instance, String name) {
        Object value = readField(instance, name);
        return value instanceof String text ? text : null;
    }

    private static int intOrMin(Object instance, String primary, String secondary) {
        Integer value = intField(instance, primary);
        if (value == null) {
            value = intField(instance, secondary);
        }
        return value == null ? Integer.MIN_VALUE : value;
    }
}
