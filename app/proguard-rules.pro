# Media3 extensions are instantiated reflectively.
-keep class org.jellyfin.media3.decoder.** { *; }
-keep class org.videolan.libvlc.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn org.videolan.**
-dontwarn org.jellyfin.**

# Keep player engine class names referenced from settings / reflection.
-keep class com.streamline.play.player.** { *; }

# QuickJS native bindings used by the pako gzip fallback.
-keep class app.cash.quickjs.** { *; }
-dontwarn app.cash.quickjs.**

# ExoPlayer resolves the frame-processor factory by class name, so the whole
# media3-effect module must survive minification (shaders ship as assets).
-keep class androidx.media3.effect.** { *; }
-dontwarn androidx.media3.effect.**
