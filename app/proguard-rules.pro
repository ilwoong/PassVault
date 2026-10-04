# TST-12: 릴리스 빌드에서 로그 호출을 제거한다 (SEC-10).
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}

# argon2kt 는 소비자 R8 규칙을 싣지 않는다 (1.6.0 AAR 확인). JNI 로 오가는 클래스·메서드 이름이
# 바뀌면 릴리스 빌드에서 Argon2 호출이 실패한다 (CRY-02). 패키지 전체를 그대로 둔다.
-keep class com.lambdapioneer.argon2kt.** { *; }
