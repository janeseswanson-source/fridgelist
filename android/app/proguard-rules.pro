# R8 rules for release (A11). Smoke-tested with the `devMinified` build type against the emulators.

# kotlinx.serialization: @Serializable navigation routes and CONTRACT types.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Glance creates widget actions by class name (actionRunCallback<T>): keep their no-arg constructors.
# Found by the minified smoke test: without this, widget checkboxes did nothing in release.
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    <init>();
}

# Crashlytics: readable stack traces (mappings are uploaded by the Crashlytics plugin in prod).
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
