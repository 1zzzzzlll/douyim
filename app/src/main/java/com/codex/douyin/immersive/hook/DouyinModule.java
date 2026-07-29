package com.codex.douyin.immersive.hook;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

public final class DouyinModule extends XposedModule {
    static final String TAG = "DouyinImmersive";
    static final String TARGET_PACKAGE = "com.ss.android.ugc.aweme";
    private static final Set<ClassLoader> INSTALLED_LOADERS =
            Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded in " + param.getProcessName()
                + ", framework=" + getFrameworkName()
                + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        synchronized (INSTALLED_LOADERS) {
            if (!INSTALLED_LOADERS.add(param.getClassLoader())) {
                log(Log.DEBUG, TAG, "hooks already installed for this class loader");
                return;
            }
        }

        installSubsystem("activity lifecycle", this::hookActivityLifecycle);
        installSubsystem("activity touch", this::hookActivityTouch);
        installSubsystem("instrumentation lifecycle", this::hookInstrumentationLifecycle);
        installSubsystem(
                "Douyin activity lifecycle",
                () -> hookDouyinActivityLifecycle(param.getClassLoader())
        );
        installSubsystem(
                "feed content tracker",
                () -> {
                    SharedPreferences preferences = getRemotePreferences(
                            com.codex.douyin.immersive.FilterPreferences.NAME
                    );
                    FeedContentTracker.install(
                            this,
                            param.getClassLoader(),
                            preferences
                    );
                }
        );
        installSubsystem(
                "player tracker",
                () -> PlayerHooks.install(this, param.getClassLoader())
        );
        log(Log.INFO, TAG, "hook installation finished for " + param.getPackageName());
    }

    private void installSubsystem(String name, HookInstaller installer) {
        try {
            installer.install();
            log(Log.INFO, TAG, "hook subsystem installed: " + name);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "hook subsystem failed: " + name, error);
        }
    }

    @FunctionalInterface
    private interface HookInstaller {
        void install() throws Throwable;
    }

    private void hookActivityTouch() throws NoSuchMethodException {
        Method dispatchTouchEvent = Activity.class.getDeclaredMethod(
                "dispatchTouchEvent", MotionEvent.class
        );
        hook(dispatchTouchEvent)
                .setId("douyin-immersive-activity-touch")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Activity activity = (Activity) chain.getThisObject();
                    MotionEvent event = (MotionEvent) chain.getArgs().get(0);
                    ImmersiveUi.beforeActivityTouch(activity, event);
                    Object result = chain.proceed();
                    ImmersiveUi.onActivityTouch(activity, event);
                    return result;
                });
    }

    private void hookActivityLifecycle() throws NoSuchMethodException {
        Method onResume = Activity.class.getDeclaredMethod("onResume");
        hook(onResume)
                .setId("douyin-immersive-activity-resume")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    ImmersiveUi.onActivityResumed((Activity) chain.getThisObject());
                    return result;
                });

        Method onPause = Activity.class.getDeclaredMethod("onPause");
        hook(onPause)
                .setId("douyin-immersive-activity-pause")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    ImmersiveUi.onActivityPaused((Activity) chain.getThisObject());
                    return chain.proceed();
                });
    }

    private void hookInstrumentationLifecycle() throws NoSuchMethodException {
        Method callResume = Instrumentation.class.getDeclaredMethod(
                "callActivityOnResume", Activity.class
        );
        hook(callResume)
                .setId("douyin-immersive-instrumentation-resume")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    ImmersiveUi.onActivityResumed((Activity) chain.getArgs().get(0));
                    return result;
                });

        Method callPause = Instrumentation.class.getDeclaredMethod(
                "callActivityOnPause", Activity.class
        );
        hook(callPause)
                .setId("douyin-immersive-instrumentation-pause")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    ImmersiveUi.onActivityPaused((Activity) chain.getArgs().get(0));
                    return chain.proceed();
                });
    }

    private void hookDouyinActivityLifecycle(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> splashActivity = Class.forName(
                "com.ss.android.ugc.aweme.splash.SplashActivity", false, loader
        );

        Method onCreate = splashActivity.getDeclaredMethod(
                "onCreate", android.os.Bundle.class
        );
        hook(onCreate)
                .setId("douyin-immersive-splash-create")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    ImmersiveUi.onActivityResumed((Activity) chain.getThisObject());
                    return result;
                });

        Method onResume = splashActivity.getDeclaredMethod("onResume");
        hook(onResume)
                .setId("douyin-immersive-splash-resume")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    ImmersiveUi.onActivityResumed((Activity) chain.getThisObject());
                    return result;
                });

        Method onStop = splashActivity.getDeclaredMethod("onStop");
        hook(onStop)
                .setId("douyin-immersive-splash-stop")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    ImmersiveUi.onActivityPaused((Activity) chain.getThisObject());
                    return chain.proceed();
                });
    }
}
