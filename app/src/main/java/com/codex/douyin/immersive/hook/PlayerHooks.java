package com.codex.douyin.immersive.hook;

import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class PlayerHooks {
    private static final String[] ENGINE_CLASSES = {
            "com.ss.ttvideoengine.TTVideoEngine",
            "com.ss.ttvideoengine.TTVideoEngineImpl",
            "com.ss.ttvideoengine.TTVideoEngineImplV2",
            "com.ss.android.ugc.aweme.player.sdk.impl.SimplifyPlayerImpl",
            "com.ss.android.ugc.playerkit.simapicommon.model.SimVideoUrlModel"
    };

    private static final Set<Class<?>> HOOKED_LISTENER_CLASSES =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Object, WeakReference<Object>> LISTENER_OWNERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private PlayerHooks() {
    }

    static void install(DouyinModule module, ClassLoader loader) {
        int hooked = 0;
        for (String className : ENGINE_CLASSES) {
            try {
                Class<?> type = Class.forName(className, false, loader);
                hooked += hookEngineClass(module, type);
            } catch (ClassNotFoundException ignored) {
                module.log(Log.DEBUG, DouyinModule.TAG, "player class absent: " + className);
            } catch (Throwable error) {
                module.log(Log.WARN, DouyinModule.TAG,
                        "player hook failed for " + className, error);
            }
        }
        module.log(Log.INFO, DouyinModule.TAG, "installed " + hooked + " player hooks");
    }

    private static int hookEngineClass(DouyinModule module, Class<?> type) {
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            String name = method.getName();
            String lower = name.toLowerCase();

            if (isPlayMethod(lower, method)) {
                hookState(module, method, true);
                count++;
            } else if (isPauseMethod(lower, method)) {
                hookState(module, method, false);
                count++;
            } else if (lower.contains("completion") || lower.contains("completed")) {
                hookEngineCompletion(module, method);
                count++;
            } else if (lower.startsWith("set") && lower.contains("listener")) {
                hookListenerSetter(module, method);
                count++;
            }
        }
        return count;
    }

    private static boolean isPlayMethod(String name, Method method) {
        return method.getParameterCount() == 0
                && (name.equals("play") || name.equals("start") || name.equals("resume"));
    }

    private static boolean isPauseMethod(String name, Method method) {
        return method.getParameterCount() == 0
                && (name.startsWith("pause")
                || name.equals("stop")
                || name.equals("release")
                || name.equals("releaseasync"));
    }

    private static void hookState(DouyinModule module, Method method, boolean playing) {
        module.hook(method)
                .setId("douyin-player-state-" + method.toGenericString())
                .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (playing) {
                        PlaybackState.playing(chain.getThisObject());
                    } else {
                        PlaybackState.paused(chain.getThisObject());
                    }
                    return result;
                });
    }

    private static void hookEngineCompletion(DouyinModule module, Method method) {
        module.hook(method)
                .setId("douyin-player-complete-" + method.toGenericString())
                .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    PlaybackState.completed(chain.getThisObject());
                    return result;
                });
    }

    private static void hookListenerSetter(DouyinModule module, Method method) {
        module.hook(method)
                .setId("douyin-player-listener-" + method.toGenericString())
                .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Object owner = chain.getThisObject();
                    for (Object argument : chain.getArgs()) {
                        if (argument != null) {
                            LISTENER_OWNERS.put(argument, new WeakReference<>(owner));
                            hookListenerObject(module, argument);
                        }
                    }
                    return result;
                });
    }

    private static void hookListenerObject(DouyinModule module, Object listener) {
        Class<?> type = listener.getClass();
        synchronized (HOOKED_LISTENER_CLASSES) {
            if (!HOOKED_LISTENER_CLASSES.add(type)) {
                return;
            }
        }

        int count = 0;
        for (Method method : type.getMethods()) {
            if (Modifier.isAbstract(method.getModifiers()) || Modifier.isNative(method.getModifiers())) {
                continue;
            }
            String lower = method.getName().toLowerCase();
            try {
                if (lower.equals("oncompletion") || lower.equals("oncompleted")) {
                    hookListenerCompletion(module, method);
                    count++;
                } else if (lower.equals("onplaybackstatechanged")) {
                    hookPlaybackStateCallback(module, method);
                    count++;
                }
            } catch (Throwable error) {
                module.log(Log.WARN, DouyinModule.TAG,
                        "listener method hook failed: " + method, error);
            }
        }
        module.log(Log.DEBUG, DouyinModule.TAG,
                "listener " + type.getName() + ": " + count + " hooks");
    }

    private static void hookListenerCompletion(DouyinModule module, Method method) {
        module.hook(method)
                .setId("douyin-player-listener-complete-" + method.toGenericString())
                .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    WeakReference<Object> ownerReference =
                            LISTENER_OWNERS.get(chain.getThisObject());
                    Object owner = ownerReference == null ? null : ownerReference.get();
                    if (owner != null) {
                        PlaybackState.completed(owner);
                    }
                    return result;
                });
    }

    private static void hookPlaybackStateCallback(DouyinModule module, Method method) {
        module.hook(method)
                .setId("douyin-player-callback-state-" + method.toGenericString())
                .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Integer state = lastInteger(chain.getArgs());
                    if (state != null && state >= 0 && state <= 4) {
                        WeakReference<Object> ownerReference =
                                LISTENER_OWNERS.get(chain.getThisObject());
                        Object owner = ownerReference == null
                                ? null
                                : ownerReference.get();
                        // TTVideoEngine 39.7.0: 0=stopped, 1=playing,
                        // 2=paused, 3=error, 4=initializing.
                        if (owner != null && state == 1) {
                            PlaybackState.playingFromCallback(owner);
                        } else if (owner != null && state == 2) {
                            PlaybackState.paused(owner);
                        } else if (owner != null && state == 3) {
                            PlaybackState.error(owner);
                        }
                    }
                    return result;
                });
    }

    private static Integer lastInteger(java.util.List<Object> values) {
        for (int index = values.size() - 1; index >= 0; index--) {
            Object value = values.get(index);
            if (value instanceof Integer integer) {
                return integer;
            }
        }
        return null;
    }
}
