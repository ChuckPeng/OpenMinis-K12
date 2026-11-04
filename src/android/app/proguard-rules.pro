# Tink references errorprone annotations that aren't shipped at runtime
-dontwarn com.google.errorprone.annotations.**

# [T-android-vad-jni-keep / GH#250] RealTimeCutVAD's C++ layer calls back into
# VADWrapper BY NAME through JNI (GetMethodID "onVoiceStart" / "onVoiceEnd" /
# "onVoiceDidContinue"). Those three are PRIVATE Java methods with no Java-side
# caller, so R8 sees them as dead and renames them — verified in the shipped
# 1.12 release dex, where they had become b()V / c()V / d()V and the `callback`
# field was removed outright. The native lookup then fails with
#
#   java.lang.NoSuchMethodError: no non-static method
#   "Lio/codeconcept/realtimecutvadlibrary/VADWrapper;.onVoiceStart()V"
#
# thrown from processAudio (a native method), which is exactly the reported
# crash: every release user who tapped the mic got "Voice detection failed"
# before any STT provider was even contacted.
#
# proguard-android-optimize.txt keeps `native` method DECLARATIONS and @Keep,
# but nothing protects an ordinary Java method that only native code invokes.
#
# Kept whole rather than member-scoped: the library is five classes, so the
# size cost is negligible, whereas a narrow rule that missed a member the
# native side also touches (the `vadInstance` handle, for one) would degrade
# into a subtler runtime failure.
#
# NOTE FOR VERIFICATION: debug builds don't minify, so this bug is invisible
# there. Any change here must be checked against an assembleRelease APK.
-keep class io.codeconcept.realtimecutvadlibrary.** { *; }

# Symja 3.2.0 (tutoring A4). Builtins and JAS call each other through
# generated tables and occasional reflection. Keep the engine, and let R8
# shrink Guava / fastutil / Hipparchus down to what those classes reach.
# java.awt / ImageIO / JMX are only referenced from plotting and desktop
# helpers that the tutoring path does not load.
# Device Ed25519 seed. The named curve table is loaded by a string. Keep the
# small library so release builds still sign with the same 64-byte format.
-keep class net.i2p.crypto.eddsa.** { *; }

-keep class org.matheclipse.** { *; }
-keep class edu.jas.** { *; }
-keep class uk.ac.ed.ph.snuggletex.** { *; }
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn java.lang.management.**
-dontwarn javax.tools.**
-dontwarn javax.script.**
-dontwarn org.osgi.**
-dontwarn sun.misc.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.apache.logging.log4j.core.**
-dontwarn org.apache.logging.log4j.util.ProcessIdUtil
-dontwarn afu.org.checkerframework.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn java.rmi.**
-dontwarn javax.lang.model.**
