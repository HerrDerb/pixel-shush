-keepattributes *Annotation*

# Sensor hub (CHRE) classes are system APIs, missing from the public SDK. Keep the
# callback overrides, R8 cannot see that the framework calls them.
-dontwarn android.hardware.location.**
-keep class com.herrderb.pssst.ShushHub$Callback { *; }
