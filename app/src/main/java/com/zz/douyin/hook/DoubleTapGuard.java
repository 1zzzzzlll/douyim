package com.zz.douyin.hook;

import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/** Consume the recognized double tap, without turning it into two single taps. */
final class DoubleTapGuard {
    private static final Set<Method> HOOKED = new HashSet<>();

    private DoubleTapGuard() {}

    static void installFeedInterceptor(DouyinModule module, ClassLoader loader) throws Exception {
        // Douyin 40.5's LongPressLayout touch listener recognizes double taps itself.
        // It cancels the pending single tap and sets its double-tap flag BEFORE this
        // callback. Returning true consumes the gesture before any feed component,
        // like request or heart animation, while leaving its gesture state intact.
        Class<?> group = Class.forName(
                "com.ss.android.ugc.aweme.feed.plato.core.FeedComponentGroup", false, loader);
        Method intercept = group.getDeclaredMethod(
                "interceptDoubleClick", MotionEvent.class, MotionEvent.class);
        if (intercept.getReturnType() != boolean.class) {
            throw new NoSuchMethodException("unexpected feed double-click interceptor contract");
        }
        module.hook(intercept)
                .setId("douyin-block-feed-double-click")
                .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (ImmersiveUi.shouldBlockDoubleTap()) {
                        Log.i(DouyinModule.TAG, "blocked feed double tap before component dispatch");
                        return true;
                    }
                    return chain.proceed();
                });
    }

    static void installPlatformListeners(DouyinModule module) throws Exception {
        for (Constructor<?> constructor : GestureDetector.class.getDeclaredConstructors()) {
            module.hook(constructor)
                    .setId("douyin-double-tap-constructor-" + constructor.toGenericString())
                    .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        for (Object argument : chain.getArgs()) {
                            if (argument instanceof GestureDetector.OnDoubleTapListener) {
                                hookListener(module, argument.getClass());
                            }
                        }
                        return result;
                    });
        }
        module.hook(GestureDetector.class.getDeclaredMethod(
                        "setOnDoubleTapListener", GestureDetector.OnDoubleTapListener.class))
                .setId("douyin-double-tap-listener-setter")
                .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Object listener = chain.getArgs().get(0);
                    if (listener != null) hookListener(module, listener.getClass());
                    return result;
                });
    }

    private static synchronized void hookListener(DouyinModule module, Class<?> type)
            throws NoSuchMethodException {
        for (String name : new String[]{"onDoubleTap", "onDoubleTapEvent"}) {
            Method method = type.getMethod(name, MotionEvent.class);
            if (HOOKED.contains(method)) continue;
            module.hook(method)
                    .setId("douyin-block-" + method.toGenericString())
                    .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        // DPlayer can dispatch from a parent before LongPressLayout.
                        // The module is installed only inside Douyin, so include these
                        // native video/zoom callbacks regardless of the current child.
                        if (ImmersiveUi.shouldBlockDoubleTap()) {
                            if (name.equals("onDoubleTap")) {
                                Log.i(DouyinModule.TAG, "blocked native double tap: "
                                        + chain.getThisObject().getClass().getName());
                            }
                            return true;
                        }
                        return chain.proceed();
                    });
            HOOKED.add(method);
            module.log(Log.DEBUG, DouyinModule.TAG, "double tap listener: " + method);
        }
    }
}
