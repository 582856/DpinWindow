# The modern module loader loads this entry by its name in java_init.list.
-keep class com.maple.dpinviewer.DpinModule { *; }
-keep class com.maple.dpinviewer.PowerProbe { public static void main(java.lang.String[]); }
-keep class com.maple.dpinviewer.AudioMixFix { public static void main(java.lang.String[]); }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
