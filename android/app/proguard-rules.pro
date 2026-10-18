# Release rules are completed in A11 (R8, Crashlytics mappings).
# Keep kotlinx.serialization metadata for @Serializable navigation routes and contract types.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
