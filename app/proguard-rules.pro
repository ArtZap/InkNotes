# InkNotes: rules for R8 (release and the "minified" test build).

# Our own code is small next to the libraries and is read back from files written by every
# earlier version (enum names in notebook.json and settings, codec classes): kept whole, so
# shrinking can't change how notebooks are read. R8 still shrinks the libraries.
-keep class dev.inknotes.** { *; }

# PdfBox for Android: optional JPEG 2000 and crypto backends are not shipped.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.xml.**
-dontwarn org.apache.commons.logging.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }

# ML Kit Digital Ink and androidx.ink ship their own rules; their native code calls back
# into Java by name, so their classes keep their names.
-keep class com.google.mlkit.vision.digitalink.** { *; }
-keep class androidx.ink.** { *; }
-keep class androidx.input.motionprediction.** { *; }

# Line numbers in crash traces (with mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
