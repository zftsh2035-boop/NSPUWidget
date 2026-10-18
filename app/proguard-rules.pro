# Android Gradle Plugin includes manifest, WorkManager, Compose and Kotlin keep rules.
# Preserve metadata useful for Kotlin and Jsoup while allowing R8 to shrink app code.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# No broad -keep ru.nspu.schedule.**: doing so would disable most shrinking.
# WorkManager/AndroidX automatic consumer rules handle their own reflection.
