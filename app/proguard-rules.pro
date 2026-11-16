-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.pocketagent.** {
    *** Companion;
}
-keepclasseswithmembers class app.pocketagent.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn org.jspecify.**
