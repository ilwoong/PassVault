# TST-12: 릴리스 빌드에서 로그 호출을 제거한다 (SEC-10).
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
