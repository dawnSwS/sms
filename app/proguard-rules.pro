-repackageclasses ''
-allowaccessmodification
-dontusemixedcaseclassnames

-dontwarn de.robv.android.xposed.**

-keep class com.example.appcore.CoreHook {
    public <init>();
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}

-keep class com.example.appcore.IAnalysis** { *; }
-keep class com.example.appcore.AnalysisSvc { *; }